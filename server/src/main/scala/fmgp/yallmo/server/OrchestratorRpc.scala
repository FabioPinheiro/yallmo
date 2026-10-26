package fmgp.yallmo.server

import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.orchestration.OrchestratorLive
import fmgp.yallmo.server.application.ServerApplication
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.server.protocol.YallmoProtocol.given
import java.util.{Base64, UUID}
import scala.collection.concurrent.TrieMap
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Shared read and configured trusted-client submission handler. It owns neither transports nor an additional
  * runtime/storage service. Initialization state belongs to each connection; a future Unix adapter can use the same
  * handler unchanged.
  */
private[server] final class OrchestratorRpc(
    master: OrchestratorLive[?],
    configured: Option[ServerApplication.Configured] = None
) {
  import OrchestratorRpc.*

  require(
    configured.forall(backend => backend.assembly.orchestrator eq master),
    "Configured backend must use the supplied master"
  )

  private val instanceId = UUID.randomUUID().toString
  // Presence only: never inserted into the executable runtime registry or storage.
  private val humans = TrieMap.empty[UUID, AgentId]

  def openConnection: UIO[Connection] =
    Ref.Synchronized
      .make[ConnectionState](ConnectionState.Uninitialized)
      .map(new Connection(UUID.randomUUID(), _))

  def disconnect(connection: Connection): UIO[Unit] =
    connection.state.modifyZIO { _ =>
      ZIO
        .succeed(humans.remove(connection.id))
        .flatMap { removed =>
          ZIO.foreachDiscard(removed)(id => ZIO.logInfo(s"Viewer identity removed userId=${escapedHumanId(id)}"))
        }
        .as(((), ConnectionState.Closed))
    }.uninterruptible
  private val methods = List(
    Initialize.method,
    SessionsList.method,
    SessionsGet.method,
    MessagesList.method,
    AgentsList.method,
    BlobsList.method,
    BlobsRead.method
  )

  def handle(input: String, connection: Connection): UIO[Option[String]] =
    RpcWire.parse(input) match {
      case Left(_) => ZIO.some(JsonRpc.Failure(Json.Null, JsonRpc.Error.ParseError).toJson)
      case Right(Json.Arr(values)) if values.isEmpty || values.size > 64 =>
        ZIO.some(JsonRpc.Failure(Json.Null, JsonRpc.Error.InvalidRequest).toJson)
      case Right(Json.Arr(values)) =>
        ZIO.foreach(values)(handleOne(_, connection)).map { replies =>
          val responses = replies.flatten
          Option.when(responses.nonEmpty)(responses.mkString("[", ",", "]"))
        }
      case Right(value) => handleOne(value, connection)
    }

  private def handleOne(value: Json, connection: Connection): UIO[Option[String]] = value match {
    case Json.Obj(entries) =>
      val fields = entries.toMap
      val id = fields.get("id")
      val validId = id.forall {
        case Json.Str(_) | Json.Num(_) | Json.Null => true
        case _                                     => false
      }
      val method = fields.get("method").collect { case Json.Str(value) => value }
      if entries.size != fields.size || !fields.get("jsonrpc").contains(Json.Str(JsonRpc.Version)) ||
        !validId || method.isEmpty || fields.contains("result") || fields.contains("error")
      then invalidRequest
      else {
        val result = fields.getOrElse("params", Json.Obj()) match {
          case params: Json.Obj => invoke(method.getOrElse(""), params, connection)
          case _                => ZIO.fail(JsonRpc.Error.InvalidParams)
        }
        result.either
          .catchAllCause { cause =>
            if cause.isInterrupted then ZIO.refailCause(cause)
            else ZIO.logErrorCause("RPC failed", cause).as(Left(JsonRpc.Error.InternalError))
          }
          .map { outcome =>
            id.map { requestId =>
              outcome match {
                case Right(result) => JsonRpc.Success(requestId, result).toJson
                case Left(error)   => JsonRpc.Failure(requestId, error).toJson
              }
            }
          }
      }
    case _ => invalidRequest
  }

  private def invalidRequest: UIO[Option[String]] =
    ZIO.some(JsonRpc.Failure(Json.Null, JsonRpc.Error.InvalidRequest).toJson)

  private def invoke(method: String, params: Json.Obj, connection: Connection): IO[JsonRpc.Error, Json] =
    if method == Initialize.method then
      decode[Initialize.Params](params).flatMap { request =>
        connection.state.modifyZIO { current =>
          for {
            _ <- ZIO
              .fail(JsonRpc.Error(ErrorCode.UnsupportedVersion, "Unsupported protocol version"))
              .unless(request.protocolVersion == Version)
            _ <- current match {
              case ConnectionState.Closed => ZIO.fail(JsonRpc.Error(ErrorCode.Unauthorized, "Connection closed"))
              case ConnectionState.Initialized(id) if id != request.userId =>
                ZIO.fail(JsonRpc.Error(ErrorCode.Conflict, "Initialized identity cannot change"))
              case _ => ZIO.unit
            }
            registered <- domain(master.listAgentIds)
            knownUser <- request.userId.fold[UIO[Boolean]](ZIO.succeed(false)) { id =>
              configured.fold[UIO[Boolean]](ZIO.succeed(false))(_.runtime.isUser(id))
            }
            _ <- ZIO
              .fail(JsonRpc.Error(ErrorCode.Conflict, "Human identity conflicts with a runtime agent"))
              .when(request.userId.exists(registered.contains) && !knownUser)
            result <- encode(
              Initialize.Result(
                Version,
                instanceId,
                Identity(
                  request.userId.getOrElse(AgentId("local-viewer")),
                  if permitted(request.userId) then Role.Operator else Role.Viewer
                ),
                if permitted(request.userId) then
                  methods ++ List(SessionsCreate.method, TurnSubmit.method, DiagnosticsRequest.method)
                else methods,
                Nil,
                MaxBlobChunkBytes,
                configured.map(value => Chunk.fromIterable(value.runtime.agentSummaries.map(_.id)))
              )
            )
            _ <- ZIO.when(current == ConnectionState.Uninitialized) {
              ZIO.foreachDiscard(request.userId) { id =>
                ZIO.succeed(humans.put(connection.id, id)) *>
                  ZIO.logInfo(s"Viewer identity registered userId=${escapedHumanId(id)}")
              }
            }
          } yield (result, ConnectionState.Initialized(request.userId))
        }.uninterruptible
      }
    else
      for {
        state <- connection.state.get
        _ <- ZIO
          .fail(JsonRpc.Error(ErrorCode.Unauthorized, "Initialize required"))
          .unless(state.isInstanceOf[ConnectionState.Initialized])
        result <- method match {
          case SessionsList.method =>
            decode[Empty](params) *> domain(master.listSessions).flatMap(values => encode(values.sortBy(_.id.value)))
          case SessionsGet.method =>
            for {
              request <- decode[SessionsGet.Params](params)
              session <- domain(master.loadSession(request.sessionId))
              result <- encode(SessionSnapshot.from(session))
            } yield result
          case MessagesList.method =>
            decode[Empty](params) *> domain(master.listMessages).flatMap(values =>
              encode(values.map(MessageSnapshot.from))
            )
          case AgentsList.method =>
            decode[Empty](params) *> domain(master.listAgentIds).flatMap { values =>
              encode((values ++ Chunk.fromIterable(humans.values)).distinct.sortBy(AgentId.value))
            }
          case BlobsList.method =>
            decode[Empty](params) *> domain(master.listBlobIds).flatMap(values => encode(values.sortBy(_.value)))
          case BlobsRead.method                             => readBlob(params)
          case SessionsCreate.method if configured.nonEmpty =>
            for {
              author <- operator(state)
              request <- decode[SessionsCreate.Params](params)
              result <- configured.get.create(author, request.name).flatMap(encode(_))
            } yield result
          case TurnSubmit.method if configured.nonEmpty =>
            for {
              author <- operator(state)
              request <- decode[TurnSubmit.Params](params)
              outcome <- configured.get.submit(
                author,
                request.sessionId,
                request.agentId,
                request.task.prompt,
                request.expectedHead,
                request.resetContinuation
              )
              result <- encode(outcome)
            } yield result
          case DiagnosticsRequest.method if configured.nonEmpty =>
            for {
              _ <- operator(state)
              request <- decode[DiagnosticsRequest.Params](params)
              result <- configured.get.providerRequest(request.sessionId).flatMap(encode(_))
            } yield result
          case _ => ZIO.fail(JsonRpc.Error.MethodNotFound)
        }
      } yield result

  private def permitted(human: Option[AgentId]): Boolean =
    configured.nonEmpty && human.exists(id => AgentId.value(id).trim.nonEmpty)

  private def operator(state: ConnectionState): IO[JsonRpc.Error, AgentId] = state match {
    case ConnectionState.Initialized(Some(id)) if permitted(Some(id)) => ZIO.succeed(id)
    case _ => ZIO.fail(JsonRpc.Error(ErrorCode.Forbidden, "Operator permission unavailable for this connection"))
  }

  private def escapedHumanId(id: AgentId): String =
    AgentId.value(id).toJson.flatMap { char =>
      if Character.isISOControl(char) then f"\\u${char.toInt}%04x" else char.toString
    }

  private def readBlob(params: Json.Obj): IO[JsonRpc.Error, Json] =
    for {
      request <- decode[BlobsRead.Params](params)
      _ <- ZIO
        .fail(JsonRpc.Error.InvalidParams)
        .unless(
          request.offset >= 0 && request.maxBytes > 0 && request.maxBytes <= MaxBlobChunkBytes
        )
      blob <- domain(master.readBlob(request.blobId))
      _ <- ZIO.fail(JsonRpc.Error.InvalidParams).unless(request.offset <= blob.bytes.size.toLong)
      end = (request.offset + request.maxBytes).min(blob.bytes.size.toLong).toInt
      bytes = blob.bytes.slice(request.offset.toInt, end)
      result <- encode(
        BlobsRead.Result(
          blob.id,
          request.offset,
          Base64.getEncoder.encodeToString(bytes.toArray),
          blob.bytes.size.toLong,
          end == blob.bytes.size
        )
      )
    } yield result

  private def decode[A: JsonDecoder](params: Json): IO[JsonRpc.Error, A] =
    ZIO.fromEither(params.as[A]).mapError(_ => JsonRpc.Error.InvalidParams)

  private def encode[A: JsonEncoder](value: A): IO[JsonRpc.Error, Json] =
    ZIO.fromEither(value.toJsonAST).mapError(_ => JsonRpc.Error.InternalError)

  private def domain[A](effect: IO[YallmoError, A]): IO[JsonRpc.Error, A] =
    effect.mapError {
      case YallmoError.StorageFailed(error) => storageError(error)
      case error                            => storageError(error)
    }

  private def storageError(error: YallmoError): JsonRpc.Error = error match {
    case YallmoError.StorageFailed(error)                            => storageError(error)
    case _: YallmoError.SessionNotFound | _: YallmoError.BlobMissing =>
      JsonRpc.Error(ErrorCode.NotFound, "Not found")
    case _ => JsonRpc.Error.InternalError
  }
}

private[server] object OrchestratorRpc {
  enum ConnectionState {
    case Uninitialized
    case Initialized(userId: Option[AgentId])
    case Closed
  }

  final class Connection private[OrchestratorRpc] (
      val id: UUID,
      private[OrchestratorRpc] val state: Ref.Synchronized[ConnectionState]
  )
}
