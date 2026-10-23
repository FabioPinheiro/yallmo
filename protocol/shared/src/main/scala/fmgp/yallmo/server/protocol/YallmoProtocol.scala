package fmgp.yallmo.server.protocol

import fmgp.yallmo.model.{AgentId, BlobId, Message, MessageId, MessagePayload}
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.orchestration.{AgentResult, AgentTask, AgentToolDefinition, Session, SessionId, SessionSummary}
import java.time.Instant
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** The Yallmo v1 application contract, identical on WebSocket and Unix-domain sockets. Each operation declares its
  * JSON-RPC method, named Params and Result. This defines messages only: no handler, listener or runtime is started.
  * Domain values are reused directly; only messages/sessions need redacted snapshots to exclude private provider
  * continuations. There is no dependency on the legacy viewer contract. See server/PROTOCOL.md.
  */
object YallmoProtocol {
  val Version: Int = 1
  val MaxBlobChunkBytes: Int = 65536

  // Missing domain codecs are derived here, without changing persistence formats or core/model ownership.
  given JsonCodec[SessionId] = JsonCodec.string.transformOrFail(SessionId.from, _.value)
  given JsonCodec[SessionSummary] = DeriveJsonCodec.gen[SessionSummary]
  given JsonCodec[AgentTask] = DeriveJsonCodec.gen[AgentTask]
  given JsonCodec[AgentResult] = DeriveJsonCodec.gen[AgentResult]
  given JsonCodec[JsonSchema] = DeriveJsonCodec.gen[JsonSchema]
  given JsonCodec[AgentToolDefinition] = DeriveJsonCodec.gen[AgentToolDefinition]

  /** Public projection of a domain message: opaque provider continuation data is deliberately not a wire field. */
  final case class MessageSnapshot(
      id: MessageId,
      agentId: AgentId,
      parentId: Option[MessageId],
      timestamp: Instant,
      role: String,
      content: String,
      blobIds: Chunk[BlobId],
      toolName: Option[String] = None
  ) derives JsonCodec

  object MessageSnapshot {
    def from(message: Message): MessageSnapshot =
      MessageSnapshot(
        message.id,
        message.agentId,
        message.parentId,
        message.timestamp,
        message.payload.role,
        message.payload.contentStr,
        message.payload.blobIds,
        message.payload match {
          case MessagePayload.FunctionCallRequest(call)  => Some(call.name)
          case MessagePayload.FunctionCallOutput(output) => Some(output.name)
          case _                                         => None
        }
      )
  }

  /** Session metadata is reused, but messages are public snapshots rather than raw persisted provider payloads. */
  final case class SessionSnapshot(id: SessionId, agentIds: Chunk[AgentId], messages: Chunk[MessageSnapshot])
      derives JsonCodec

  object SessionSnapshot {
    def from(session: Session): SessionSnapshot =
      SessionSnapshot(session.id, session.agentIds, session.messages.map(MessageSnapshot.from))
  }

  final case class Empty() derives JsonCodec

  enum Role derives JsonCodec {
    @jsonHint("viewer") case Viewer
    @jsonHint("operator") case Operator
  }

  final case class Identity(id: AgentId, role: Role) derives JsonCodec
  final case class ApplicationError(code: String, message: String) derives JsonCodec

  object Initialize {
    val method: String = "initialize"
    final case class Params(protocolVersion: Int, clientName: String) derives JsonCodec
    final case class Result(
        protocolVersion: Int,
        serverInstanceId: String,
        identity: Identity,
        methods: List[String],
        notifications: List[String],
        maxBlobChunkBytes: Int
    ) derives JsonCodec
  }

  object SessionsList {
    val method: String = "sessions.list"
    type Params = Empty
    type Result = Chunk[SessionSummary]
  }

  object SessionsGet {
    val method: String = "sessions.get"
    final case class Params(sessionId: SessionId) derives JsonCodec
    type Result = SessionSnapshot
  }

  object SessionsCreate {
    val method: String = "sessions.create"
    final case class Params(name: Option[SessionId] = None) derives JsonCodec
    type Result = SessionSnapshot
  }

  object SessionsFork {
    val method: String = "sessions.fork"

    /** Fork the current head only; name is an ID/name, never a filesystem path. */
    final case class Params(sourceSessionId: SessionId, name: Option[SessionId] = None) derives JsonCodec
    type Result = SessionSnapshot
  }

  object MessagesList {
    val method: String = "messages.list"
    type Params = Empty
    type Result = Chunk[MessageSnapshot]
  }

  object AgentsList {
    val method: String = "agents.list"
    type Params = Empty
    type Result = Chunk[AgentId]
  }

  object ToolsList {
    val method: String = "tools.list"
    final case class Params(agentId: AgentId) derives JsonCodec
    type Result = Chunk[AgentToolDefinition]
  }

  object BlobsList {
    val method: String = "blobs.list"
    type Params = Empty
    type Result = Chunk[BlobId]
  }

  object BlobsRead {
    val method: String = "blobs.read"

    /** offset >= 0; maxBytes must be in 1..MaxBlobChunkBytes. Limits apply to decoded bytes, not base64 characters. */
    final case class Params(blobId: BlobId, offset: Long, maxBytes: Int = MaxBlobChunkBytes) derives JsonCodec
    final case class Result(blobId: BlobId, offset: Long, dataBase64: String, totalBytes: Long, eof: Boolean)
        derives JsonCodec
  }

  object TurnSubmit {
    val method: String = "turn.submit"

    /** The human author is obtained from the authorized identity, never supplied in these params. Omitted expectedHead
      * means no head precondition. A supplied head must match when write admission is granted.
      */
    final case class Params(
        commandId: String,
        sessionId: SessionId,
        agentId: AgentId,
        task: AgentTask,
        expectedHead: Option[MessageId] = None
    ) derives JsonCodec
    type Result = TurnOutcome
  }

  /** Accepted-run failures are results, not malformed requests. accepted means that the prompt was durably saved;
    * headMessageId is the last durable head observed when settling the run, not a promise to roll back tool effects.
    */
  @jsonDiscriminator("status")
  sealed trait TurnOutcome derives JsonCodec

  object TurnOutcome {
    @jsonHint("succeeded")
    final case class Succeeded(
        runId: String,
        sessionId: SessionId,
        headMessageId: MessageId,
        result: AgentResult,
        newMessages: Chunk[MessageSnapshot]
    ) extends TurnOutcome

    @jsonHint("failed")
    final case class Failed(
        runId: String,
        sessionId: SessionId,
        accepted: Boolean,
        headMessageId: Option[MessageId],
        error: ApplicationError
    ) extends TurnOutcome

    @jsonHint("cancelled")
    final case class Cancelled(runId: String, sessionId: SessionId, accepted: Boolean, headMessageId: Option[MessageId])
        extends TurnOutcome
  }

  enum RunStatus derives JsonCodec {
    @jsonHint("queued") case Queued
    @jsonHint("running") case Running
    @jsonHint("succeeded") case Succeeded
    @jsonHint("failed") case Failed
    @jsonHint("cancelled") case Cancelled
  }

  object RunsGet {
    val method: String = "runs.get"
    final case class Params(runId: String) derives JsonCodec

    /** outcome is absent while queued/running and present for a terminal status. */
    final case class Result(
        runId: String,
        commandId: String,
        sessionId: SessionId,
        agentId: AgentId,
        status: RunStatus,
        outcome: Option[TurnOutcome]
    ) derives JsonCodec
  }

  object RunsCancel {
    val method: String = "runs.cancel"
    type Params = RunsGet.Params

    /** requested means cancellation was requested, not that the run has stopped or its side effects were undone. */
    final case class Result(runId: String, requested: Boolean) derives JsonCodec
  }

  object DiagnosticsMessage {
    val method: String = "diagnostics.message"
    final case class Params(sessionId: SessionId, messageId: MessageId) derives JsonCodec

    /** Authorized/redacted data only. Absence means that no captured provider request is available. */
    final case class Result(message: MessageSnapshot, providerRequest: Option[Json.Obj]) derives JsonCodec
  }

  object TurnStarted {
    val method: String = "turn.started"

    /** Registration/admission notification, not confirmation of prompt persistence. */
    final case class Params(@jsonExplicitNull requestId: Json, commandId: String, runId: String, sessionId: SessionId)
        derives JsonCodec
  }

  object TurnProgress {
    val method: String = "turn.progress"

    /** Transient sanitized display text, not a persisted message, replay cursor or raw provider payload. */
    final case class Params(runId: String, text: String) derives JsonCodec
  }

  /** Application errors in the JSON-RPC envelope. Rejection data uses ApplicationError; standard errors stay standard.
    */
  object ErrorCode {
    val UnsupportedVersion: Int = -32001
    val Unauthorized: Int = -32002
    val Forbidden: Int = -32003
    val NotFound: Int = -32004
    val Conflict: Int = -32005
    val Busy: Int = -32006
    val Rejected: Int = -32007
  }
}
