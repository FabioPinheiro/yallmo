package fmgp.yallmo.agents

import fmgp.yallmo.tool.FunctionKey
import fmgp.yallmo.agents.CoachApplicationTools.{Application, Record}
import fmgp.yallmo.fs.{FileSystem, WriteMode}
import fmgp.yallmo.orchestration.{AgentFunction, ExternalFunctionCall}
import java.nio.file.Files
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import zio.*
import zio.json.*

final class CoachApplicationToolsSuite extends FunSuite {
  private val application = Application(
    "acme-scala",
    "Acme",
    "Staff Scala engineer",
    "interviewing",
    contact = Some("Interviewer"),
    channel = Some("LinkedIn"),
    nextAction = Some("User to send reply"),
    replyDraft = Some("Thank you. Could we meet on Tuesday?")
  )

  test("persists application stages and unsent replies across reconstruction with an append-only history") {
    withWorkspace { fs =>
      val tools = run(CoachApplicationTools.makeFunctions(fs))
      val first = save(tools, application, 0)
      assertEquals(first.revision, 1)
      assertEquals(first.application.replyDraft, application.replyDraft)
      val updated = application.copy(
        nextAction = Some("Wait for confirmation"),
        interviewStart = Some("2026-11-03T10:00:00Z"),
        interviewTimeZone = Some("Europe/Lisbon"),
        interviewState = Some("proposed"),
        interviewDurationMinutes = Some(45)
      )
      val next = save(tools, updated, 1)
      assertEquals(next.revision, 2)
      val fresh = run(CoachApplicationTools.makeFunctions(fs))
      assertEquals(get(fresh, application.id), next)
      val summaries = invoke(fresh, CoachApplicationTools.ListKey, "{}")
      assert(summaries.contains("proposed"))
      assert(!summaries.contains("Thank you"), "Listing should not dump private message drafts")
      assertEquals(run(fs.readUtf8("applications.jsonl")).linesIterator.size, 2)
    }
  }

  test("identical retries are idempotent and stale edits fail without changing history") {
    withWorkspace { fs =>
      val tools = run(CoachApplicationTools.makeFunctions(fs))
      val first = save(tools, application, 0)
      assertEquals(save(tools, application, 0), first)
      assertEquals(run(fs.readUtf8("applications.jsonl")).linesIterator.size, 1)
      assert(saveResult(tools, application.copy(stage = "offer"), 0).isLeft)
      assertEquals(get(tools, application.id), first)
    }
  }

  test("concurrent different edits serialize and one must reload after a revision conflict") {
    withWorkspace { fs =>
      val tools = run(CoachApplicationTools.makeFunctions(fs))
      save(tools, application, 0)
      val results = run(ZIO.foreachPar(List("offer", "rejected")) { stage =>
        tool(tools, CoachApplicationTools.SaveKey)
          .execute(ExternalFunctionCall(CoachApplicationTools.SaveKey, saveJson(application.copy(stage = stage), 1)))
          .either
      })
      assertEquals(results.count(_.isRight), 1)
      assertEquals(results.count(_.isLeft), 1)
      assertEquals(get(tools, application.id).revision, 2)
    }
  }

  test("validates scheduling timezone/DST, dates, fields, stages and arguments before writing") {
    withWorkspace { fs =>
      val tools = run(CoachApplicationTools.makeFunctions(fs))
      val scheduled = application.copy(
        interviewStart = Some("2026-07-03T10:00:00+01:00"),
        interviewTimeZone = Some("Europe/Lisbon"),
        interviewState = Some("proposed")
      )
      val bad = List(
        application.copy(id = "../escape"),
        application.copy(company = " "),
        application.copy(stage = "sent email"),
        application.copy(followUpDate = Some("tomorrow")),
        application.copy(jobUrl = Some("file:///etc/passwd")),
        application.copy(notes = Some("x" * 4001)),
        scheduled.copy(interviewStart = Some("2026-07-03T10:00:00Z")),
        scheduled.copy(interviewTimeZone = Some("CANDIDATE_TIMEZONE")),
        scheduled.copy(interviewState = None),
        scheduled.copy(interviewDurationMinutes = Some(-1)),
        application.copy(interviewState = Some("confirmed"))
      )
      bad.foreach(a => assert(saveResult(tools, a, 0).isLeft))
      assert(
        run(
          tool(tools, CoachApplicationTools.SaveKey)
            .execute(ExternalFunctionCall(CoachApplicationTools.SaveKey, "{\"extra\":true}"))
            .either
        ).isLeft
      )
      assertEquals(invoke(tools, CoachApplicationTools.ListKey, "{}"), "[]")
      assertEquals(save(tools, scheduled, 0).application.interviewState, Some("proposed"))
    }
  }

  test("corrupt or incomplete journals fail closed instead of resetting or concatenating records") {
    withWorkspace { fs =>
      val tools = run(CoachApplicationTools.makeFunctions(fs))
      val record = save(tools, application, 0)
      for broken <- List(
          "not JSON\n",
          record.toJson,
          record.toJson + "\n\n",
          record.toJson + " trailing\n",
          record.copy(revision = 99).toJson + "\n"
        )
      do {
        run(fs.writeUtf8("applications.jsonl", broken, WriteMode.Replace))
        assert(saveResult(tools, application, 0).isLeft)
        assertEquals(run(fs.readUtf8("applications.jsonl")), broken)
      }
    }
  }

  test("separate tool instances stay rooted and cannot read another coach's records") {
    withWorkspace { one =>
      withWorkspace { two =>
        val first = run(CoachApplicationTools.makeFunctions(one))
        val second = run(CoachApplicationTools.makeFunctions(two))
        save(first, application, 0)
        assertEquals(invoke(second, CoachApplicationTools.ListKey, "{}"), "[]")
        assertEquals(invoke(second, CoachApplicationTools.GetKey, "{\"id\":\"acme-scala\"}"), "{\"not_found\":true}")
      }
    }
  }

  private def tool(tools: Chunk[AgentFunction[Any]], key: FunctionKey): AgentFunction[Any] =
    tools.find(_.definition.name == key).get
  private def invoke(tools: Chunk[AgentFunction[Any]], key: FunctionKey, json: String): String =
    run(tool(tools, key).execute(ExternalFunctionCall(key, json))).text
  private def saveJson(a: Application, revision: Int): String =
    s"{\"expectedRevision\":$revision,\"application\":${a.toJson}}"
  private def saveResult(tools: Chunk[AgentFunction[Any]], a: Application, revision: Int) =
    run(
      tool(tools, CoachApplicationTools.SaveKey)
        .execute(ExternalFunctionCall(CoachApplicationTools.SaveKey, saveJson(a, revision)))
        .either
    )
  private def save(tools: Chunk[AgentFunction[Any]], a: Application, revision: Int): Record =
    invoke(tools, CoachApplicationTools.SaveKey, saveJson(a, revision)).fromJson[Record].toOption.get
  private def get(tools: Chunk[AgentFunction[Any]], id: String): Record =
    invoke(tools, CoachApplicationTools.GetKey, s"{\"id\":\"$id\"}").fromJson[Record].toOption.get

  private def withWorkspace(test: FileSystem => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-coach-notebook-").toRealPath()
    try test(run(FileSystem.open(root.toString)))
    finally {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
      finally paths.close()
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
