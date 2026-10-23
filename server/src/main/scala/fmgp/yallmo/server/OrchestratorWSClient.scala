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
import zio.http.{ChannelEvent, Client, DnsResolver, Handler, Status, WebSocketChannel, WebSocketFrame}
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
    timeout: Duration
) extends OrchestratorClient {
  import OrchestratorWSClient.*

  override def initialize(clientName: String): IO[Error, Initialize.Result] =
    for {
      result <- request[Initialize.Params, Initialize.Result](Initialize.method, Initialize.Params(Version, clientName))
      _ <- ZIO
        .fail(Error.InvalidResponse)
        .unless(
          result.protocolVersion == Version && result.maxBlobChunkBytes > 0 && result.maxBlobChunkBytes <= MaxBlobChunkBytes
        )
    } yield result
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
        .withPermit(channel.send(ChannelEvent.Read(WebSocketFrame.Close(1000, None))))
        .timeout(1.second)
        .ignore *> channel.shutdown

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
            value <- (for {
              _ <- writes
                .withPermit(channel.send(ChannelEvent.Read(WebSocketFrame.Text(output))))
                .mapError(Error.Transport.apply)
                .tapError(_ => disconnect(state, Error.Disconnected))
              value <- promise.await
            } yield value).timeoutFail(Error.RequestTimedOut)(timeout)
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
    * local wait only; it is never a request to cancel server-side execution.
    */
  def connect(
      endpoint: String,
      requestTimeout: Duration = 15.seconds
  ): ZIO[Scope, Error, OrchestratorWSClient] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit)).flatMap { scope =>
      connectInScope(endpoint, requestTimeout)
        .provideEnvironment(ZEnvironment(scope))
        .onError(_ => scope.close(Exit.unit))
    }

  private def connectInScope(endpoint: String, requestTimeout: Duration): ZIO[Scope, Error, OrchestratorWSClient] =
    for {
      _ <- ZIO.fail(Error.InvalidEndpoint).unless(validEndpoint(endpoint) && requestTimeout > Duration.Zero)
      state <- Ref.make(State())
      ready <- Promise.make[Error, WebSocketChannel]
      writes <- Semaphore.make(1)
      app = Handler
        .webSocket { channel =>
          for {
            text <- WebSocketText.make
            _ <- channel
              .receiveAll {
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                  ready.succeed(channel).unit
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout) =>
                  ready.fail(Error.RequestTimedOut).unit
                case ChannelEvent.Read(WebSocketFrame.Ping) =>
                  writes.withPermit(channel.send(ChannelEvent.Read(WebSocketFrame.Pong)))
                case ChannelEvent.Read(WebSocketFrame.Pong)     => ZIO.unit
                case ChannelEvent.Read(_: WebSocketFrame.Close) => channel.shutdown
                case ChannelEvent.Read(frame)                   =>
                  text
                    .accept(frame)
                    .foldZIO(
                      _ => disconnect(state, Error.InvalidResponse) *> channel.shutdown,
                      value => ZIO.foreachDiscard(value)(receive(_, state, channel))
                    )
                case ChannelEvent.ExceptionCaught(error) =>
                  ready.fail(Error.Transport(error)) *> disconnect(state, Error.Transport(error))
                case ChannelEvent.Unregistered =>
                  ready.fail(Error.Disconnected) *> disconnect(state, Error.Disconnected)
                case _ => ZIO.unit
              }
              .ensuring(disconnect(state, Error.Disconnected) *> channel.shutdown)
          } yield ()
        }
        .withConfig(RpcWire.webSocketConfig)
      environment <- (
        (ZLayer.succeed(Client.Config.default.webSocketConfig(RpcWire.webSocketConfig)) ++
          ZLayer.succeed(zio.http.netty.NettyConfig.defaultWithFastShutdown) ++ DnsResolver.default) >>> Client.live
      ).build.mapError(Error.Transport.apply)
      _ <- ZIO.addFinalizer(disconnect(state, Error.Disconnected))
      response <- app
        .connect(endpoint)
        .provideSomeEnvironment[Scope](_.add(environment.get[Client]))
        .mapError(Error.Transport.apply)
        .timeoutFail(Error.RequestTimedOut)(requestTimeout)
      _ <- ZIO.fail(Error.InvalidResponse).unless(response.status == Status.SwitchingProtocols)
      channel <- ready.await.timeoutFail(Error.RequestTimedOut)(requestTimeout)
      client = new OrchestratorWSClient(endpoint, channel, state, writes, requestTimeout)
      _ <- ZIO.addFinalizer(client.close)
      _ <- client.initialize("yallmo-jvm")
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
    }
  }
}
