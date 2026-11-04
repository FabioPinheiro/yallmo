package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.som.*
import java.time.Instant
import munit.FunSuite
import scala.concurrent.Future
import zio.*
import zio.json.*

final class SystemOneHistoryDecisionSuite extends FunSuite {
  private final case class Evidence(context: String) derives JsonCodec
  private val original = Chunk(
    Message(MessageData(AgentId("user"), None, Instant.EPOCH, MessagePayload.User("seed")))
  )
  private val helper = SystemOneHistoryDecision("jev-latest")
  private val evidence = "A temporary research note is superseded by the final summary."

  private final class History(calls: Ref[Int]) extends ManagedHistory {
    def messages = ZIO.succeed(original)
    def initialMessages = ZIO.succeed(original)
    private[orchestration] def snapshot = ZIO.succeed(original)
    def rebase(base: MessageId, head: MessageId, onto: Option[MessageId]) = calls.update(_ + 1).as(original)
    private[orchestration] def append(payloads: Chunk[MessagePayload]) = ZIO.unit
    private[orchestration] def appendAt(payloads: Chunk[MessagePayload], expectedParent: Option[MessageId]) =
      ZIO.succeed(expectedParent)
  }

  private def response(answer: SystemOneAnswer): SystemOneResponse =
    SystemOneResponse("jev-1.13.0", Map("rewrite" -> answer), SystemOneUsage(12, 2))
  private def choice(selected: String = "rebase", probability: Double = 0.98, confidence: Double = 0.97) =
    SystemOneAnswer.Choice(selected, Map("keep" -> (1.0 - probability), "rebase" -> probability), confidence)

  private val cases = List(
    ("rebase", response(choice()), true),
    ("keep", response(choice("keep", 0.02)), false),
    ("tie", response(choice(probability = 0.5)), false),
    ("low probability", response(choice(probability = 0.89)), false),
    (
      "low official confidence ignores Laya extras",
      response(choice(confidence = 0.2).copy(answerConfidence = Some(1.0))),
      false
    ),
    ("unknown choice", response(choice("invented-id")), false),
    ("wrong answer type", response(SystemOneAnswer.Noul(0.99)), false),
    ("missing answer", SystemOneResponse("jev-1.13.0", Map.empty, SystemOneUsage(1, 1)), false),
    (
      "extra probability key",
      response(choice().copy(probabilities = Map("keep" -> 0.01, "rebase" -> 0.98, "other" -> 0.01))),
      false
    ),
    ("missing probability key", response(choice().copy(probabilities = Map("rebase" -> 0.98))), false),
    (
      "unnormalized probabilities",
      response(choice().copy(probabilities = Map("keep" -> 0.5, "rebase" -> 0.98))),
      false
    ),
    ("non-finite probability", response(choice(probability = Double.NaN)), false),
    ("non-finite confidence", response(choice(confidence = Double.PositiveInfinity)), false),
    ("out-of-range confidence", response(choice(confidence = 1.1)), false),
    ("out-of-range probability", response(choice(probability = 1.1)), false),
    (
      "floating-point tolerance",
      response(choice().copy(probabilities = Map("keep" -> 0.02000001, "rebase" -> 0.98))),
      true
    )
  )

  for (name, result, expected) <- cases do
    test(s"typed wire decision: $name") {
      run {
        for {
          calls <- Ref.make(0)
          requests <- Ref.make(Chunk.empty[String])
          provider = recording(requests, ZIO.succeed(result))
          fresh <- helper
            .prepare(new History(calls))(messages => Some((messages.head.id, messages.last.id, None, evidence)))
            .provideEnvironment(ZEnvironment[SOMProvider](provider))
          count <- calls.get
          wire <- requests.get
        } yield {
          assertEquals(count, if expected then 1 else 0)
          assertEquals(fresh.messages, original.map(_.payload))
          assertEquals(wire.size, 1)
          val decoded = wire.head.fromJson[SystemOneRequest[Evidence]]
          assert(decoded.isRight, s"$decoded wire=${wire.head}")
          val request = decoded.toOption.get
          assertEquals(request.state.context, evidence)
          assertEquals(request.model, "jev-latest")
          assertEquals(request.questions.keySet, Set("rewrite"))
          assertEquals(
            request.questions("rewrite").asInstanceOf[SystemOneQuestion.Choice].criteria.keySet,
            Set("keep", "rebase")
          )
          assert(!wire.head.contains(MessageId.value(original.head.id)))
          assert(!wire.head.contains("seed"))
        }
      }
    }

  for scenario <- List("none", "missing-base", "missing-head", "missing-onto") do
    test(s"$scenario candidate never calls advisory provider") {
      run {
        for {
          calls <- Ref.make(0)
          requests <- Ref.make(Chunk.empty[String])
          provider = recording(requests, ZIO.dieMessage("Must not call System One"))
          fresh <- helper
            .prepare(new History(calls)) { messages =>
              val id = messages.head.id
              val missing = MessageId.unsafe("missing")
              scenario match {
                case "none"         => None
                case "missing-base" => Some((missing, id, None, evidence))
                case "missing-head" => Some((id, missing, None, evidence))
                case _              => Some((id, id, Some(missing), evidence))
              }
            }
            .provideEnvironment(ZEnvironment[SOMProvider](provider))
          seen <- requests.get
          count <- calls.get
        } yield {
          assertEquals(seen, Chunk.empty)
          assertEquals(count, 0)
          assertEquals(fresh.messages, original.map(_.payload))
        }
      }
    }

  for malformed <- List(false, true) do
    test(s"provider failure keeps without disclosing error (malformed JSON=$malformed)") {
      run {
        for {
          calls <- Ref.make(0)
          requests <- Ref.make(Chunk.empty[String])
          failure =
            if malformed then
              ZIO.fromEither("{bad-json".fromJson[SystemOneResponse].left.map(YallmoError.ProtocolFailure.apply))
            else ZIO.fail(YallmoError.ProtocolFailure("SECRET provider error"))
          provider = recording(requests, failure)
          fresh <- helper
            .prepare(new History(calls))(messages => Some((messages.head.id, messages.last.id, None, evidence)))
            .provideEnvironment(ZEnvironment[SOMProvider](provider))
          count <- calls.get
        } yield {
          assertEquals(count, 0)
          assertEquals(fresh.messages, original.map(_.payload))
        }
      }
    }

  for threshold <- List(Double.NaN, Double.PositiveInfinity, 0.0, 0.5, 1.1) do
    test(s"invalid host threshold $threshold fails before advisory") {
      run {
        for {
          calls <- Ref.make(0)
          requests <- Ref.make(Chunk.empty[String])
          provider = recording(requests, ZIO.dieMessage("Must not call System One"))
          result <- SystemOneHistoryDecision("jev-latest", threshold)
            .prepare(new History(calls))(_ => None)
            .provideEnvironment(ZEnvironment[SOMProvider](provider))
            .exit
          seen <- requests.get
        } yield {
          assert(result.isFailure)
          assertEquals(seen, Chunk.empty)
        }
      }
    }

  test("advisory interruption is not a KEEP fallback") {
    run {
      for {
        calls <- Ref.make(0)
        requests <- Ref.make(Chunk.empty[String])
        entered <- Promise.make[Nothing, Unit]
        provider = recording(requests, entered.succeed(()) *> ZIO.never)
        fiber <- helper
          .prepare(new History(calls))(messages => Some((messages.head.id, messages.last.id, None, evidence)))
          .provideEnvironment(ZEnvironment[SOMProvider](provider))
          .fork
        _ <- entered.await
        result <- fiber.interrupt
        count <- calls.get
      } yield {
        assert(result.isInterrupted)
        assertEquals(count, 0)
      }
    }
  }

  private def recording(requests: Ref[Chunk[String]], result: IO[YallmoError, SystemOneResponse]): SOMProvider =
    new SOMProvider {
      protected def evaluateRequestJson(json: String) = requests.update(_ :+ json) *> ZIO.succeed {
        val decoded = json.fromJson[SystemOneRequest[Evidence]]
        assert(decoded.isRight, decoded.toString)
        val request = decoded.toOption.get
        assertEquals(request.model, "jev-latest")
        assertEquals(request.state.context, evidence)
        assertEquals(request.questions.keySet, Set("rewrite"))
        assertEquals(
          request.questions("rewrite").asInstanceOf[SystemOneQuestion.Choice].criteria.keySet,
          Set("keep", "rebase")
        )
      } *> result
    }

  private def run(effect: IO[YallmoError, Unit]): Future[Unit] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.mapError(error => FiberFailure(Cause.fail(error))))
    }
}
