package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.tool.sdlc.llm.{PlanIntentSimplifiedModel, PlanTaskSimplifiedModel}
import java.time.Instant
import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

final class PlanModelsSuite extends FunSuite {
  private val createdAt = Instant.parse("2026-01-01T00:00:00Z")
  private val updatedAt = Instant.parse("2026-01-02T03:04:05.123456789Z")
  private val task = PlanTask(
    id = PlanTaskId.unsafe("T-1"),
    title = "Investigate authentication failures",
    description = "Check \"external\" callers — héllo 🌍",
    createdAt = createdAt,
    updatedAt = updatedAt,
    state = TaskState.Doing,
    refs = List(
      PlanTaskRelationship.Subtask -> PlanTaskId.unsafe("T-3"),
      PlanTaskRelationship.Subtask -> PlanTaskId.unsafe("T-2"),
      PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-4"),
      PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-5"),
      PlanTaskRelationship.LeftoverOf -> PlanTaskId.unsafe("T-6")
    ),
    contributesTo = List("I-2", "I-1"),
    assumptions = List("No external clients use the old name", "The test reproduces the failure"),
    archived = true,
    revision = 3L,
    steps = List(PlanTaskStep(true, "Reproduce", "Check **expired tokens**"), PlanTaskStep(false, "Fix renewal"))
  )
  private val simplifiedTask = PlanTaskSimplifiedModel(
    id = task.id,
    title = task.title,
    description = task.description,
    state = TaskState.Doing,
    refs = List(
      PlanTaskRelationship.Subtask -> PlanTaskId.unsafe("T-3"),
      PlanTaskRelationship.Subtask -> PlanTaskId.unsafe("T-2"),
      PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-4"),
      PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-5"),
      PlanTaskRelationship.LeftoverOf -> PlanTaskId.unsafe("T-6")
    ),
    contributesTo = List("I-2", "I-1"),
    assumptions = List("No external clients use the old name", "The test reproduces the failure"),
    archived = true,
    expectedRevision = 3L,
    steps = task.steps
  )
  private val intent = PlanIntent(
    id = "I-1",
    text = "Authentication failures stay below 0.1%",
    createdAt = createdAt,
    updatedAt = updatedAt,
    dependsOn = List("T-2", "T-1"),
    archived = true,
    revision = 4L
  )
  private val simplifiedIntent = PlanIntentSimplifiedModel(
    id = intent.id,
    text = intent.text,
    dependsOn = List("T-2", "T-1"),
    archived = true,
    expectedRevision = 4L
  )

  private def fields[A: JsonEncoder](value: A): Map[String, Json] =
    value.toJsonAST match {
      case Right(Json.Obj(entries)) => entries.toMap
      case other                    => fail(s"Expected a JSON object, got $other")
    }

  test("task IDs are typed strings with an explicit empty allocation sentinel") {
    assertEquals(PlanTaskId.from("T-42").map(_.value), Right("T-42"))
    assertEquals(PlanTaskId.unsafe("T-42").toJson, "\"T-42\"")
    assertEquals("\"T-42\"".fromJson[PlanTaskId].map(_.value), Right("T-42"))
    assertEquals("\"\"".fromJson[PlanTaskId], Right(PlanTaskId.New))
    assert(PlanTaskId.New.isEmpty)
    assert(PlanTaskId.unsafe("T-1").nonEmpty)
    List("", "T-0", "T-01", "I-1", "../escape", "T-12345678901", " T-1").foreach { id =>
      assert(PlanTaskId.from(id).isLeft, clues(id))
      if id.nonEmpty then assert(id.toJson.fromJson[PlanTaskId].isLeft, clues(id))
    }
    assert("42".fromJson[PlanTaskId].isLeft)
  }

  test("refs encode as ordered relationship and task ID tuples, without dedicated relationship fields") {
    val expected =
      """[["subtask","T-3"],["subtask","T-2"],["dependsOn","T-4"],["dependsOn","T-5"],["leftoverOf","T-6"]]"""
    assertEquals(task.refs.toJson, expected)
    assertEquals(fields(task)("refs").toJson, expected)
    assertEquals(fields(task).keySet.intersect(Set("subtasks", "dependsOn", "leftoverOf")), Set.empty[String])
    assertEquals(fields(simplifiedTask).keySet.intersect(Set("subtasks", "dependsOn", "leftoverOf")), Set.empty[String])
    List(
      "\"T-2\"",
      """["subtask","T-2"]""",
      """[["unknown","T-2"]]""",
      """[["subtask","I-1"]]""",
      """[["subtask"]]""",
      """[["subtask","T-2","extra"]]""",
      """[{"relationship":"subtask","id":"T-2"}]"""
    ).foreach { refs =>
      val json = s"""{"id":"T-1","title":"Task","refs":$refs}"""
      assert(json.fromJson[PlanTaskSimplifiedModel].isLeft, clues(json))
    }
  }

  test("stored legacy relationships migrate without losing order and mixed formats fail closed") {
    val metadata = s""""createdAt":"$createdAt","updatedAt":"$updatedAt""""
    val legacy =
      s"""{"id":"T-1","text":"Task",$metadata,"subtasks":["T-3","T-2"],"dependsOn":["T-4","T-5"],"leftoverOf":["T-6"]}"""
    assertEquals(legacy.fromJson[PlanTask].map(_.refs), Right(task.refs))
    val mixed = s"""{"id":"T-1","text":"Task",$metadata,"refs":[],"subtasks":[]}"""
    assert(mixed.fromJson[PlanTask].isLeft)
    val invalid = s"""{"id":"T-1","text":"Task",$metadata,"subtasks":[42]}"""
    assert(invalid.fromJson[PlanTask].isLeft)
  }

  test("tasks encode a separate title and Markdown description, never legacy text") {
    assertEquals(fields(task)("title"), Json.Str(task.title))
    assertEquals(fields(task)("description"), Json.Str(task.description))
    assert(!fields(task).contains("text"))
    assert(!fields(simplifiedTask).contains("text"))
    assertEquals(
      PlanTaskSimplifiedModel(PlanTaskId.New, "Read [docs] &copy;").toMarkdown,
      "# Read \\[docs\\] \\&copy;"
    )
    assert("""{"id":"","text":"Old tool input"}""".fromJson[PlanTaskSimplifiedModel].isLeft)
    assert("""{"id":"","title":"Task","description":42}""".fromJson[PlanTaskSimplifiedModel].isLeft)
  }

  test("legacy text migrates into a derived title and an unchanged description") {
    val text = "\n# Authentication\n\nPreserve **all** the notes.\r\nAnd line endings."
    val legacy = s"""{"id":"T-1","text":${text.toJson},"createdAt":"$createdAt","updatedAt":"$updatedAt"}"""
    val migrated = legacy.fromJson[PlanTask].toOption.get
    assertEquals(migrated.title, "Authentication")
    assertEquals(migrated.description, text)
    assertEquals(migrated.createdAt, createdAt)
    assertEquals(migrated.updatedAt, updatedAt)
    assert(!fields(migrated).contains("text"))
    assertEquals(migrated.toJson.fromJson[PlanTask], Right(migrated))
    List("\"title\":\"New title\"", "\"description\":\"New description\"").foreach { mixed =>
      val json = s"""{"id":"T-1","text":"Old",$mixed,"createdAt":"$createdAt","updatedAt":"$updatedAt"}"""
      assert(json.fromJson[PlanTask].isLeft)
    }
  }

  test("task states use the documented lowercase wire values") {
    List(
      TaskState.Open -> "open",
      TaskState.Doing -> "doing",
      TaskState.Done -> "done",
      TaskState.Dropped -> "dropped"
    ).foreach { (state, wire) =>
      assertEquals(state.toJson, s"\"$wire\"")
      assertEquals(s"\"$wire\"".fromJson[TaskState], Right(state))
    }
    assert("\"blocked\"".fromJson[TaskState].isLeft)
    assert("\"Open\"".fromJson[TaskState].isLeft)
  }

  test("stored task and intent snapshots round-trip with timestamp precision and ordered relationships") {
    assertEquals(task.toJson.fromJson[PlanTask], Right(task))
    assertEquals(intent.toJson.fromJson[PlanIntent], Right(intent))
    assertEquals(fields(task)("createdAt"), Json.Str(createdAt.toString))
    assertEquals(fields(task)("updatedAt"), Json.Str(updatedAt.toString))
    assertEquals(fields(intent)("createdAt"), Json.Str(createdAt.toString))
    assertEquals(fields(intent)("updatedAt"), Json.Str(updatedAt.toString))
    assert(!task.toJson.contains('\n'), "JSONL snapshots must escape embedded text newlines")
  }

  test("simplified task and intent snapshots round-trip without timestamps") {
    assertEquals(simplifiedTask.toJson.fromJson[PlanTaskSimplifiedModel], Right(simplifiedTask))
    assertEquals(simplifiedIntent.toJson.fromJson[PlanIntentSimplifiedModel], Right(simplifiedIntent))
    assertEquals(fields(simplifiedTask).keySet.intersect(Set("createdAt", "updatedAt")), Set.empty[String])
    assertEquals(fields(simplifiedIntent).keySet.intersect(Set("createdAt", "updatedAt")), Set.empty[String])
  }

  test("stored and simplified JSON differ only by runtime-maintained metadata") {
    assertEquals(fields(task) -- Set("createdAt", "updatedAt", "revision"), fields(simplifiedTask) - "expectedRevision")
    assertEquals(
      fields(intent) -- Set("createdAt", "updatedAt", "revision"),
      fields(simplifiedIntent) - "expectedRevision"
    )
    assertEquals(simplifiedTask.expectedRevision, task.revision)
    assertEquals(simplifiedIntent.expectedRevision, intent.revision)
  }

  test("task projection and reconstruction retain every planning field") {
    assertEquals(task.toSimplifiedModel, simplifiedTask)
    assertEquals(simplifiedTask.toPlanTask(createdAt, updatedAt), task)
    assertEquals(task.toSimplifiedModel.toPlanTask(task.createdAt, task.updatedAt), task)
  }

  test("intent projection and reconstruction retain every planning field") {
    assertEquals(intent.toSimplifiedModel, simplifiedIntent)
    assertEquals(simplifiedIntent.toPlanIntent(createdAt, updatedAt), intent)
    assertEquals(intent.toSimplifiedModel.toPlanIntent(intent.createdAt, intent.updatedAt), intent)
  }

  test("runtime-supplied update timestamps do not replace creation timestamps") {
    val later = Instant.parse("2026-01-03T00:00:00Z")
    assertEquals(
      simplifiedTask.copy(title = "Updated task").toPlanTask(task.createdAt, later),
      task.copy(title = "Updated task", updatedAt = later)
    )
    assertEquals(
      simplifiedIntent.copy(text = "Updated outcome").toPlanIntent(intent.createdAt, later),
      intent.copy(text = "Updated outcome", updatedAt = later)
    )
  }

  test("omitted simplified planning fields use the documented defaults") {
    val taskJson = """{"id":"T-1","title":"Investigate"}"""
    val intentJson = """{"id":"I-1","text":"Reliable authentication"}"""
    assertEquals(
      taskJson.fromJson[PlanTaskSimplifiedModel],
      Right(PlanTaskSimplifiedModel(PlanTaskId.unsafe("T-1"), "Investigate"))
    )
    assertEquals(
      intentJson.fromJson[PlanIntentSimplifiedModel],
      Right(PlanIntentSimplifiedModel("I-1", "Reliable authentication"))
    )
    assertEquals(
      taskJson.fromJson[PlanTaskSimplifiedModel].map(_.toPlanTask(createdAt, updatedAt)),
      Right(PlanTask(PlanTaskId.unsafe("T-1"), "Investigate", createdAt, updatedAt))
    )
  }

  test("omitted stored planning fields use defaults while timestamps remain required") {
    val metadata = s""""createdAt":"$createdAt","updatedAt":"$updatedAt""""
    assertEquals(
      s"""{"id":"T-1","title":"Investigate",$metadata}""".fromJson[PlanTask],
      Right(PlanTask(PlanTaskId.unsafe("T-1"), "Investigate", createdAt, updatedAt))
    )
    assertEquals(
      s"""{"id":"I-1","text":"Reliable authentication",$metadata}""".fromJson[PlanIntent],
      Right(PlanIntent("I-1", "Reliable authentication", createdAt, updatedAt))
    )
    List(
      "",
      s""", "createdAt":"$createdAt"""",
      s""", "updatedAt":"$updatedAt""""
    ).foreach { metadata =>
      val json = s"""{"id":"T-1","text":"Investigate"$metadata}"""
      assert(json.fromJson[PlanTask].isLeft, clues(json))
      assert(json.fromJson[PlanIntent].isLeft, clues(json))
    }
  }

  test("stored models reject malformed and null timestamps") {
    List("\"not-an-instant\"", "null", "42").foreach { invalid =>
      val json = s"""{"id":"T-1","text":"Investigate","createdAt":$invalid,"updatedAt":"$updatedAt"}"""
      assert(json.fromJson[PlanTask].isLeft, clues(json))
      assert(json.fromJson[PlanIntent].isLeft, clues(json))
      val invalidUpdate =
        s"""{"id":"T-1","text":"Investigate","createdAt":"$createdAt","updatedAt":$invalid}"""
      assert(invalidUpdate.fromJson[PlanTask].isLeft, clues(invalidUpdate))
      assert(invalidUpdate.fromJson[PlanIntent].isLeft, clues(invalidUpdate))
    }
  }

  test("simplified models require identity/content and reject invalid planning field shapes") {
    List(
      "{}",
      """{"id":"T-1"}""",
      """{"text":"Investigate"}""",
      """{"id":42,"text":"Investigate"}""",
      """{"id":"T-1","text":"Investigate","archived":"yes"}"""
    ).foreach { json =>
      assert(json.fromJson[PlanTaskSimplifiedModel].isLeft, clues(json))
      assert(json.fromJson[PlanIntentSimplifiedModel].isLeft, clues(json))
    }
    assert("""{"id":"T-1","title":"Investigate","state":"blocked"}""".fromJson[PlanTaskSimplifiedModel].isLeft)
  }

  test("step decoding requires completion and title, with an optional Markdown description") {
    assertEquals(
      """{"done":false,"title":"Investigate"}""".fromJson[PlanTaskStep],
      Right(PlanTaskStep(false, "Investigate"))
    )
    List(
      """{"title":"Investigate"}""",
      """{"done":false}""",
      """{"done":"yes","title":"Investigate"}""",
      """{"done":false,"title":42}""",
      """{"done":false,"title":"Investigate","description":42}"""
    ).foreach(json => assert(json.fromJson[PlanTaskStep].isLeft, clues(json)))
    List(
      "\"not a list\"",
      """[42]""",
      """[{"title":"Missing completion"}]""",
      """[{"done":false}]"""
    ).foreach { steps =>
      val json = s"""{"id":"T-1","title":"Investigate","steps":$steps}"""
      assert(json.fromJson[PlanTaskSimplifiedModel].isLeft, clues(json))
      val stored =
        s"""{"id":"T-1","title":"Investigate","createdAt":"$createdAt","updatedAt":"$updatedAt","steps":$steps}"""
      assert(stored.fromJson[PlanTask].isLeft, clues(stored))
    }
  }

  test("Markdown export combines narrative and ordered checkboxes without storing the export") {
    val narrative = "Make renewal **reliable**."
    val model = PlanTaskSimplifiedModel(
      PlanTaskId.unsafe("T-1"),
      "Authentication",
      description = narrative,
      steps = List(
        PlanTaskStep(true, "Reproduce", "Confirm the failure."),
        PlanTaskStep(false, "Fix renewal", "Add **regression tests**.\n\n- Expired token\n- Retry")
      )
    )
    val expected =
      "# Authentication\n\nMake renewal **reliable**.\n\n## Steps\n\n" +
        "- [x] Reproduce\n\n  Confirm the failure.\n\n" +
        "- [ ] Fix renewal\n\n  Add **regression tests**.\n  \n  - Expired token\n  - Retry"
    assertEquals(model.toMarkdown, expected)
    assertEquals(model.toPlanTask(createdAt, updatedAt).toMarkdown, expected)
    assertEquals(model.state, TaskState.Open)
    assert(!fields(model).contains("toMarkdown"))
    assert(!fields(model.toPlanTask(createdAt, updatedAt)).contains("toMarkdown"))
  }

  test("Markdown export preserves description formatting under its title and escapes plain-text step titles") {
    val narrative = "\n# Existing Markdown\n\n```scala\nval x = 1\n```\n"
    assertEquals(
      PlanTaskSimplifiedModel(PlanTaskId.unsafe("T-1"), "Example", description = narrative).toMarkdown,
      "# Example\n\n" + narrative
    )
    val indented = "    val example = 1\n"
    assertEquals(
      PlanTaskSimplifiedModel(
        PlanTaskId.unsafe("T-1"),
        "Example",
        description = indented,
        steps = List(PlanTaskStep(false, "Next"))
      ).toMarkdown,
      "# Example\n\n" + indented + "\n\n## Steps\n\n- [ ] Next"
    )
    assertEquals(PlanTaskStep(false, "Keep &copy; literal").toMarkdown, """- [ ] Keep \&copy; literal""")
    assertEquals(
      PlanTaskStep(false, "Read [docs] *first* <tag>").toMarkdown,
      """- [ ] Read \[docs\] \*first\* \<tag\>"""
    )
    assertEquals(PlanTaskStep(true, "Done", " \n ").toMarkdown, "- [x] Done")
    assertEquals(
      PlanTaskStep(false, "Check", "One\r\n\r\n```\rTwo\r```").toMarkdown,
      "- [ ] Check\n\n  One\n  \n  ```\n  Two\n  ```"
    )
  }

  test("collections of simplified records retain their IDs and ordering") {
    val tasks = List(simplifiedTask, PlanTaskSimplifiedModel(PlanTaskId.unsafe("T-2"), "Check callers"))
    val intents = List(simplifiedIntent, PlanIntentSimplifiedModel("I-2", "Stable API"))
    assertEquals(tasks.toJson.fromJson[List[PlanTaskSimplifiedModel]], Right(tasks))
    assertEquals(intents.toJson.fromJson[List[PlanIntentSimplifiedModel]], Right(intents))
  }
}
