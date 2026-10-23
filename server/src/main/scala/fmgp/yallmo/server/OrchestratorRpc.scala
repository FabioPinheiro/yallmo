package fmgp.yallmo.server

import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.orchestration.OrchestratorLive
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.server.protocol.YallmoProtocol.given
import java.util.{Base64, UUID}
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Small shared read-only method handler. It owns neither transports nor an additional runtime/storage service.
  * Initialization state belongs to each connection; a future Unix adapter can use the same handler unchanged.
  */
private[server] final class OrchestratorRpc(master: OrchestratorLive[?]) {
  private val instanceId = UUID.randomUUID().toString
  private val methods = List(
    Initialize.method,
    SessionsList.method,
    SessionsGet.method,
    MessagesList.method,
    AgentsList.method,
    BlobsList.method,
    BlobsRead.method
  )

  def handle(input: String, initialized: Ref[Boolean]): UIO[Option[String]] =
    RpcWire.parse(input) match {
      case Left(_) => ZIO.some(JsonRpc.Failure(Json.Null, JsonRpc.Error.ParseError).toJson)
      case Right(Json.Arr(values)) if values.isEmpty || values.size > 64 =>
        ZIO.some(JsonRpc.Failure(Json.Null, JsonRpc.Error.InvalidRequest).toJson)
      case Right(Json.Arr(values)) =>
        ZIO.foreach(values)(handleOne(_, initialized)).map { replies =>
          val responses = replies.flatten
          Option.when(responses.nonEmpty)(responses.mkString("[", ",", "]"))
        }
      case Right(value) => handleOne(value, initialized)
    }

  private def handleOne(value: Json, initialized: Ref[Boolean]): UIO[Option[String]] = value match {
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
          case params: Json.Obj => invoke(method.getOrElse(""), params, initialized)
          case _                => ZIO.fail(JsonRpc.Error.InvalidParams)
        }
        result.either
          .catchAllCause { cause =>
            if cause.isInterrupted then ZIO.refailCause(cause)
            else ZIO.logErrorCause("Read-only RPC failed", cause).as(Left(JsonRpc.Error.InternalError))
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

  private def invoke(method: String, params: Json.Obj, initialized: Ref[Boolean]): IO[JsonRpc.Error, Json] =
    if method == Initialize.method then
      for {
        request <- decode[Initialize.Params](params)
        _ <- ZIO
          .fail(JsonRpc.Error(ErrorCode.UnsupportedVersion, "Unsupported protocol version"))
          .unless(request.protocolVersion == Version)
        _ <- initialized.set(true)
        result <- encode(
          Initialize.Result(
            Version,
            instanceId,
            Identity(AgentId("local-viewer"), Role.Viewer),
            methods,
            Nil,
            MaxBlobChunkBytes
          )
        )
      } yield result
    else
      for {
        ready <- initialized.get
        _ <- ZIO.fail(JsonRpc.Error(ErrorCode.Unauthorized, "Initialize required")).unless(ready)
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
            decode[Empty](params) *> domain(master.listAgentIds).flatMap(values => encode(values.sortBy(AgentId.value)))
          case BlobsList.method =>
            decode[Empty](params) *> domain(master.listBlobIds).flatMap(values => encode(values.sortBy(_.value)))
          case BlobsRead.method => readBlob(params)
          case _                => ZIO.fail(JsonRpc.Error.MethodNotFound)
        }
      } yield result

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
