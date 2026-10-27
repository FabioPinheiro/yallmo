package fmgp.yallmo.server

import fmgp.yallmo.model.{AgentId, BlobId}
import fmgp.yallmo.orchestration.{SessionId, SessionSummary}
import fmgp.yallmo.server.OrchestratorClient.Error
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.server.protocol.YallmoProtocol.given
import java.net.URI
import scala.util.Try
import zio.*
import zio.http.{ChannelEvent, Handler, Status, WebSocketChannel, WebSocketFrame}
import zio.json.*
import zio.json.ast.Json

/** JVM WebSocket client. Use connect inside a Scope; no provider/tool/storage services are constructed here. One
  * connection correlates concurrent requests. There is no automatic reconnect or request replay.
  */
final class OrchestratorWSClient private (
    val endpoint: String,
    channel: WebSocketChannel,
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
    ZIO.uninterruptibleMask { restore =>
      disconnect(state, Error.Disconnected).flatMap { wasOpen =>
        ZIO
          .when(wasOpen) {
            restore(writes.withPermit(channel.send(ChannelEvent.Read(WebSocketFrame.Close(1000, None)))))
              .timeout(1.second)
              .ignore
              .ensuring(channel.shutdown *> channel.awaitShutdown)
          }
          .unit
      }
    }

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
                  .withPermit(channel.send(ChannelEvent.Read(WebSocketFrame.Text(output))))
                  .mapError(Error.Transport.apply)
                  .tapError(_ => (disconnect(state, Error.Disconnected) *> channel.shutdown).uninterruptible)
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
      ready <- Promise.make[Error, WebSocketChannel]
      _ <- ZIO.addFinalizer(disconnect(state, Error.Disconnected))
      http <- OrchestratorWSClientTransport.layer.build.map(_.get[zio.http.Client]).mapError(Error.Transport.apply)
      app = Handler
        .webSocket { channel =>
          val reject = (code: Int, reason: String) =>
            disconnect(state, Error.InvalidResponse) *>
              writes
                .withPermit(channel.send(ChannelEvent.Read(WebSocketFrame.Close(code, Some(reason)))))
                .timeout(1.second)
                .ignore *> channel.shutdown
          WebSocketText.make
            .flatMap { text =>
              channel.receiveAll {
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                  ready.succeed(channel).unit
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout) =>
                  ready.fail(Error.RequestTimedOut).unit *> channel.shutdown
                // Netty answers pings (with their original payload) and drops pongs automatically.
                case ChannelEvent.Read(WebSocketFrame.Ping | WebSocketFrame.Pong) => ZIO.unit
                case ChannelEvent.Read(_: WebSocketFrame.Close)                   =>
                  disconnect(state, Error.Disconnected) *> channel.shutdown
                case ChannelEvent.Read(frame) =>
                  text
                    .accept(frame)
                    .foldZIO(
                      failure => reject(failure.code, failure.reason),
                      message => ZIO.foreachDiscard(message)(receive(_, state, channel))
                    )
                case ChannelEvent.ExceptionCaught(error) =>
                  val failure = OrchestratorWSClientTransport.error(error)
                  ready.fail(failure).unit *> disconnect(state, failure) *> channel.shutdown
                case _ => ZIO.unit
              }
            }
            .catchAllCause { cause =>
              val failure = if cause.isInterruptedOnly then Error.Disconnected else Error.Transport(cause.squash)
              ready.fail(failure).unit *> disconnect(state, failure)
            }
            .ensuring(
              ready.fail(Error.Disconnected).unit *> disconnect(state, Error.Disconnected) *> channel.shutdown
            )
        }
        .withConfig(RpcWire.webSocketConfig.handshakeTimeout(Duration.fromMillis(requestTimeout.toMillis.max(1L))))
      channel <- (for {
        response <- http.uri(URI.create(endpoint)).socket(app).mapError(Error.Transport.apply)
        _ <- ZIO.fail(Error.InvalidResponse).unless(response.status == Status.SwitchingProtocols)
        // socket returns the HTTP response before Netty has necessarily validated the upgrade.
        channel <- ready.await
      } yield channel).timeoutFail(Error.RequestTimedOut)(requestTimeout)
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

  private def disconnect(state: Ref[State], error: Error): UIO[Boolean] =
    state
      .modify(current =>
        ((current.pending.values.toList, !current.closed), current.copy(pending = Map.empty, closed = true))
      )
      .flatMap { (pending, wasOpen) => ZIO.foreachDiscard(pending)(_.fail(error)).as(wasOpen) }
      .uninterruptible

  private def receive(input: String, state: Ref[State], channel: WebSocketChannel): UIO[Unit] = {
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
      case Left(_)              => disconnect(state, Error.InvalidResponse) *> channel.shutdown
      case Right((id, outcome)) =>
        state
          .modify(current => (current.pending.get(id), current.copy(pending = current.pending - id)))
          .flatMap(pending => ZIO.foreachDiscard(pending)(_.done(Exit.fromEither(outcome))))
          .uninterruptible
    }
  }
}
