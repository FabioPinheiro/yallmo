package fmgp.rpc

import java.net.URI
import java.nio.charset.StandardCharsets
import zio.*
import zio.stream.*
import zio.json.*
import zio.json.ast.Json

/** Shared client protocol and lifecycle; platform adapters own only socket I/O. */
object RpcWebSocketClient extends RpcWebSocketClientPlatform {

  /** Per-connection bounds. maxPending is unary-only; maxStreams includes cancelling streams until cleanup.
    * maxChunkElements also bounds byte payload elements, in addition to the binary frame's 12-byte header. timeout is a
    * client connect/unary/open/per-pull deadline, not a total stream lifetime or idle TTL.
    */
  final case class Limits(
      maxFrameBytes: Int = 65536,
      maxPending: Int = 32,
      timeout: Duration = 10.seconds,
      maxStreams: Int = 16,
      maxChunkElements: Int = 65524
  ) {
    require(maxFrameBytes > 0 && maxFrameBytes <= 1024 * 1024, "maxFrameBytes must be 1..1048576")
    require(maxStreams > 0 && maxStreams <= 1024, "maxStreams must be 1..1024")
    require(maxChunkElements > 0 && maxChunkElements <= 65536, "maxChunkElements must be 1..65536")
    require(maxPending > 0 && maxPending <= 1024, "maxPending must be 1..1024")
    require(timeout > Duration.Zero && timeout < Duration.Infinity, "timeout must be finite and positive")
  }

  def connect(port: Int, token: RpcToken): ZIO[Scope, RpcError, RpcConnection] =
    connect(s"ws://127.0.0.1:$port/rpc", token)

  /** One receiver per connection. Streams are lazy, independently materialized, stop-and-wait and scoped. Explicit
    * close releases owned transport resources without closing the caller's scope. No retry/replay; cancelling a unary
    * call removes only its local waiter. Rejected upgrades are opaque Disconnected errors on both platforms.
    */
  def connect(endpoint: String, token: RpcToken, limits: Limits = Limits()): ZIO[Scope, RpcError, RpcConnection] =
    connectUsing(endpoint, limits)(open(endpoint, token, limits))

  private[rpc] def connectUsing(endpoint: String, limits: Limits)(
      socket: ZIO[Scope, RpcError, RpcSocket]
  ): ZIO[Scope, RpcError, RpcConnection] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit)).flatMap { scope =>
      (for {
        uri <- ZIO.attempt(URI.create(endpoint)).mapError(_ => RpcError(RpcError.Code.ProtocolError))
        _ <- ZIO
          .fail(RpcError(RpcError.Code.ProtocolError))
          .unless(
            Set("ws", "wss").contains(uri.getScheme) && uri.getHost != null && uri.getUserInfo == null &&
              uri.getFragment == null && uri.getQuery == null
          )
        transport <- socket.timeoutFail(RpcError(RpcError.Code.TimedOut))(limits.timeout)
        connection <- session(transport, limits)
      } yield connection)
        .provideEnvironment(ZEnvironment(scope))
        .onError(_ => scope.close(Exit.unit))
        .map { connection =>
          new RpcConnection {
            def call(serviceId: String, version: Int, method: String, arguments: Chunk[Json]): IO[RpcError, RpcResult] =
              connection.call(serviceId, version, method, arguments)
            override def stream(
                serviceId: String,
                version: Int,
                method: String,
                arguments: Chunk[Json],
                binary: Boolean
            ): ZStream[Any, RpcStreamError, RpcStreamChunk] =
              connection.stream(serviceId, version, method, arguments, binary)
            def close: UIO[Unit] = scope.close(Exit.unit).uninterruptible
          }
        }
    }

  private def size(text: String): Int = text.getBytes(StandardCharsets.UTF_8).length
  private def traceMessage(event: String, connectionId: java.util.UUID, payload: => String): UIO[Unit] =
    ZIO.logTrace(s"RpcWebSocket client RPC $event connectionId=$connectionId payload=${payload.toJson}")
  private def errorCode(error: Throwable): RpcError.Code = error match {
    case error: RpcError => error.code
    case _               => RpcError.Code.ServiceFailure
  }
  private final case class State(
      nextId: Long = 1,
      pending: Map[String, Promise[RpcError, RpcResult]] = Map.empty,
      closed: Boolean = false
  )
  private def disconnect(state: Ref[State], error: RpcError): UIO[Unit] =
    state
      .modify(current => (current.pending.values.toList, current.copy(pending = Map.empty, closed = true)))
      .flatMap(pending => ZIO.foreachDiscard(pending)(_.fail(error)))
      .uninterruptible

  private def session(socket: RpcSocket, limits: Limits): ZIO[Scope, RpcError, RpcConnection] =
    for {
      state <- Ref.make(State())
      connectionId <- Random.nextUUID
      writes <- Semaphore.make(1)
      streams <- RpcClientStreams.make(
        limits,
        state
          .modify { current =>
            if current.closed then (Left(RpcError(RpcError.Code.Disconnected)), current)
            else if current.nextId == Long.MaxValue then (Left(RpcError(RpcError.Code.LimitExceeded)), current)
            else (Right(current.nextId.toString), current.copy(nextId = current.nextId + 1))
          }
          .flatMap(ZIO.fromEither),
        writes,
        output =>
          ZIO.fail(RpcError(RpcError.Code.LimitExceeded)).when(size(output) > limits.maxFrameBytes) *>
            socket.sendText(output) *> traceMessage("stream control sent", connectionId, output),
        error => disconnect(state, error) *> socket.close
      )
      disconnectAll = (error: RpcError) => disconnect(state, error) *> streams.disconnect(error)
      _ <- ZIO.addFinalizer(disconnectAll(RpcError(RpcError.Code.Disconnected)) *> socket.close)
      receive = socket.receive.flatMap { message =>
        (message match {
          case RpcSocket.Message.Binary(bytes) =>
            for {
              _ <- ZIO.fail(RpcError(RpcError.Code.LimitExceeded)).when(bytes.size > limits.maxFrameBytes)
              reply <- ZIO.fromEither(RpcWire.binaryReply(bytes))
              _ <- streams.receiveBytes(reply._1, reply._2)
            } yield ()
          case RpcSocket.Message.Text(input) =>
            for {
              _ <- ZIO.fail(RpcError(RpcError.Code.LimitExceeded)).when(size(input) > limits.maxFrameBytes)
              _ <- traceMessage("response received", connectionId, input)
              isStream <- ZIO.fromEither(RpcWire.isStream(input))
              _ <-
                if isStream then
                  ZIO.fromEither(RpcWire.streamReply(input)).flatMap { (id, reply) =>
                    state.get.flatMap(current =>
                      ZIO
                        .fail(RpcError(RpcError.Code.ProtocolError))
                        .when(current.pending.contains(id) || id.toLong >= current.nextId)
                    ) *> streams.receive(id, reply)
                  }
                else
                  for {
                    response <- ZIO.fromEither(RpcWire.response(input))
                    collision <- streams.contains(response.id)
                    _ <- ZIO.fail(RpcError(RpcError.Code.ProtocolError)).when(collision)
                    promise <- state.modify(current =>
                      (current.pending.get(response.id), current.copy(pending = current.pending - response.id))
                    )
                    _ <- ZIO.foreachDiscard(promise)(_.done(Exit.fromEither(response.outcome)))
                  } yield ()
            } yield ()
        }).uninterruptible
      }
      _ <- receive.forever
        .catchAll(error => disconnectAll(error) *> socket.close)
        .ensuring(disconnectAll(RpcError(RpcError.Code.Disconnected)) *> socket.close)
        .forkScoped
      _ <- ZIO.logInfo(s"RpcWebSocket client connected connectionId=$connectionId")
      _ <- ZIO.addFinalizer(ZIO.logInfo(s"RpcWebSocket client disconnected connectionId=$connectionId"))
      connection = new RpcConnection {
        override def stream(
            serviceId: String,
            version: Int,
            method: String,
            arguments: Chunk[Json],
            binary: Boolean
        ): ZStream[Any, RpcStreamError, RpcStreamChunk] = streams.stream(serviceId, version, method, arguments, binary)
        override def close: UIO[Unit] =
          (disconnectAll(RpcError(RpcError.Code.Disconnected)) *> socket.close).uninterruptible
        override def call(
            serviceId: String,
            version: Int,
            method: String,
            arguments: Chunk[Json]
        ): IO[RpcError, RpcResult] =
          ZIO.uninterruptibleMask { restore =>
            for {
              promise <- Promise.make[RpcError, RpcResult]
              id <- state
                .modify { current =>
                  if current.closed then (Left(RpcError(RpcError.Code.Disconnected)), current)
                  else if current.pending.size >= limits.maxPending || current.nextId == Long.MaxValue then
                    (Left(RpcError(RpcError.Code.LimitExceeded)), current)
                  else {
                    val id = current.nextId.toString
                    (
                      Right(id),
                      current.copy(nextId = current.nextId + 1, pending = current.pending.updated(id, promise))
                    )
                  }
                }
                .flatMap(ZIO.fromEither)
                .tapError(error =>
                  ZIO.logTrace(
                    s"RpcWebSocket client RPC call rejected connectionId=$connectionId service=${serviceId.toJson} method=${method.toJson} code=${error.code}"
                  )
                )
              value <- restore {
                (for {
                  output <- ZIO
                    .attempt(RpcWire.Request(id, serviceId, version, method, arguments).toJson)
                    .mapError(_ => RpcError(RpcError.Code.ProtocolError))
                  _ <- ZIO.fail(RpcError(RpcError.Code.LimitExceeded)).when(size(output) > limits.maxFrameBytes)
                  _ <- writes
                    .withPermit(
                      socket.sendText(output) *>
                        traceMessage("request sent", connectionId, output)
                    )
                    .tapError(error => disconnectAll(error) *> socket.close)
                  result <- promise.await
                } yield result).timeoutFail(RpcError(RpcError.Code.TimedOut))(limits.timeout)
              }
                .tapError(error =>
                  ZIO.logTrace(
                    s"RpcWebSocket client RPC call failed connectionId=$connectionId requestId=$id code=${errorCode(error)}"
                  )
                )
                .onInterrupt(
                  ZIO.logTrace(s"RpcWebSocket client RPC call interrupted connectionId=$connectionId requestId=$id")
                )
                .ensuring(state.update(current => current.copy(pending = current.pending - id)))
            } yield value
          }
      }
    } yield connection
}

private[rpc] trait RpcSocket {
  def sendText(text: String): IO[RpcError, Unit]
  def receive: IO[RpcError, RpcSocket.Message]
  def close: UIO[Unit]
}
private[rpc] object RpcSocket {
  enum Message {
    case Text(value: String)
    case Binary(value: Chunk[Byte])
  }
  def closeError(status: Int): RpcError = RpcError(status match {
    case 1002 | 1003 | 1007 => RpcError.Code.ProtocolError
    case 1009 | 1013        => RpcError.Code.LimitExceeded
    case _                  => RpcError.Code.Disconnected
  })
}
