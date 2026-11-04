package fmgp.yallmo.model.som

import fmgp.yallmo.model.YallmoError
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import zio.{IO, Runtime, Unsafe, ZIO}
import zio.json.*
import zio.json.ast.Json

final class SORequestEncodingSuite extends FunSuite {
  private final case class State(@jsonField("task_text") task: String) derives JsonCodec
  private final case class Questions(
      @jsonField("route_to") route: SystemOneQuestion.Choice,
      score: SystemOneQuestion.Score,
      yes: SystemOneQuestion.Noul,
      bare: SystemOneQuestion.Noul
  ) derives JsonCodec,
        SOSchema

  private val questions = Questions(
    SO.choice("Select a worker", "review" -> "Review", "code" -> "Implement"),
    SO.score("Rate complexity", "simple", "complex"),
    SO.noul("Needs review?", "Review required", "No review"),
    SO.noul("Ready?")
  )
  private val request = SORequest(State("Synthetic change"), "jev-latest", questions)
  private val expected = """{
    "state":{"task_text":"Synthetic change"},"model":"jev-latest","questions":{
      "route_to":{"type":"choice","instructions":"Select a worker","criteria":{"review":"Review","code":"Implement"}},
      "score":{"type":"score","instructions":"Rate complexity","criteria":["simple","complex"]},
      "yes":{"type":"noul","instructions":"Needs review?","criteria":{"true":"Review required","false":"No review"}},
      "bare":{"type":"noul","instructions":"Ready?"}
    }}"""
  private val response = SystemOneResponse(
    "jev-test",
    Map(
      "route_to" -> SystemOneAnswer.Choice("review", Map("review" -> 1.0, "code" -> 0.0), 1.0),
      "score" -> SystemOneAnswer.Score(0.0, Map("0" -> "simple"), Map("0" -> 1.0), 1.0),
      "yes" -> SystemOneAnswer.Noul(0.99),
      "bare" -> SystemOneAnswer.Noul(0.98)
    ),
    SystemOneUsage(1, 1)
  )

  private final class RecordingProvider extends SOMProvider {
    var sent = Vector.empty[String]
    protected def evaluateRequestJson(json: String): IO[YallmoError, SystemOneResponse] = ZIO.succeed {
      sent :+= json
      response
    }
  }

  private def run[A](effect: IO[YallmoError, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe.run(effect).getOrThrow()
  }

  // Full structural equality independent of object member order, also rejecting duplicate keys at every depth.
  private def canonical(json: Json): Json = json match {
    case Json.Obj(fields) =>
      assertEquals(fields.map(_._1).distinct.size, fields.size)
      Json.Obj(fields.sortBy(_._1).map { case (key, value) => key -> canonical(value) })
    case Json.Arr(values) => Json.Arr(values.map(canonical))
    case value            => value
  }
  private def assertEnvelope(actual: String, wanted: String = expected): Unit =
    assertEquals(
      canonical(actual.fromJson[Json].fold(fail(_), identity)),
      canonical(wanted.fromJson[Json].fold(fail(_), identity))
    )

  test("SORequest JSON and SOSchema encode all concrete primitives, aliases and optional Noul criteria") {
    val json = request.toJson
    assertEnvelope(json)
    assertEnvelope(summon[SOSchema[Questions]].encodeRequest(request))
    assertEquals(json.fromJson[SORequest[State, Questions]], Right(request))
    assertEquals(summon[SOSchema[Questions]].decodeRequest[State](json), Right(request))
    val dynamic = expected.fromJson[SystemOneRequest[State]].fold(fail(_), identity)
    assertEquals(json.fromJson[SystemOneRequest[State]], Right(dynamic))
    assertEnvelope(dynamic.toJson) // The ordinary map-backed encoder remains unchanged.
  }

  test("state retains its caller-supplied encoder rather than being rebuilt from product fields") {
    given JsonEncoder[State] = JsonEncoder[String].contramap(state => s"custom:${state.task}")
    val wanted = expected.replace("{\"task_text\":\"Synthetic change\"}", "\"custom:Synthetic change\"")
    assertEnvelope(request.toJson, wanted)
    assertEnvelope(summon[SOSchema[Questions]].encodeRequest(request), wanted)
    val provider = new RecordingProvider
    run(provider.evaluate(request))
    assertEnvelope(provider.sent.head, wanted)
  }

  test("actual typed SOMProvider sends the complete discriminated envelope and validates all answer types") {
    val provider = new RecordingProvider
    val answer = run(provider.evaluate(request))
    assertEquals(provider.sent.size, 1)
    assertEnvelope(provider.sent.head)
    assertEquals(answer.route.choice, "review")
    assertEquals(answer.score.score, 0.0)
    assertEquals(answer.yes.noul, 0.99)
    assertEquals(answer.bare.noul, 0.98)
  }

  private final case class CustomQuestions(@jsonField("custom_route") route: SystemOneQuestion.Choice) derives SOSchema
  private def customRequest = SORequest(State("Synthetic change"), "jev-latest", CustomQuestions(questions.route))
  private def customEncoder(json: String): JsonEncoder[CustomQuestions] =
    JsonEncoder[Json].contramap(_ => json.fromJson[Json].fold(fail(_), identity))

  test("custom payload encoders retain aliases, instructions, criteria and extra fields, without duplicate hints") {
    for hint <- List("", "\"type\":\"choice\",") do {
      given JsonEncoder[CustomQuestions] = customEncoder(
        s"""{"custom_route":{${hint}"instructions":"Custom instruction","criteria":{"review":"Custom review"},"extension":42}}"""
      )
      val json = customRequest.toJson
      val wanted = """{"state":{"task_text":"Synthetic change"},"model":"jev-latest","questions":{
        "custom_route":{"type":"choice","instructions":"Custom instruction","criteria":{"review":"Custom review"},"extension":42}}}"""
      assertEnvelope(json, wanted)
      assertEnvelope(summon[SOSchema[CustomQuestions]].encodeRequest(customRequest), wanted)
      val provider = new RecordingProvider
      // Response validation may fail for this alternate schema, but the actual transport must receive the same wire.
      run(provider.evaluate(customRequest).either)
      assertEquals(provider.sent.size, 1)
      assertEnvelope(provider.sent.head, wanted)
    }
  }

  for (name, encoded) <- List(
      "not an object" -> "[]",
      "missing question" -> "{}",
      "unknown question" -> """{"other":{}}""",
      "non-object payload" -> """{"custom_route":false}""",
      "conflicting hint" -> """{"custom_route":{"type":"score"}}""",
      "non-string hint" -> """{"custom_route":{"type":null}}""",
      "duplicate hint" -> """{"custom_route":{"type":"choice","type":"choice"}}""",
      "duplicate question" -> """{"custom_route":{},"custom_route":{}}"""
    )
  do
    test(s"malformed custom encoding: $name fails explicitly before provider transport") {
      given JsonEncoder[CustomQuestions] = customEncoder(encoded)
      intercept[IllegalArgumentException](customRequest.toJson)
      intercept[IllegalArgumentException](summon[SOSchema[CustomQuestions]].encodeRequest(customRequest))
      val provider = new RecordingProvider
      assertEquals(
        run(provider.evaluate(customRequest).either),
        Left(YallmoError.ProtocolFailure("Invalid typed System One request encoding"))
      )
      assertEquals(provider.sent, Vector.empty)
    }

  private final case class DuplicateQuestions(
      @jsonField("same") first: SystemOneQuestion.Noul,
      @jsonField("same") second: SystemOneQuestion.Noul
  ) derives SOSchema

  test("duplicate schema aliases cannot manufacture an ambiguous typed request") {
    given JsonEncoder[DuplicateQuestions] =
      JsonEncoder[Map[String, SystemOneQuestion]].contramap(q => Map("same" -> q.first))
    val duplicate =
      SORequest(State("Synthetic change"), "jev-latest", DuplicateQuestions(questions.yes, questions.bare))
    intercept[IllegalArgumentException](duplicate.toJson)
    val provider = new RecordingProvider
    assert(run(provider.evaluate(duplicate).either).isLeft)
    assertEquals(provider.sent, Vector.empty)
  }

  test("direct typed encoding requires SOSchema rather than an arbitrary question object") {
    val errors = typeCheckErrors("""{
      import fmgp.yallmo.model.som.*
      import zio.json.*
      final case class Arbitrary(value: Int) derives JsonEncoder
      SORequest("state", "jev-latest", Arbitrary(1)).toJson
    }""")
    assert(errors.nonEmpty)
    assert(errors.exists(_.message.contains("SOSchema")))
  }
}
