package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ToolDefinition, ToolKey}

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.config.AgentCapabilities
import fmgp.yallmo.model.tool.{SdlcToolDefinition, ScalamodeToolDefinition}
import munit.FunSuite
import zio.Chunk

final class ToolFunctionsSuite extends FunSuite {
  private val read = FunctionToolDefinition(FunctionKey.unsafe("inspect"), "Read")
  private val write = FunctionToolDefinition(FunctionKey.unsafe("update"), "Write")
  private val notebook = FunctionToolDefinition(FunctionKey.unsafe("notebook"), "Notebook")
  private val planning = new ToolDefinition {
    val name: ToolKey = ToolKey.Sdlc
    val functions: Set[FunctionToolDefinition] = Set(read, write)
  }
  private val coaching = ToolDefinition(ToolKey.Coach, Set(notebook))
  private val context = StaticContext[Any](
    "system",
    functions = Chunk(read, write, notebook),
    tools = Chunk(planning, coaching)
  )

  test("a tool exposes complete function definitions, including the schemas selected at binding") {
    assertEquals(planning.functions, Set(read, write))
    assertEquals(SdlcToolDefinition.functions.size, 5)
    assert(SdlcToolDefinition.functions.contains(SdlcToolDefinition.ReadTaskDefinition))
    assert(SdlcToolDefinition.functions.forall(_.validate.isRight))
    assertEquals(ToolDefinition.available(context.tools, Chunk(read, write)), Chunk(planning))
    val bound = read.copy(description = "Read from the selected private root")
    assertEquals(ToolDefinition.available(context.tools, Chunk(bound)).head.functions, Set(bound))
    assertEquals(
      ToolDefinition
        .available(Chunk(ScalamodeToolDefinition), Chunk(ScalamodeToolDefinition.Filesystem))
        .head
        .functions,
      Set(ScalamodeToolDefinition.Filesystem)
    )
  }

  test("tool and function restrictions narrow the same definitions used in provider requests") {
    val policy = AgentCapabilities(tools = Some(Set(ToolKey.Sdlc)), functions = Some(Set(read.name, notebook.name)))
    val effective = policy.resolve(AgentId("policy"), context).toOption.get
    assertEquals(effective.functions, Chunk(read))
    assertEquals(effective.tools.map(_.functions), Chunk(Set(read)))
    val request = AgentTurnRequest(
      "model",
      Chunk(MessagePayload.Instructions("system")),
      tools = context.functions,
      toolGroups = context.tools,
      parallelToolCalls = true
    )
    val filtered = policy.filterRequest(request)
    assertEquals(filtered.tools, effective.functions)
    assertEquals(filtered.toolGroups, effective.tools)
    val disabled = policy.copy(tools = Some(Set.empty))
    assert(disabled.restrict(context).functions.isEmpty)
    assert(!disabled.filterRequest(request).parallelToolCalls)
    assert(policy.copy(functions = None).filterRequest(request.copy(toolGroups = Chunk.empty)).tools.isEmpty)
  }
}
