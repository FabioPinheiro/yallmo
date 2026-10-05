package fmgp.yallmo.model

import fmgp.yallmo.orchestration.SessionId
import zio.Duration

/** The complete expected error vocabulary exposed by Yallmo model APIs. */
sealed trait YallmoError extends RuntimeException

object YallmoError {
  final case class UnknownAgent(agentId: AgentId)
      extends RuntimeException(s"No agent is registered with id '${AgentId.value(agentId)}'")
      with YallmoError

  final case class StorageFailed(error: YallmoError)
      extends RuntimeException(s"Storage failed: ${error.getMessage}", error)
      with YallmoError

  final case class InvalidSessionId(value: String, reason: String)
      extends RuntimeException(s"Invalid session ID '$value': $reason")
      with YallmoError

  final case class AgentNotInSession(sessionId: SessionId, agentId: AgentId)
      extends RuntimeException(s"Agent '${AgentId.value(agentId)}' is not associated with session '${sessionId.value}'")
      with YallmoError

  final case class UnknownTool(requestedKey: String)
      extends RuntimeException(s"No tool is registered with key '$requestedKey'")
      with YallmoError

  final case class ToolUnavailable(toolKey: String, reason: String)
      extends RuntimeException(s"Tool '$toolKey' is unavailable: $reason")
      with YallmoError

  final case class ToolUnavailableToAgent(agentId: AgentId, toolKey: String)
      extends RuntimeException(s"Agent '${AgentId.value(agentId)}' is not allowed to call tool '$toolKey'")
      with YallmoError

  final case class UnknownRetriever(requestedKey: String)
      extends RuntimeException(s"No retriever is registered with key '$requestedKey'")
      with YallmoError

  final case class RetrieverUnavailableToAgent(agentId: AgentId, retrieverKey: String)
      extends RuntimeException(s"Agent '${AgentId.value(agentId)}' is not allowed to use retriever '$retrieverKey'")
      with YallmoError

  final case class EmptyActionBatch(agentId: AgentId)
      extends RuntimeException(s"Agent '${AgentId.value(agentId)}' returned an empty action batch")
      with YallmoError

  final case class InvalidAgentDecision(reason: String) extends RuntimeException(reason) with YallmoError

  final case class TurnLimitExceeded(limit: Int)
      extends RuntimeException(s"Agent run exceeded its $limit turn limit")
      with YallmoError

  final case class ActionLimitExceeded(limit: Int)
      extends RuntimeException(s"Agent run exceeded its $limit action limit")
      with YallmoError

  final case class AgentTimedOut(timeout: Duration)
      extends RuntimeException(s"Agent run timed out after $timeout")
      with YallmoError

  final case class InvalidAgentBudget(message: String) extends RuntimeException(message) with YallmoError

  final case class AgentExecutionFailed(agentId: AgentId, message: String, cause: Throwable)
      extends RuntimeException(s"Agent '${AgentId.value(agentId)}' failed: $message", cause)
      with YallmoError

  final case class ToolExecutionFailed(toolKey: String, message: String, cause: Throwable)
      extends RuntimeException(s"Tool '$toolKey' failed: $message", cause)
      with YallmoError

  final case class ToolApprovalDenied(toolKey: String, reason: String)
      extends RuntimeException(s"Tool '$toolKey' was not approved: $reason")
      with YallmoError

  final case class InvalidToolArguments(toolKey: String, reason: String)
      extends RuntimeException(s"Tool '$toolKey' received invalid arguments: $reason")
      with YallmoError

  final case class RetrievalFailed(retrieverKey: String, message: String, cause: Throwable)
      extends RuntimeException(s"Retriever '$retrieverKey' failed: $message", cause)
      with YallmoError

  final case class SessionAlreadyExists(id: SessionId)
      extends RuntimeException(s"Session '${id.value}' already exists")
      with YallmoError

  final case class SessionNotFound(id: SessionId)
      extends RuntimeException(s"Session '${id.value}' does not exist")
      with YallmoError

  final case class EmptyAgentIds(id: SessionId)
      extends RuntimeException(s"Session '${id.value}' must contain at least one agent ID")
      with YallmoError

  final case class DuplicateAgentId(id: SessionId, agentId: AgentId)
      extends RuntimeException(s"Session '${id.value}' contains agent '${AgentId.value(agentId)}' more than once")
      with YallmoError

  final case class EmptyAppend(id: SessionId)
      extends RuntimeException(s"Cannot append an empty message batch to session '${id.value}'")
      with YallmoError

  final case class StorageFailure(message: String, cause: Throwable)
      extends RuntimeException(message, cause)
      with YallmoError

  final case class InvalidConfig(message: String) extends RuntimeException(message) with YallmoError
  final case class AuthenticationFailure(message: String) extends RuntimeException(message) with YallmoError
  final case class HttpFailure(message: String) extends RuntimeException(message) with YallmoError
  final case class ProtocolFailure(message: String) extends RuntimeException(message) with YallmoError
  final case class EmptyResponse(message: String) extends RuntimeException(message) with YallmoError
  final case class LlmUnknownTool(name: String)
      extends RuntimeException(s"OpenAI Codex requested unknown tool '$name'")
      with YallmoError
  final case class ToolExecutionFailure(name: String, cause: Throwable)
      extends RuntimeException(s"Tool '$name' failed: ${cause.getMessage}", cause)
      with YallmoError
  final case class ToolLoopLimitExceeded(limit: Int)
      extends RuntimeException(s"LLM tool loop exceeded max iterations: $limit")
      with YallmoError

  final case class Unexpected(cause: Throwable)
      extends RuntimeException(Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName), cause)
      with YallmoError

  def fromThrowable(error: Throwable): YallmoError = error match
    case known: YallmoError => known
    case other              => Unexpected(other)
}
