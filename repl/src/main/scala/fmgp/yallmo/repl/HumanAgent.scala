package fmgp.yallmo.repl

import fmgp.yallmo.model.{Agent, AgentId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason}
import zio.*

/** A turn requested by an agent and awaiting a response from the REPL-owned terminal loop. */
final class PendingHumanTurn private[repl] (
    val request: AgentTurnRequest,
    private val reply: Promise[YallmoError, String]
) {

  /** Completes this turn with text entered by the human. Returns false when it was already cancelled or completed. */
  def respond(text: String): UIO[Boolean] = reply.succeed(text)

  /** Cancels this turn, for example when the REPL session exits. */
  def fail(error: YallmoError): UIO[Boolean] = reply.fail(error)

  private[repl] def isAwaiting: UIO[Boolean] = reply.poll.map(_.isEmpty)

  private[repl] def awaitResponse: IO[YallmoError, String] = reply.await
}

/** Bridge between the orchestrator and the REPL terminal.
  *
  * The human agent publishes pending turns here and then suspends. Terminal clients are the sole consumers: they keep
  * control of commands, line editing, history, and cancellation before completing a pending turn with normal text.
  */
final class HumanTurnChannel private (pendingTurns: Queue[PendingHumanTurn]) {

  def awaitResponse(request: AgentTurnRequest): IO[YallmoError, String] =
    for {
      reply <- Promise.make[YallmoError, String]
      pending = new PendingHumanTurn(request, reply)
      _ <- pendingTurns.offer(pending)
      response <- pending.awaitResponse.ensuring(
        reply
          .fail(
            YallmoError.Unexpected(
              new java.util.concurrent.CancellationException("The REPL stopped waiting for this human turn.")
            )
          )
          .unit
      )
    } yield response

  /** Takes the next still-live request. Interrupted orchestrator fibers can leave cancelled requests in the queue. */
  def takePending: UIO[PendingHumanTurn] =
    pendingTurns.take.flatMap { pending =>
      pending.isAwaiting.flatMap {
        case true  => ZIO.succeed(pending)
        case false => takePending
      }
    }
}

object HumanTurnChannel {
  def make: UIO[HumanTurnChannel] = Queue.unbounded[PendingHumanTurn].map(new HumanTurnChannel(_))
}

object HumanAgent {
  def make(id: AgentId, channel: HumanTurnChannel): HumanAgent = new HumanAgent(id, channel)
}

/** An [[Agent]] implementation whose response is entered by a human through a REPL terminal. */
final class HumanAgent(val id: AgentId, channel: HumanTurnChannel) extends Agent[Any] {
  override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
    channel
      .awaitResponse(request)
      .map(text => AgentTurnResponse(Chunk(MessagePayload.Assistant(text)), LlmStopReason.Stop))
}
