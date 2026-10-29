package fmgp.rpc

import java.nio.charset.StandardCharsets.UTF_8
import zio.*
import zio.stream.*

/** One worker per admitted stream; the receiver never waits on producer pulls or interruption finalizers. */
private[rpc] final class RpcServerStreams private (
    dispatcher: RpcDispatcher,
    limits: RpcWebSocket.Limits,
    scope: Scope,
    entries: Ref[Map[String, RpcServerStreams.Entry]],
    highest: Ref[Long],
    unary: Ref[Set[String]],
    send: String => Task[Unit],
    sendBytes: (String, Chunk[Byte]) => Task[Unit],
    abort: UIO[Unit]
) {
  import RpcServerStreams.*
  private val protocol = RpcError(RpcError.Code.ProtocolError)
  def contains(id: String): UIO[Boolean] = entries.get.map(_.contains(id))
  def control(control: RpcWire.Control): IO[RpcError, Unit] = control match {
    case RpcWire.Control.Open(request, binary) =>
      for {
        previous <- highest.get
        collision <- unary.get.map(_.contains(request.id))
        _ <- ZIO.fail(protocol).when(request.id.toLong <= previous || collision)
        _ <- highest.set(request.id.toLong)
        queue <- Queue.bounded[Unit](1)
        busy <- Ref.make(false)
        fiber <- Promise.make[Nothing, Fiber.Runtime[Nothing, Unit]]
        entry = Entry(queue, busy, fiber)
        admitted <- entries.modify { current =>
          if current.size >= limits.maxStreams then (false, current)
          else (true, current.updated(request.id, entry))
        }
        _ <-
          if !admitted then
            send(
              RpcWire.streamResponse(request.id, RpcWire.StreamReply.Error(RpcError(RpcError.Code.LimitExceeded)))
            ).orDie
          else {
            val stream = dispatcher
              .dispatchStream(request.service, request.version, request.method, request.arguments, binary)
              .flatMap {
                case RpcStreamChunk.Bytes(bytes) if binary =>
                  if limits.maxFrameBytes <= RpcWire.binaryHeaderBytes then
                    ZStream.fail(RpcStreamError.Infrastructure(RpcError(RpcError.Code.LimitExceeded)))
                  else
                    ZStream
                      .fromChunk(bytes)
                      .rechunk((limits.maxFrameBytes - RpcWire.binaryHeaderBytes).min(limits.maxChunkElements))
                      .chunks
                      .map(RpcStreamChunk.Bytes(_))
                case RpcStreamChunk.Values(values) if !binary =>
                  // Each individual element is checked before sending; no unbounded encoded batch is built.
                  ZStream.fromChunk(values).mapZIO { value =>
                    val chunk = RpcWire.StreamReply.Chunk(Chunk.single(value))
                    ZIO
                      .fail(RpcStreamError.Infrastructure(RpcError(RpcError.Code.LimitExceeded)))
                      .when(RpcWire.streamResponse(request.id, chunk).getBytes(UTF_8).length > limits.maxFrameBytes)
                      .as(RpcStreamChunk.Values(Chunk.single(value)))
                  }
                case _ => ZStream.fail(RpcStreamError.Infrastructure(protocol))
              }
              .rechunk(1)
            val body = ZIO
              .scoped {
                for {
                  _ <- send(RpcWire.streamResponse(request.id, RpcWire.StreamReply.Opened))
                  pull <- stream.toPull
                  terminal <- {
                    def next: Task[RpcWire.StreamReply] = queue.take *> pull.foldZIO(
                      error =>
                        ZIO.succeed(error match {
                          case None                                       => RpcWire.StreamReply.End
                          case Some(RpcStreamError.Domain(value))         => RpcWire.StreamReply.Failure(value)
                          case Some(RpcStreamError.Infrastructure(error)) => RpcWire.StreamReply.Error(error)
                        }),
                      chunks => {
                        val sendChunk = chunks.headOption match {
                          case Some(RpcStreamChunk.Bytes(bytes))   => sendBytes(request.id, bytes)
                          case Some(RpcStreamChunk.Values(values)) =>
                            send(RpcWire.streamResponse(request.id, RpcWire.StreamReply.Chunk(values)))
                          case None if binary => sendBytes(request.id, Chunk.empty)
                          case None => send(RpcWire.streamResponse(request.id, RpcWire.StreamReply.Chunk(Chunk.empty)))
                        }
                        busy.set(false) *> sendChunk *> ZIO.suspendSucceed(next)
                      }
                    )
                    next
                  }
                } yield terminal
              }
              .flatMap(terminal => send(RpcWire.streamResponse(request.id, terminal)))
            val run = ZIO.uninterruptibleMask { restore =>
              restore(body)
                .catchAllCause(cause => if cause.isInterruptedOnly then ZIO.unit else abort)
                .ensuring(
                  (queue.shutdown *> entries
                    .modify { current =>
                      current.get(request.id) match {
                        case Some(entry) => (entry.cancelled, current.updated(request.id, entry.copy(finished = true)))
                        case None        => (false, current)
                      }
                    }
                    .flatMap(cancelled =>
                      send(RpcWire.streamResponse(request.id, RpcWire.StreamReply.Cancelled))
                        .orElse(abort)
                        .when(cancelled)
                        .unit
                    ))
                    .ensuring(entries.update(_ - request.id))
                )
            }
            run.forkIn(scope).flatMap(fiber.succeed).unit
          }
      } yield ()
    case RpcWire.Control.Pull(id) =>
      for {
        entry <- entries.get.flatMap(m => ZIO.fromOption(m.get(id)).orElseFail(protocol))
        wasBusy <- entry.busy.getAndSet(true)
        _ <- ZIO.fail(protocol).when(entry.cancelled || entry.finished || wasBusy)
        _ <- entry.queue.offer(()).unit
      } yield ()
    case RpcWire.Control.Cancel(id) =>
      unary.get.flatMap(ids => ZIO.fail(protocol).when(ids.contains(id))) *> entries
        .modify { current =>
          current.get(id) match {
            case Some(entry) if !entry.finished =>
              (Some(entry.fiber), current.updated(id, entry.copy(cancelled = true)))
            case _ => (None, current)
          }
        }
        .flatMap {
          case Some(fiber) => fiber.await.flatMap(_.interruptFork)
          case None        =>
            // An interrupted OPEN send may never have reached this endpoint. Retire its ID anyway:
            // acknowledgement is safe, and a delayed OPEN cannot resurrect the cancelled stream.
            highest.update(_.max(id.toLong)) *>
              send(RpcWire.streamResponse(id, RpcWire.StreamReply.Cancelled)).orElse(abort)
        }
  }
}
private[rpc] object RpcServerStreams {
  private final case class Entry(
      queue: Queue[Unit],
      busy: Ref[Boolean],
      fiber: Promise[Nothing, Fiber.Runtime[Nothing, Unit]],
      cancelled: Boolean = false,
      finished: Boolean = false
  )
  def make(
      dispatcher: RpcDispatcher,
      limits: RpcWebSocket.Limits,
      scope: Scope,
      unary: Ref[Set[String]],
      send: String => Task[Unit],
      sendBytes: (String, Chunk[Byte]) => Task[Unit],
      abort: UIO[Unit]
  ): UIO[RpcServerStreams] =
    for {
      entries <- Ref.make(Map.empty[String, Entry])
      highest <- Ref.make(0L)
    } yield new RpcServerStreams(dispatcher, limits, scope, entries, highest, unary, send, sendBytes, abort)
}
