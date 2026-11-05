package fmgp.yallmo.server

import fmgp.yallmo.model.{AgentId, BlobId, BlobUploadFailure, BlobUploadLimits, YallmoError}
import fmgp.yallmo.orchestration.{BlobUploader, OrchestratorLive}
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
    for {
      state <- Ref.Synchronized.make[ConnectionState](ConnectionState.Uninitialized)
      uploads <- Ref.make(Map.empty[BlobId, BlobUploader])
    } yield new Connection(UUID.randomUUID(), state, uploads)

  def disconnect(connection: Connection): UIO[Unit] =
    connection.state.modifyZIO { _ =>
      ZIO
        .succeed(humans.remove(connection.id))
        .flatMap { removed =>
          ZIO.foreachDiscard(removed)(id => ZIO.logInfo(s"Viewer identity removed userId=${escapedHumanId(id)}"))
        }
        .as(((), ConnectionState.Closed))
        .tap(_ =>
          connection.uploads
            .getAndSet(Map.empty)
            .flatMap(uploads => ZIO.foreachDiscard(uploads.values)(_.abort.ignore)) *>
            ZIO.foreachDiscard(configured)(_.providers.disconnect(connection.id))
        )
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
        ZIO.logAnnotate("rpcMethod", method.getOrElse("").take(80)) {
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
                  methods ++ List(
                    SessionsCreate.method,
                    TurnSubmit.method,
                    DiagnosticsRequest.method,
                    BlobsUpload.method,
                    AgentsInvoke.method,
                    ProvidersList.method,
                    ProvidersLoginStart.method,
                    ProvidersLoginComplete.method,
                    ProvidersLoginCancel.method
                  )
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
              session <- ZIO.logAnnotate("sessionId", request.sessionId.value)(
                domain(master.loadSession(request.sessionId))
              )
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
          case BlobsUpload.method =>
            // Serialize admission with disconnect, not with long-running turns or blob waiters.
            connection.state.modifyZIO { current =>
              (operator(current) *> upload(params, connection)).map(result => (result, current))
            }.uninterruptible
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
              outcome <- ZIO.logAnnotate("sessionId", request.sessionId.value) {
                ZIO.logAnnotate("agentId", AgentId.value(request.agentId)) {
                  configured.get.submit(
                    author,
                    request.sessionId,
                    request.agentId,
                    request.task.prompt,
                    request.expectedHead,
                    request.resetContinuation
                  )
                }
              }
              result <- encode(outcome)
            } yield result
          case AgentsInvoke.method if configured.nonEmpty =>
            for {
              author <- operator(state)
              request <- decode[AgentsInvoke.Params](params)
              response <- configured.get.invoke(author, request.agentId, request.input)
              result <- encode(response)
            } yield result
          case ProvidersList.method if configured.nonEmpty =>
            operator(state) *> decode[Empty](params) *> configured.get.providers.list.flatMap(encode(_))
          case ProvidersLoginStart.method if configured.nonEmpty =>
            // Start admission shares the connection lock with disconnect, so no pending secret can outlive closure.
            connection.state.modifyZIO { current =>
              (for {
                _ <- operator(current)
                request <- decode[ProvidersLoginStart.Params](params)
                _ <- ZIO.fail(JsonRpc.Error.InvalidParams).unless(request.providerId.length <= 1024)
                started <- configured.get.providers.start(connection.id, request.providerId)
                result <- encode(started)
              } yield (result, current))
            }
          case ProvidersLoginComplete.method if configured.nonEmpty =>
            for {
              _ <- operator(state)
              request <- decode[ProvidersLoginComplete.Params](params)
              completed <- configured.get.providers.complete(connection.id, request)
              result <- encode(completed)
            } yield result
          case ProvidersLoginCancel.method if configured.nonEmpty =>
            for {
              _ <- operator(state)
              request <- decode[ProvidersLoginCancel.Params](params)
              _ <- ZIO.fail(JsonRpc.Error.InvalidParams).unless(request.attemptId.length <= 64)
              cancelled <- configured.get.providers.cancel(connection.id, request.attemptId)
              result <- encode(cancelled)
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

  private def upload(params: Json.Obj, connection: Connection): IO[JsonRpc.Error, Json] =
    for {
      command <- decode[BlobsUpload.Params](params)
      result <- command match {
        case BlobsUpload.Params.Begin(blobId, total) =>
          domain(for {
            uploader <- master.beginBlobUpload(blobId, total)
            _ <- connection.uploads.update(_.updated(blobId, uploader))
            // Drop retained capabilities after the observed outcome or bounded dependency wait, even without traffic.
            // Blocked uninterruptible publication can delay this lookup and expiry beyond MaximumLifetime.
            // Identity protects a replacement Begin.
            _ <- (master.readBlob(blobId, BlobUploadLimits.AwaitTimeout).ignore *>
              forgetUpload(connection, uploader)).interruptible.forkDaemon
          } yield BlobsUpload.Result.Begun(uploader.blobId))
        case BlobsUpload.Params.Append(id, offset, encoded) =>
          for {
            _ <- ZIO.fail(JsonRpc.Error.InvalidParams).unless(encoded.length <= BlobsUpload.MaxEncodedChunkChars)
            bytes <- ZIO.attempt(Base64.getDecoder.decode(encoded)).mapError(_ => JsonRpc.Error.InvalidParams)
            _ <- ZIO
              .fail(JsonRpc.Error.InvalidParams)
              .unless(
                bytes.nonEmpty && bytes.length <= MaxBlobChunkBytes && Base64.getEncoder
                  .encodeToString(bytes) == encoded
              )
            uploader <- ownedUpload(connection, id)
            next <- domain(uploader.append(offset, Chunk.fromArray(bytes)))
          } yield BlobsUpload.Result.Appended(id, next)
        case BlobsUpload.Params.Complete(id) =>
          ownedUpload(connection, id)
            .flatMap { uploader =>
              domain(uploader.complete).tap(_ => forgetUpload(connection, uploader))
            }
            .map(BlobsUpload.Result.Completed(_))
        case BlobsUpload.Params.Abort(id) =>
          ownedUpload(connection, id)
            .flatMap { uploader =>
              domain(uploader.abort).tap(_ => forgetUpload(connection, uploader))
            }
            .as(BlobsUpload.Result.Aborted(id))
      }
      json <- encode[BlobsUpload.Result](result)
    } yield json

  private def forgetUpload(connection: Connection, uploader: BlobUploader): UIO[Unit] =
    connection.uploads.update { current =>
      if current.get(uploader.blobId).exists(_ eq uploader) then current - uploader.blobId else current
    }

  private def ownedUpload(connection: Connection, id: BlobId): IO[JsonRpc.Error, BlobUploader] =
    domain(
      connection.uploads.get.flatMap(current =>
        ZIO
          .fromOption(current.get(id))
          .orElseFail(YallmoError.BlobUploadFailed(BlobUploadFailure.Unknown))
      )
    )

  private def decode[A: JsonDecoder](params: Json): IO[JsonRpc.Error, A] =
    ZIO.fromEither(params.as[A]).mapError(_ => JsonRpc.Error.InvalidParams)

  private def encode[A: JsonEncoder](value: A): IO[JsonRpc.Error, Json] =
    ZIO
      .fromEither(value.toJsonAST)
      .tapError(details => ZIO.logError(s"RPC response encoding failed: $details"))
      .mapError(_ => JsonRpc.Error.InternalError)

  private def domain[A](effect: IO[YallmoError, A]): IO[JsonRpc.Error, A] =
    effect.catchAll { error =>
      val response = storageError(error)
      ZIO
        .logErrorCause("RPC domain operation failed", Cause.fail(error))
        .when(response == JsonRpc.Error.InternalError) *> ZIO.fail(response)
    }

  private def storageError(error: YallmoError): JsonRpc.Error = error match {
    case YallmoError.StorageFailed(error)                            => storageError(error)
    case _: YallmoError.SessionNotFound | _: YallmoError.BlobMissing =>
      JsonRpc.Error(ErrorCode.NotFound, "Not found")
    case _: YallmoError.BlobAlreadyExists     => JsonRpc.Error(ErrorCode.Conflict, "Blob already exists")
    case YallmoError.BlobUploadFailed(reason) =>
      reason match {
        case BlobUploadFailure.Capacity => JsonRpc.Error(ErrorCode.Busy, reason.message)
        case BlobUploadFailure.Unknown  => JsonRpc.Error(ErrorCode.NotFound, reason.message)
        case BlobUploadFailure.Storage  => JsonRpc.Error.InternalError
        case _                          => JsonRpc.Error(ErrorCode.Rejected, reason.message)
      }
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
      private[OrchestratorRpc] val state: Ref.Synchronized[ConnectionState],
      private[server] val uploads: Ref[Map[BlobId, BlobUploader]]
  )
}
