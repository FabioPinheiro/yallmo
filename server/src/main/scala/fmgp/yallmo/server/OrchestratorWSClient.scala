package fmgp.yallmo.server

import fmgp.yallmo.model.{AgentId, BlobId}
import fmgp.yallmo.orchestration.{SessionId, SessionSummary}
import fmgp.yallmo.server.OrchestratorClient.Error
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.server.protocol.YallmoProtocol.given
import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.concurrent.{ArrayBlockingQueue, CompletionStage, ThreadPoolExecutor, TimeUnit}
import scala.jdk.FutureConverters.*
import scala.util.Try
import zio.*
import zio.json.*
import zio.json.ast.Json

/** JVM WebSocket client. Use connect inside a Scope; no provider/tool/storage services are constructed here. One
  * connection correlates concurrent requests. There is no automatic reconnect or request replay.
  */
final class OrchestratorWSClient private (
    val endpoint: String,
    channel: WebSocket,
    state: Ref[OrchestratorWSClient.State],
    writes: Semaphore,
    timeout: Duration,
    userId: Option[AgentId],
    negotiated: Ref[Option[Initialize.Result]]
) extends OrchestratorClient {
  import OrchestratorWSClient.*

  override def initialize(clientName: String): IO[Error, Initialize.Result] =
    for {
      result <- request[Initialize.Params, Initialize.Result](
        Initialize.method,
        Initialize.Params(Version, clientName, userId)
      )
      _ <- ZIO
        .fail(Error.InvalidResponse)
        .unless(
          result.protocolVersion == Version && result.maxBlobChunkBytes > 0 && result.maxBlobChunkBytes <= MaxBlobChunkBytes &&
            userId.fold(result.identity.role == Role.Viewer)(id =>
              result.identity.id == id && (result.identity.role == Role.Viewer ||
                (result.identity.role == Role.Operator &&
                  result.methods.contains(SessionsCreate.method) && result.methods.contains(TurnSubmit.method)))
            )
        )
      _ <- negotiated.set(Some(result))
    } yield result
  override def capabilities: UIO[Option[Initialize.Result]] = negotiated.get
  override def createSession(name: Option[SessionId]): IO[Error, SessionSnapshot] =
    request(SessionsCreate.method, SessionsCreate.Params(name))
  override def submitTurn(
      sessionId: SessionId,
      agentId: AgentId,
      task: fmgp.yallmo.orchestration.AgentTask,
      expectedHead: Option[fmgp.yallmo.model.MessageId],
      resetContinuation: Boolean
  ): IO[Error, TurnOutcome] =
    request(TurnSubmit.method, TurnSubmit.Params(sessionId, agentId, task, expectedHead, resetContinuation))

  override def providerRequest(sessionId: SessionId): IO[Error, DiagnosticsRequest.Result] =
    request(DiagnosticsRequest.method, DiagnosticsRequest.Params(sessionId))
  override def listSessions: IO[Error, Chunk[SessionSummary]] = request(SessionsList.method, Empty())
  override def loadSession(id: SessionId): IO[Error, SessionSnapshot] =
    request(SessionsGet.method, SessionsGet.Params(id))
  override def listMessages: IO[Error, Chunk[MessageSnapshot]] = request(MessagesList.method, Empty())
  override def listAgentIds: IO[Error, Chunk[AgentId]] = request(AgentsList.method, Empty())
  override def listBlobIds: IO[Error, Chunk[BlobId]] = request(BlobsList.method, Empty())
  override def readBlob(id: BlobId, offset: Long, maxBytes: Int): IO[Error, BlobsRead.Result] =
    request(BlobsRead.method, BlobsRead.Params(id, offset, maxBytes))

  override def close: UIO[Unit] =
    disconnect(state, Error.Disconnected) *>
      writes
        .withPermit(ZIO.fromCompletionStage(channel.sendClose(WebSocket.NORMAL_CLOSURE, "")))
        .timeout(1.second)
        .ignore *> ZIO.succeed(channel.abort())

  private def request[P: JsonCodec, A: JsonDecoder](method: String, params: P): IO[Error, A] =
    ZIO.uninterruptibleMask { restore =>
      for {
        promise <- Promise.make[Error, Json]
        id <- state
          .modify { current =>
            if current.closed then (Left(Error.Disconnected), current)
            else if current.pending.size >= MaxPendingRequests then (Left(Error.TooManyPendingRequests), current)
            else {
              val id = current.nextId.toString
              (Right(id), current.copy(nextId = current.nextId + 1, pending = current.pending.updated(id, promise)))
            }
          }
          .flatMap(ZIO.fromEither)
        result <- restore {
          for {
            output <- ZIO.attempt(JsonRpc.Request(Json.Str(id), method, params).toJson).mapError(Error.Transport.apply)
            _ <- ZIO.fail(Error.InvalidResponse).when(RpcWire.size(output) > RpcWire.MaxMessageBytes)
            wait =
              for {
                _ <- writes
                  .withPermit(ZIO.fromCompletionStage(channel.sendText(output, true)))
                  .mapError(Error.Transport.apply)
                  .tapError(_ => disconnect(state, Error.Disconnected))
                value <- promise.await
              } yield value
            value <- if method == TurnSubmit.method then wait else wait.timeoutFail(Error.RequestTimedOut)(timeout)
            decoded <- ZIO.fromEither(value.as[A]).mapError(_ => Error.InvalidResponse)
          } yield decoded
        }.ensuring(state.update(current => current.copy(pending = current.pending - id)))
      } yield result
    }
}

object OrchestratorWSClient {
  private val MaxPendingRequests = 128
  private final case class State(
      nextId: Long = 1L,
      pending: Map[String, Promise[Error, Json]] = Map.empty,
      closed: Boolean = false
  )

  /** Opens and initializes one connection. Scope exit closes it and fails all pending requests. A deadline abandons the
    * local wait only; it never cancels server execution. Turn waits have no read deadline. userId is an impersonable
    * local claim, not authentication; configured loopback non-browser hosts may grant operator capabilities.
    */
  def connect(
      endpoint: String,
      requestTimeout: Duration = 15.seconds,
      userId: Option[AgentId] = None,
      clientName: String = "yallmo-jvm"
  ): ZIO[Scope, Error, OrchestratorWSClient] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit)).flatMap { scope =>
      connectInScope(endpoint, requestTimeout, userId, clientName)
        .provideEnvironment(ZEnvironment(scope))
        .onError(_ => scope.close(Exit.unit))
    }

  private def connectInScope(
      endpoint: String,
      requestTimeout: Duration,
      userId: Option[AgentId],
      clientName: String
  ): ZIO[Scope, Error, OrchestratorWSClient] =
    for {
      _ <- ZIO.fail(Error.InvalidEndpoint).unless(validEndpoint(endpoint) && requestTimeout > Duration.Zero)
      state <- Ref.make(State())
      writes <- Semaphore.make(1)
      runtime <- ZIO.runtime[Any]
      // One demanded callback at a time, and one bounded message under assembly. No callback queue or detached work.
      partial <- Ref.make("")
      listener = new WebSocket.Listener {
        private def callback(socket: WebSocket)(effect: UIO[Unit]): CompletionStage[Unit] =
          Unsafe.unsafe { unsafe ?=>
            runtime.unsafe.runToFuture(effect *> ZIO.succeed(socket.request(1)).unit).asJava
          }
        private def reject(socket: WebSocket, code: Int): UIO[Unit] =
          disconnect(state, Error.InvalidResponse) *>
            writes
              .withPermit(ZIO.fromCompletionStage(socket.sendClose(code, "Invalid response")))
              .timeout(1.second)
              .ignore *> ZIO.succeed(socket.abort())

        override def onOpen(socket: WebSocket): Unit = { socket.request(1); () }
        override def onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] = {
          val fragment = data.toString
          callback(socket) {
            partial
              .modify { previous =>
                // UTF-16 units are bounded before concatenation; strict UTF-8 byte limit is checked at message end.
                if previous.length.toLong + fragment.length > RpcWire.MaxMessageBytes then (None, "")
                else {
                  val combined = previous + fragment
                  (Some(combined), if last then "" else combined)
                }
              }
              .flatMap {
                case None                => reject(socket, 1009)
                case Some(input) if last =>
                  ZIO
                    .attempt {
                      StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(input))
                        .remaining()
                    }
                    .foldZIO(
                      _ => reject(socket, 1007),
                      bytes =>
                        if bytes > RpcWire.MaxMessageBytes then reject(socket, 1009)
                        else receive(input, state, socket)
                    )
                case _ => ZIO.unit
              }
          }
        }
        override def onBinary(socket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage[?] = {
          val _ = (data, last)
          callback(socket)(reject(socket, 1003))
        }
        override def onPing(socket: WebSocket, message: ByteBuffer): CompletionStage[?] =
          callback(socket)(
            writes
              .withPermit(ZIO.fromCompletionStage(socket.sendPong(message)))
              .unit
              .catchAll(_ => disconnect(state, Error.Disconnected) *> ZIO.succeed(socket.abort()))
          )
        override def onPong(socket: WebSocket, message: ByteBuffer): CompletionStage[?] = {
          val _ = message
          callback(socket)(ZIO.unit)
        }
        override def onClose(socket: WebSocket, code: Int, reason: String): CompletionStage[?] = {
          val _ = (code, reason)
          callback(socket)(disconnect(state, Error.Disconnected) *> ZIO.succeed(socket.abort()))
        }
        override def onError(socket: WebSocket, error: Throwable): Unit = {
          val _ = callback(socket)(disconnect(state, Error.Transport(error)) *> ZIO.succeed(socket.abort()))
        }
      }
      executor <- ZIO
        .acquireRelease(
          ZIO.attempt(new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue[Runnable](256)))
        )(executor => ZIO.succeed(executor.shutdownNow()).unit)
        .mapError(Error.Transport.apply)
      http <- ZIO
        .acquireRelease(ZIO.attempt(HttpClient.newBuilder().executor(executor).build()))(http =>
          ZIO.succeed(http.shutdownNow())
        )
        .mapError(Error.Transport.apply)
      _ <- ZIO.addFinalizer(disconnect(state, Error.Disconnected))
      // The JDK transport deliberately sends no Origin: browser Origin policy remains entirely server-owned.
      channel <- ZIO.acquireRelease(
        ZIO
          .fromCompletionStage(http.newWebSocketBuilder().buildAsync(URI.create(endpoint), listener))
          .mapError(Error.Transport.apply)
          .timeoutFail(Error.RequestTimedOut)(requestTimeout)
      )(socket => ZIO.succeed(socket.abort()))
      negotiated <- Ref.make(Option.empty[Initialize.Result])
      client = new OrchestratorWSClient(endpoint, channel, state, writes, requestTimeout, userId, negotiated)
      _ <- ZIO.addFinalizer(client.close)
      _ <- client.initialize(clientName)
    } yield client

  private def validEndpoint(endpoint: String): Boolean =
    Try(URI.create(endpoint)).toOption.exists(uri =>
      Set("ws", "wss").contains(uri.getScheme) && uri.getHost != null &&
        uri.getUserInfo == null && uri.getFragment == null
    )

  private def disconnect(state: Ref[State], error: Error): UIO[Unit] =
    state
      .modify(current => (current.pending.values.toList, current.copy(pending = Map.empty, closed = true)))
      .flatMap(pending => ZIO.foreachDiscard(pending)(_.fail(error)))

  private def receive(input: String, state: Ref[State], channel: WebSocket): UIO[Unit] = {
    val decoded = for {
      json <- RpcWire.parse(input)
      fields <- json match {
        case Json.Obj(entries) if entries.size == entries.toMap.size => Right(entries.toMap)
        case _                                                       => Left("Expected response object")
      }
      _ <- Either.cond(
        fields.get("jsonrpc").contains(Json.Str(JsonRpc.Version)) && !fields.contains("method"),
        (),
        "Invalid response"
      )
      id <- fields.get("id").collect { case Json.Str(value) => value }.toRight("Expected string response ID")
      outcome <- (fields.get("result"), fields.get("error")) match {
        case (Some(result), None) => Right(Right(result))
        case (None, Some(error))  => error.as[JsonRpc.Error].map(value => Left(Error.Remote(value)))
        case _                    => Left("Expected exactly one result/error")
      }
    } yield (id, outcome)
    decoded match {
      case Left(_)              => disconnect(state, Error.InvalidResponse) *> ZIO.succeed(channel.abort())
      case Right((id, outcome)) =>
        state
          .modify(current => (current.pending.get(id), current.copy(pending = current.pending - id)))
          .flatMap(pending => ZIO.foreachDiscard(pending)(_.done(Exit.fromEither(outcome))))
    }
  }
}
