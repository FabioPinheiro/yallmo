package fmgp.yallmo.orchestration

import fmgp.yallmo.model.MessagePayload.AudioAttachment
import fmgp.yallmo.model.*
import munit.FunSuite
import zio.*

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

final class LlmDecisionAgentSuite extends FunSuite {
  private def run[A](effect: UIO[A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  test("audio rendering logs only when run and preserves history around explicit error markers") {
    val logs = new CopyOnWriteArrayList[String]()
    val logger = ZLogger.default.map(line => { logs.add(line); () })
    val marker = "SYSTEM ERROR: decision agents do not support audio input"
    val audio = AudioAttachment(fmgp.yallmo.model.BlobId.fresh(), AudioFormat.Wav, Some("PRIVATE_DESCRIPTION"))
    val rendering = LlmDecisionAgent.renderTask(
      AgentTask("Continue"),
      StaticContext[Any]("System"),
      RunContext(Chunk(MessagePayload.User("Before"), audio, MessagePayload.Assistant("After"), audio))
    )
    assert(logs.isEmpty)
    val prompt = run {
      ZIO.scoped {
        (Runtime.removeDefaultLoggers >>> Runtime.addLogger(logger)).build *> rendering
      }
    }
    assertEquals(prompt, s"User: Before\n\n$marker\n\nAssistant: After\n\n$marker\n\nTask: Continue")
    assertEquals(logs.size, 2)
    logs.asScala.foreach { line =>
      assert(line.contains("level=ERROR"))
      assert(line.contains(marker))
      assert(!line.contains("PRIVATE_DESCRIPTION"))
      assert(!line.contains("DCI4Tg=="))
    }
    assert(!prompt.contains("PRIVATE_DESCRIPTION"))
    assert(!prompt.contains("DCI4Tg=="))
  }

  test("effectful prompt rendering preserves text, capabilities, and system instructions") {
    val codec = new AgentDecisionCodec {
      val instructions = "Decide"
      def decode(response: String, staticContext: StaticContext[?]): UIO[AgentDecision] =
        ZIO.succeed(AgentDecision.FinalAnswer(response))
    }
    val prompt = run {
      LlmDecisionAgent.renderPrompt(
        AgentTask("Continue"),
        StaticContext[Any](
          "System",
          tools = Chunk(AgentToolDefinition("clock", "Time")),
          retrievers = Chunk(RetrieverDefinition("notes", "Notes")),
          skills = Chunk(AgentSkill("guide", "Guidance", "Follow guide"))
        ),
        RunContext(Chunk(MessagePayload.User("Hello"), MessagePayload.Assistant("Hi"))),
        codec
      )
    }
    assertEquals(prompt.systemInstruction, "System\n\nFollow guide\n\nDecide")
    assertEquals(
      prompt.prompt,
      "Tool: clock — Time\n\nRetriever: notes — Notes\n\nSkill: guide — Guidance\n\nUser: Hello\n\nAssistant: Hi\n\nTask: Continue"
    )
  }
}
