package fmgp.yallmo.server.protocol

import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.model.{AgentId, BlobId, BlobUploadLimits, Message, MessageId, MessagePayload, UserId}
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.orchestration.{AgentResult, AgentTask, AgentToolDefinition, Session, SessionId, SessionSummary}
import java.time.Instant
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** The Yallmo application contract, identical on WebSocket and Unix-domain sockets. Each operation declares its
  * JSON-RPC method, named Params and Result. This defines messages only: no handler, listener or runtime is started.
  * Domain values are reused directly. History uses redacted snapshots to exclude private provider continuations;
  * agents.invoke exchanges payload-flexible Messages through a configured recipient. Client and server share one
  * current contract, without application-version negotiation. See server/PROTOCOL.md.
  */
object YallmoProtocol {
  val MaxBlobChunkBytes: Int = BlobUploadLimits.MaxChunkBytes

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
      toolName: Option[ToolKey] = None
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
          case MessagePayload.ToolCall(call)     => Some(call.name)
          case MessagePayload.ToolOutput(output) => Some(output.name)
          case _                                 => None
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

  /** The connected user and its server-provisioned actors; recipient agents are advertised separately. */
  final case class Identity(id: UserId, role: Role, agentIds: Chunk[AgentId]) derives JsonCodec {
    def chatAgent: Option[AgentId] = agentIds.find(_ == UserId.chatAgent(id))
    def websiteAgent: Option[AgentId] = agentIds.find(_ == UserId.websiteAgent(id))
  }
  final case class ApplicationError(code: String, message: String) derives JsonCodec

  object Initialize {
    val method: String = "initialize"

    /** Claimed local user, not an authenticated identity. Transport/server policy determines capabilities. */
    @jsonNoExtraFields
    final case class Params(clientName: String, userId: Option[UserId] = None) derives JsonCodec
    @jsonNoExtraFields
    final case class Result(
        serverInstanceId: String,
        identity: Identity,
        methods: List[String],
        notifications: List[String],
        maxBlobChunkBytes: Int,
        configuredAgentIds: Option[Chunk[AgentId]] = None
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

  object SessionsFork {
    val method: String = "sessions.fork"

    /** Fork the current head only; name is an ID/name, never a filesystem path. */
    final case class Params(
        sourceSessionId: SessionId,
        name: Option[SessionId] = None,
        expectedHead: Option[MessageId] = None
    ) derives JsonCodec
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

  enum ProviderKind derives JsonCodec {
    case OAI, ChatGPT
  }

  /** Presence only; configured credentials may be expired or revoked. No secrets or endpoints are exposed. */
  enum ProviderCredentialStatus derives JsonCodec {
    @jsonHint("not-applicable") case NotApplicable
    @jsonHint("logged-out") case LoggedOut
    @jsonHint("configured") case Configured
  }

  final case class ProviderSummary(
      id: String,
      providerType: ProviderKind,
      credentials: ProviderCredentialStatus
  ) derives JsonCodec

  object ProvidersList {
    val method: String = "providers.list"
    type Params = Empty
    type Result = Chunk[ProviderSummary]
  }

  object ProvidersLoginStart {
    val method: String = "providers.login.start"
    final case class Params(providerId: String) derives JsonCodec
    final case class Result(attemptId: String, authorizationUrl: String, expiresAtMillis: Long) derives JsonCodec {
      override def toString: String = "ProviderLoginStarted(<redacted>)"
    }
  }

  object ProvidersLoginComplete {
    val method: String = "providers.login.complete"
    val MaxRedirectChars: Int = 16384
    final case class Params(attemptId: String, redirectUrl: String) derives JsonCodec {
      override def toString: String = "ProviderLoginComplete(<redacted>)"
    }

    /** False means privately saved and active for future runtime requests; true means saved only, requiring restart. */
    final case class Result(restartRequired: Boolean) derives JsonCodec
  }

  object ProvidersLoginCancel {
    val method: String = "providers.login.cancel"
    final case class Params(attemptId: String) derives JsonCodec
    type Result = Empty
  }

  object AgentsInvoke {
    val method: String = "agents.invoke"

    /** Independent invocation: no session fields, inherited history or prior provider continuation. The input author
      * must match the explicitly selected, user-owned actingAgentId. Payload support belongs to the recipient; a parent
      * must exist but does not load history.
      */
    @jsonNoExtraFields
    final case class Params(agentId: AgentId, input: Message, actingAgentId: AgentId) derives JsonCodec
    type Result = Message
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

  object BlobsUpload {
    val method: String = "blobs.upload"
    val MaxEncodedChunkChars: Int = 4 * ((MaxBlobChunkBytes + 2) / 3)

    /** Every command/result uses the caller's BlobId; Begin echoes it without allocating another identity. Commands are
      * connection-owned and ordered, with no automatic retry, replay or resume guarantee.
      */
    @jsonDiscriminator("command")
    sealed trait Params derives JsonCodec
    object Params {
      @jsonHint("begin") final case class Begin(blobId: BlobId, totalBytes: Long) extends Params
      @jsonHint("append") final case class Append(blobId: BlobId, offset: Long, dataBase64: String) extends Params {
        override def toString: String = s"Append($blobId,$offset,<redacted>)"
      }
      @jsonHint("complete") final case class Complete(blobId: BlobId) extends Params
      @jsonHint("abort") final case class Abort(blobId: BlobId) extends Params
    }
    @jsonDiscriminator("status")
    sealed trait Result derives JsonCodec
    object Result {
      @jsonHint("begun") final case class Begun(blobId: BlobId) extends Result
      @jsonHint("appended") final case class Appended(blobId: BlobId, nextOffset: Long) extends Result
      @jsonHint("completed") final case class Completed(blobId: BlobId) extends Result
      @jsonHint("aborted") final case class Aborted(blobId: BlobId) extends Result
    }
  }

  object TurnSubmit {
    val method: String = "turn.submit"

    /** Explicit conversation destination: New allocates only as part of submission, Existing never creates. */
    sealed trait Target
    object Target {
      final case class New(name: Option[SessionId] = None) extends Target
      final case class Existing(sessionId: SessionId) extends Target

      // Strict product decoding avoids discriminator fields being treated as unknown by subtype codecs.
      @jsonNoExtraFields
      private final case class Wire(kind: String, name: Option[SessionId] = None, sessionId: Option[SessionId] = None)
          derives JsonCodec
      given JsonCodec[Target] = summon[JsonCodec[Wire]].transformOrFail(
        {
          case Wire("new", name, None)          => Right(New(name))
          case Wire("existing", None, Some(id)) => Right(Existing(id))
          case _                                => Left("Expected a new or existing conversation target")
        },
        {
          case New(name)    => Wire("new", name = name)
          case Existing(id) => Wire("existing", sessionId = Some(id))
        }
      )
    }

    /** The actor authors the prompt; agentId selects the recipient. expectedHead is valid only for Existing. New
      * returns its allocated session even if execution fails. No automatic retries, replay or rollback.
      */
    @jsonNoExtraFields
    final case class Params(
        target: Target,
        agentId: AgentId,
        task: AgentTask,
        actingAgentId: AgentId,
        expectedHead: Option[MessageId] = None,
        resetContinuation: Boolean = false
    ) derives JsonCodec
    type Result = TurnOutcome

    /** Only these JSON-RPC failures establish rejection before execution; InternalError is an unknown outcome. */
    def isKnownRejection(code: Int): Boolean =
      Set(
        ErrorCode.Busy,
        ErrorCode.Conflict,
        ErrorCode.Unauthorized,
        ErrorCode.Forbidden,
        ErrorCode.NotFound,
        ErrorCode.Rejected,
        -32601,
        -32602
      ).contains(code)
  }

  /** Authoritative prompt persistence, not run admission. Unknown is required when acceptance cannot be established. */
  enum PromptAcceptance derives JsonCodec {
    @jsonHint("notAccepted") case NotAccepted
    @jsonHint("accepted") case Accepted
    @jsonHint("unknown") case Unknown
  }

  /** Terminal execution results. Rejections before execution use JSON-RPC errors. No run registry or replay API. */
  @jsonDiscriminator("status")
  sealed trait TurnOutcome derives JsonCodec

  object TurnOutcome {
    @jsonHint("succeeded")
    final case class Succeeded(
        session: SessionSnapshot,
        result: AgentResult,
        newMessages: Chunk[MessageSnapshot]
    ) extends TurnOutcome

    @jsonHint("failed")
    final case class Failed(
        acceptance: PromptAcceptance,
        session: Option[SessionSnapshot],
        error: ApplicationError
    ) extends TurnOutcome
  }

  // Disabled future drafts below: not advertised, implemented, or part of active turn submission semantics.
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

    /** True acknowledges recorded/already-pending cancellation, not termination or undo; terminal runs return false.
      * Missing/expired runs are NotFound. Control must remain responsive independently of a long turn.
      */
    final case class Result(runId: String, requested: Boolean) derives JsonCodec
  }

  object CommandsGet {
    val method: String = "commands.get"
    final case class Params(commandId: String) derives JsonCodec

    /** Identity-scoped lookup within serverInstanceId. Absent run is a retained dedup tombstone after detail expiry,
      * not an unknown command or permission to re-execute. New commands are Busy when the bounded ledger is full.
      */
    final case class Result(runId: String, run: Option[RunsGet.Result]) derives JsonCodec
  }

  /** Explicit operator-only access to the latest captured provider request for a session, not a new inference. */
  object DiagnosticsRequest {
    val method: String = "diagnostics.request"
    val MaxCaptureBytes: Int = 256 * 1024
    final case class Params(sessionId: SessionId) derives JsonCodec

    /** Capture metadata and redacted request body, never a server path or transport credentials. Absence means no
      * retained capture (including restart/eviction). Conversation/tool text remains sensitive.
      */
    final case class Result(providerRequest: Option[Json.Obj]) derives JsonCodec
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
    val Unauthorized: Int = -32002
    val Forbidden: Int = -32003
    val NotFound: Int = -32004
    val Conflict: Int = -32005
    val Busy: Int = -32006
    val Rejected: Int = -32007
  }
}
