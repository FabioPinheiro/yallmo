package fmgp.yallmo.orchestration

import fmgp.yallmo.skill.Skill
import fmgp.yallmo.orchestration.config.AgentCapabilities
import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, SkillsToolDefinition}

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

  private val codec = new AgentDecisionCodec {
    val instructions = "Decide"
    def decode(response: String, staticContext: StaticContext[?]): UIO[AgentDecision] =
      ZIO.succeed(AgentDecision.FinalAnswer(response))
  }

  test("prompt rendering advertises escaped metadata only once between system and codec instructions") {
    val visible = Skill(
      "guide&<>\"'",
      "Guidance & <care> \"please\" 'now'",
      "PRIVATE BODY",
      sections = Map("private/path.md" -> "PRIVATE SECTION")
    )
    val hidden = Skill("hidden", "PRIVATE HIDDEN", "PRIVATE BODY", disableModelInvocation = true)
    val denied = Skill("denied", "PRIVATE DENIED", "PRIVATE BODY")
    val available = StaticContext[Any](
      "System",
      functions =
        Chunk(FunctionToolDefinition(FunctionKey.unsafe("clock"), "Time"), SkillsToolDefinition.ReadSkillDefinition),
      skills = Chunk(visible, hidden, denied)
    )
    val context = AgentCapabilities(skills = Some(Set(visible.name, hidden.name)))
      .resolve(AgentId("decision"), available)
      .toOption
      .get
    val prompt = run {
      LlmDecisionAgent.renderPrompt(
        AgentTask("Continue"),
        context,
        RunContext(Chunk(MessagePayload.User("Hello"), MessagePayload.Assistant("Hi"))),
        codec
      )
    }
    assertEquals(
      prompt.systemInstruction,
      s"System\n\n${Skill.renderCatalog(Seq(visible))}\n\n${SkillsToolDefinition.ActivationGuidance}\n\nDecide"
    )
    assert(prompt.systemInstruction.contains("<name>guide&amp;&lt;&gt;&quot;&apos;</name>"))
    assert(
      prompt.systemInstruction.contains(
        "<description>Guidance &amp; &lt;care&gt; &quot;please&quot; &apos;now&apos;</description>"
      )
    )
    assert(!s"${prompt.systemInstruction}\n${prompt.prompt}".contains("PRIVATE"))
    assert(!s"${prompt.systemInstruction}\n${prompt.prompt}".contains("private/path.md"))
    assertEquals(prompt.systemInstruction.sliding("<skill>".length).count(_ == "<skill>"), 1)
    assertEquals(
      prompt.prompt,
      s"Function: clock — Time\nFunction: readSkill — ${SkillsToolDefinition.ReadSkillDefinition.description}\n\nUser: Hello\n\nAssistant: Hi\n\nTask: Continue"
    )
  }

  for (label, skills, policy) <- List(
      ("empty", Chunk.empty[Skill], AgentCapabilities()),
      ("disabled", Chunk(Skill("hidden", "PRIVATE", "PRIVATE", disableModelInvocation = true)), AgentCapabilities()),
      ("denied", Chunk(Skill("denied", "PRIVATE", "PRIVATE")), AgentCapabilities(skills = Some(Set.empty)))
    )
  do
    test(s"$label decision catalog adds neither wrapper nor duplicate task advertisement") {
      val available = StaticContext[Any]("System", skills = skills)
      val context = policy.resolve(AgentId("decision"), available).toOption.get
      val prompt = run(LlmDecisionAgent.renderPrompt(AgentTask("Continue"), context, RunContext(), codec))
      assertEquals(prompt.systemInstruction, "System\n\nDecide")
      assertEquals(prompt.prompt, "Task: Continue")
    }
}
