package fmgp.yallmo

import fmgp.yallmo.tool.FunctionKey
import fmgp.yallmo.model.llm.*
import munit.FunSuite
import zio.Chunk

final class LlmModelsSuite extends FunSuite:
  test("tool definitions reject blank tool and parameter names") {
    assertEquals(LlmToolDefinition(FunctionKey.unsafe(""), "description").validate, Left("Tool name must not be empty"))
    assertEquals(
      LlmToolDefinition(
        FunctionKey.unsafe("location"),
        "description",
        Chunk(LlmToolParameter("", "value", ToolSchemaType.String))
      ).validate,
      Left("Tool 'location' contains a parameter with an empty name")
    )
  }

  test("tool definitions accept a valid schema") {
    assertEquals(
      LlmToolDefinition(
        FunctionKey.unsafe("location"),
        "Get a location",
        Chunk(LlmToolParameter("city", "City name", ToolSchemaType.String))
      ).validate,
      Right(())
    )
  }
