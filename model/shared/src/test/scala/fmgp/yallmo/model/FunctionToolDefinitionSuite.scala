package fmgp.yallmo.model

import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.tool.FunctionToolDefinition
import munit.FunSuite
import zio.Chunk

class FunctionToolDefinitionSuite extends FunSuite {
  private def validateValue(json: String, schema: JsonSchema): Either[String, Unit] =
    FunctionToolDefinition.validateArgument(
      s"""{"value":$json}""",
      JsonSchema.ObjectSchema(Map("value" -> schema), required = Set("value"))
    )

  test("validates all primitive schema types") {
    assertEquals(validateValue("\"text\"", JsonSchema.StringSchema()), Right(()))
    assertEquals(validateValue("1.5", JsonSchema.NumberSchema()), Right(()))
    assertEquals(validateValue("true", JsonSchema.BooleanSchema()), Right(()))
    assertEquals(validateValue("1.0", JsonSchema.IntegerSchema()), Right(()))
    assertEquals(validateValue("1e3", JsonSchema.IntegerSchema()), Right(()))
  }

  test("integer validation preserves precision beyond Double") {
    assertEquals(validateValue("9007199254740993", JsonSchema.IntegerSchema()), Right(()))
    assertEquals(
      validateValue("9007199254740992.1", JsonSchema.IntegerSchema()),
      Left("$.value must be an integer")
    )
    assertEquals(validateValue("1.5", JsonSchema.IntegerSchema()), Left("$.value must be an integer"))
  }

  test("rejects mismatched primitive types and null") {
    val cases = Seq(
      ("1", JsonSchema.StringSchema(), "a string"),
      ("\"1\"", JsonSchema.NumberSchema(), "a number"),
      ("true", JsonSchema.IntegerSchema(), "an integer"),
      ("0", JsonSchema.BooleanSchema(), "a boolean"),
      ("null", JsonSchema.StringSchema(), "a string")
    )
    cases.foreach { case (json, schema, expectedType) =>
      assertEquals(validateValue(json, schema), Left(s"$$.value must be $expectedType"))
    }
  }

  test("validates string enums") {
    val schema = JsonSchema.StringSchema(allowedValues = Chunk("one", "two"))
    assertEquals(validateValue("\"one\"", schema), Right(()))
    assertEquals(validateValue("\"other\"", schema), Left("$.value must be one of one, two"))
  }

  test("validates arrays with nested error paths") {
    val schema = JsonSchema.ArraySchema(JsonSchema.IntegerSchema())
    assertEquals(validateValue("[1,2,3]", schema), Right(()))
    assertEquals(validateValue("[1,2.5]", schema), Left("$.value[1] must be an integer"))
    assertEquals(validateValue("{}", schema), Left("$.value must be an array"))
  }

  test("validates nested objects and required properties") {
    val schema = JsonSchema.ObjectSchema(Map("name" -> JsonSchema.StringSchema()), required = Set("name"))
    assertEquals(validateValue("{\"name\":\"ok\"}", schema), Right(()))
    assertEquals(validateValue("{}", schema), Left("$.value is missing required properties: name"))
    assertEquals(validateValue("{\"name\":false}", schema), Left("$.value.name must be a string"))
    assertEquals(validateValue("[]", schema), Left("$.value must be an object"))
  }

  test("rejects unsupported properties unless additional properties are allowed") {
    val schema: JsonSchema.ObjectSchema = JsonSchema.ObjectSchema(Map.empty)
    assertEquals(validateValue("{\"extra\":1}", schema), Left("$.value contains unsupported properties: extra"))
    assertEquals(validateValue("{\"extra\":1}", schema.copy(additionalProperties = true)), Right(()))
  }

  test("allows missing optional properties") {
    val schema: JsonSchema.ObjectSchema = JsonSchema.ObjectSchema(Map("optional" -> JsonSchema.StringSchema()))
    assertEquals(FunctionToolDefinition.validateArgument("{}", schema), Right(()))
  }

  test("rejects malformed JSON and non-object arguments") {
    val schema: JsonSchema.ObjectSchema = JsonSchema.ObjectSchema(Map.empty)
    assert(FunctionToolDefinition.validateArgument("{", schema).isLeft)
    assertEquals(FunctionToolDefinition.validateArgument("null", schema), Left("$ must be an object"))
  }
}
