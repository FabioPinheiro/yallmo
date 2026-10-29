package fmgp.rpc

import zio.*
import zio.json.ast.Json
import zio.stream.*

private[rpc] final class RpcClientStreams private (
    limits: RpcWebSocket.Limits,
    state: Ref[RpcClientStreams.State],
    allocate: IO[RpcError, String],
    writes: Semaphore,
    send: String => IO[RpcError, Unit],
    abort: RpcError => UIO[Unit]
) {
  import RpcClientStreams.*
  import RpcWire.StreamReply
  private val protocol = RpcError(RpcError.Code.ProtocolError)
  def contains(id: String): UIO[Boolean] = state.get.map(_.entries.contains(id))
  def disconnect(error: RpcError): UIO[Unit] =
    state
      .getAndSet(State(closed = true))
      .flatMap(current =>
        ZIO.foreachDiscard(current.entries.values)(entry => ZIO.foreachDiscard(entry.waiting)(_.fail(error)))
      )
      .uninterruptible

  private def reserve: IO[RpcError, Reservation] = ZIO.suspendSucceed {
    val reservation = new Reservation
    state
      .modify { current =>
        if current.closed then (Left(RpcError(RpcError.Code.Disconnected)), current)
        else if current.reservations.size >= limits.maxStreams then
          (Left(RpcError(RpcError.Code.LimitExceeded)), current)
        else (Right(reservation), current.copy(reservations = current.reservations + reservation))
      }
      .flatMap(ZIO.fromEither)
  }

  def receive(id: String, reply: StreamReply): IO[RpcError, Unit] =
    state
      .modify { current =>
        current.entries.get(id) match {
          case None        => (Right(None), current)
          case Some(entry) =>
            val valid = reply match {
              case StreamReply.Chunk(values) =>
                !entry.opening && !entry.binary && values.size <= limits.maxChunkElements
              case StreamReply.Opened                       => entry.opening
              case StreamReply.Cancelled                    => entry.closing
              case StreamReply.End | StreamReply.Failure(_) => !entry.opening
              case StreamReply.Error(_)                     => true
            }
            if !valid then (Left(protocol), current)
            else if entry.closing then {
              val next = if reply == StreamReply.Cancelled then current.remove(id) else current
              (Right(None), next)
            } else if entry.waiting.isEmpty || reply == StreamReply.Cancelled then (Left(protocol), current)
            else {
              val terminal = reply match {
                case StreamReply.End | StreamReply.Failure(_) | StreamReply.Error(_) => true
                case _                                                               => false
              }
              val next = if terminal then current.remove(id)
              else current.copy(entries = current.entries.updated(id, entry.copy(opening = false, waiting = None)))
              (Right(entry.waiting.map(_ -> reply)), next)
            }
        }
      }
      .flatMap(ZIO.fromEither)
      .flatMap(result => ZIO.foreachDiscard(result)((promise, reply) => promise.succeed(reply)))

  // Bytes use the same phase validation as JSON, but never enter a JSON AST.
  def receiveBytes(id: String, bytes: Chunk[Byte]): IO[RpcError, Unit] =
    state
      .modify { current =>
        current.entries.get(id) match {
          case Some(entry) if entry.binary && !entry.opening && bytes.size <= limits.maxChunkElements =>
            if entry.closing then (Right(None), current)
            else
              entry.waiting match {
                case Some(promise) =>
                  (
                    Right(Some(promise -> bytes)),
                    current.copy(entries = current.entries.updated(id, entry.copy(waiting = None)))
                  )
                case None => (Left(protocol), current)
              }
          case _ => (Left(protocol), current)
        }
      }
      .flatMap(ZIO.fromEither)
      .flatMap(result => ZIO.foreachDiscard(result)((promise, bytes) => promise.succeed(ReplyBytes(bytes))))

  private def cancel(reservation: Reservation): UIO[Unit] = ZIO.uninterruptible {
    state
      .modify { current =>
        current.entries.find(_._2.reservation == reservation) match {
          case Some((id, entry)) if !entry.closing =>
            (Some(id), current.copy(entries = current.entries.updated(id, entry.copy(closing = true, waiting = None))))
          case Some(_) => (None, current)
          case None    => (None, current.copy(reservations = current.reservations - reservation))
        }
      }
      .flatMap(id =>
        ZIO.foreachDiscard(id)(id =>
          writes
            .withPermit(send(RpcWire.streamControl(RpcWire.Control.Cancel(id))))
            .interruptible
            .timeoutFail(RpcError(RpcError.Code.TimedOut))(limits.timeout)
            .catchAll(abort)
        )
      )
  }

  private def await(promise: Promise[RpcError, Reply]): IO[RpcStreamError, Reply] =
    promise.await.mapError(RpcStreamError.Infrastructure(_))

  def stream(
      service: String,
      version: Int,
      method: String,
      args: Chunk[Json],
      binary: Boolean
  ): ZStream[Any, RpcStreamError, RpcStreamChunk] =
    ZStream.unwrapScoped {
      (for {
        // Reserve before any write wait; closing and reservations share the same atomic state.
        reservation <- ZIO.acquireRelease(reserve)(cancel).mapError(RpcStreamError.Infrastructure(_))
        promise <- Promise.make[RpcError, Reply]
        id <- writes
          .withPermit {
            for {
              _ <- state.get.flatMap(current => ZIO.fail(RpcError(RpcError.Code.Disconnected)).when(current.closed))
              id <- allocate
              _ <- state
                .modify { current =>
                  if current.closed || !current.reservations.contains(reservation) then
                    (Left(RpcError(RpcError.Code.Disconnected)), current)
                  else
                    (
                      Right(()),
                      current.copy(entries =
                        current.entries.updated(id, Entry(reservation, binary, true, false, Some(promise)))
                      )
                    )
                }
                .flatMap(ZIO.fromEither)
              _ <- (state.get.flatMap(current => ZIO.fail(RpcError(RpcError.Code.Disconnected)).when(current.closed)) *>
                send(
                  RpcWire.streamControl(
                    RpcWire.Control.Open(RpcWire.Request(id, service, version, method, args), binary)
                  )
                ))
                .tapError { error =>
                  if error.code == RpcError.Code.LimitExceeded then state.update(_.remove(id))
                  else abort(error)
                }
            } yield id
          }
          .mapError(RpcStreamError.Infrastructure(_))
        opened <- await(promise)
        _ <- opened match {
          case StreamReply.Opened       => ZIO.unit
          case StreamReply.Error(error) => ZIO.fail(RpcStreamError.Infrastructure(error))
          case _                        => ZIO.fail(RpcStreamError.Infrastructure(protocol))
        }
      } yield ZStream.repeatZIOChunkOption {
        (for {
          pending <- Promise.make[RpcError, Reply]
          _ <- state
            .modify { current =>
              current.entries.get(id) match {
                case Some(entry) if !entry.closing && entry.waiting.isEmpty =>
                  (Right(()), current.copy(entries = current.entries.updated(id, entry.copy(waiting = Some(pending)))))
                case _ => (Left(RpcError(RpcError.Code.Disconnected)), current)
              }
            }
            .flatMap(ZIO.fromEither)
            .mapError(RpcStreamError.Infrastructure(_))
          _ <- writes
            .withPermit(send(RpcWire.streamControl(RpcWire.Control.Pull(id))))
            .mapError(RpcStreamError.Infrastructure(_))
          reply <- await(pending)
        } yield reply)
          .timeoutFail(RpcStreamError.Infrastructure(RpcError(RpcError.Code.TimedOut)))(limits.timeout)
          .mapError(Some(_))
          .flatMap {
            case ReplyBytes(bytes)          => ZIO.succeed(Chunk.single(RpcStreamChunk.Bytes(bytes)))
            case StreamReply.Chunk(values)  => ZIO.succeed(Chunk.single(RpcStreamChunk.Values(values)))
            case StreamReply.End            => ZIO.fail(None)
            case StreamReply.Failure(value) => ZIO.fail(Some(RpcStreamError.Domain(value)))
            case StreamReply.Error(error)   => ZIO.fail(Some(RpcStreamError.Infrastructure(error)))
            case _                          => ZIO.fail(Some(RpcStreamError.Infrastructure(protocol)))
          }
      }).timeoutFail(RpcStreamError.Infrastructure(RpcError(RpcError.Code.TimedOut)))(limits.timeout)
    }
}
private[rpc] object RpcClientStreams {
  private final class Reservation
  private final case class ReplyBytes(values: Chunk[Byte])
  private type Reply = RpcWire.StreamReply | ReplyBytes
  private final case class Entry(
      reservation: Reservation,
      binary: Boolean,
      opening: Boolean,
      closing: Boolean,
      waiting: Option[Promise[RpcError, Reply]]
  )
  private final case class State(
      entries: Map[String, Entry] = Map.empty,
      reservations: Set[Reservation] = Set.empty,
      closed: Boolean = false
  ) {
    def remove(id: String): State = copy(
      entries = entries - id,
      reservations = entries.get(id).fold(reservations)(entry => reservations - entry.reservation)
    )
  }
  def make(
      limits: RpcWebSocket.Limits,
      allocate: IO[RpcError, String],
      writes: Semaphore,
      send: String => IO[RpcError, Unit],
      abort: RpcError => UIO[Unit]
  ): UIO[RpcClientStreams] =
    Ref.make(State()).map(new RpcClientStreams(limits, _, allocate, writes, send, abort))
}
