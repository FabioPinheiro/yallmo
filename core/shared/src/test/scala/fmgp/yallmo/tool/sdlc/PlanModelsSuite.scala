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
    id = "T-1",
    text = "Investigate authentication failures\nCheck \"external\" callers — héllo 🌍",
    createdAt = createdAt,
    updatedAt = updatedAt,
    state = TaskState.Doing,
    subtasks = List("T-3", "T-2"),
    dependsOn = List("T-4", "T-5"),
    leftoverOf = List("T-6"),
    contributesTo = List("I-2", "I-1"),
    assumptions = List("No external clients use the old name", "The test reproduces the failure"),
    archived = true,
    revision = 3L
  )
  private val simplifiedTask = PlanTaskSimplifiedModel(
    id = task.id,
    text = task.text,
    state = TaskState.Doing,
    subtasks = List("T-3", "T-2"),
    dependsOn = List("T-4", "T-5"),
    leftoverOf = List("T-6"),
    contributesTo = List("I-2", "I-1"),
    assumptions = List("No external clients use the old name", "The test reproduces the failure"),
    archived = true,
    expectedRevision = 3L
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
      simplifiedTask.copy(text = "Updated task").toPlanTask(task.createdAt, later),
      task.copy(text = "Updated task", updatedAt = later)
    )
    assertEquals(
      simplifiedIntent.copy(text = "Updated outcome").toPlanIntent(intent.createdAt, later),
      intent.copy(text = "Updated outcome", updatedAt = later)
    )
  }

  test("omitted simplified planning fields use the documented defaults") {
    val taskJson = """{"id":"T-1","text":"Investigate"}"""
    val intentJson = """{"id":"I-1","text":"Reliable authentication"}"""
    assertEquals(taskJson.fromJson[PlanTaskSimplifiedModel], Right(PlanTaskSimplifiedModel("T-1", "Investigate")))
    assertEquals(
      intentJson.fromJson[PlanIntentSimplifiedModel],
      Right(PlanIntentSimplifiedModel("I-1", "Reliable authentication"))
    )
    assertEquals(
      taskJson.fromJson[PlanTaskSimplifiedModel].map(_.toPlanTask(createdAt, updatedAt)),
      Right(PlanTask("T-1", "Investigate", createdAt, updatedAt))
    )
  }

  test("omitted stored planning fields use defaults while timestamps remain required") {
    val metadata = s""""createdAt":"$createdAt","updatedAt":"$updatedAt""""
    assertEquals(
      s"""{"id":"T-1","text":"Investigate",$metadata}""".fromJson[PlanTask],
      Right(PlanTask("T-1", "Investigate", createdAt, updatedAt))
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

  test("simplified models require an ID and text and reject invalid planning field shapes") {
    List(
      "{}",
      """{"id":"T-1"}""",
      """{"text":"Investigate"}""",
      """{"id":42,"text":"Investigate"}""",
      """{"id":"T-1","text":"Investigate","dependsOn":"T-2"}""",
      """{"id":"T-1","text":"Investigate","dependsOn":[42]}""",
      """{"id":"T-1","text":"Investigate","archived":"yes"}"""
    ).foreach { json =>
      assert(json.fromJson[PlanTaskSimplifiedModel].isLeft, clues(json))
      assert(json.fromJson[PlanIntentSimplifiedModel].isLeft, clues(json))
    }
    assert("""{"id":"T-1","text":"Investigate","state":"blocked"}""".fromJson[PlanTaskSimplifiedModel].isLeft)
  }

  test("collections of simplified records retain their IDs and ordering") {
    val tasks = List(simplifiedTask, PlanTaskSimplifiedModel("T-2", "Check callers"))
    val intents = List(simplifiedIntent, PlanIntentSimplifiedModel("I-2", "Stable API"))
    assertEquals(tasks.toJson.fromJson[List[PlanTaskSimplifiedModel]], Right(tasks))
    assertEquals(intents.toJson.fromJson[List[PlanIntentSimplifiedModel]], Right(intents))
  }
}
