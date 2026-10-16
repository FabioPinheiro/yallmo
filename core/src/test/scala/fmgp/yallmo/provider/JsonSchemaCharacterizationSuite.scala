package fmgp.yallmo.provider

import fmgp.yallmo.model.FunctionToolDefinition
import fmgp.yallmo.model.llm.*
import munit.FunSuite
import zio.Chunk

abstract class JsonSchemaCharacterizationSuite extends FunSuite:
  protected def tool(definition: FunctionToolDefinition): ujson.Obj
  protected def expectedTool(parameters: ujson.Value): ujson.Obj
  protected def parameters(value: ujson.Value): ujson.Value

  private def definition(schema: JsonSchema.ObjectSchema): FunctionToolDefinition =
    FunctionToolDefinition("inspect", "Inspect inputs", schema)

  private val nestedSchema = JsonSchema.ObjectSchema(
    Map(
      "z" -> JsonSchema.ArraySchema(
        JsonSchema.ObjectSchema(
          Map("choice" -> JsonSchema.StringSchema(Some("Choice"), Chunk("second", "first"))),
          additionalProperties = true,
          description = Some("Nested object")
        ),
        Some("Rows")
      ),
      "a" -> JsonSchema.BooleanSchema()
    ),
    required = Set("z", "a"),
    description = Some("Root")
  )

  private def expectedNested: ujson.Value = ujson.read(
    """{
      |"type":"object","description":"Root","additionalProperties":false,"required":["a","z"],
      |"properties":{
      |  "a":{"type":"boolean"},
      |  "z":{"type":"array","description":"Rows","items":{
      |    "type":"object","description":"Nested object","additionalProperties":true,
      |    "properties":{"choice":{"type":"string","description":"Choice","enum":["second","first"]}}
      |  }}
      |}}""".stripMargin
  )

  for description <- Seq(None, Some("Description"), Some("")) do
    val variants = Seq(
      ("string", JsonSchema.StringSchema(description), """{"type":"string"}"""),
      ("enum", JsonSchema.StringSchema(description, Chunk("b", "a")), """{"type":"string","enum":["b","a"]}"""),
      ("number", JsonSchema.NumberSchema(description), """{"type":"number"}"""),
      ("integer", JsonSchema.IntegerSchema(description), """{"type":"integer"}"""),
      ("boolean", JsonSchema.BooleanSchema(description), """{"type":"boolean"}"""),
      (
        "array",
        JsonSchema.ArraySchema(JsonSchema.StringSchema(), description),
        """{"type":"array","items":{"type":"string"}}"""
      ),
      (
        "object",
        JsonSchema.ObjectSchema(Map.empty, description = description),
        """{"type":"object","properties":{},"additionalProperties":false}"""
      )
    )
    for (name, schema, json) <- variants do
      test(s"encodes $name with description $description and preserves the tool envelope") {
        val property = ujson.read(json)
        description.foreach(property("description") = _)
        val expected = ujson.Obj(
          "type" -> "object",
          "properties" -> ujson.Obj("value" -> property),
          "additionalProperties" -> false
        )
        val actual = tool(definition(JsonSchema.ObjectSchema(Map("value" -> schema))))
        assertEquals(parameters(actual), expected)
        assertEquals(actual, expectedTool(expected))
      }

  test("returns fresh schemas including nested objects and arrays on every invocation") {
    val input = definition(nestedSchema)
    val first = parameters(tool(input))
    val second = parameters(tool(input))
    first("description") = "Changed"
    first("required").arr.clear()
    val nested = first("properties")("z")("items")
    nested("additionalProperties") = false
    nested("properties")("choice")("enum").arr.clear()
    nested("properties")("extra") = ujson.Obj("type" -> "number")
    assertEquals(second, expectedNested)
    assertEquals(parameters(tool(input)), expectedNested)
  }

  test("encodes nested arrays and objects, sorted required names, and both additionalProperties values") {
    val actual = tool(definition(nestedSchema))
    assertEquals(parameters(actual), expectedNested)
    assertEquals(actual, expectedTool(expectedNested))
  }
