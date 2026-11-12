package fmgp.yallmo.tool

import zio.Chunk

/** A tool exposes a set of complete callable function definitions. */
trait ToolDefinition {
  def name: ToolKey
  def functions: Set[FunctionToolDefinition]
}

object ToolDefinition {
  private final case class Defined(name: ToolKey, functions: Set[FunctionToolDefinition]) extends ToolDefinition

  def apply(name: ToolKey, functions: Set[FunctionToolDefinition]): ToolDefinition = Defined(name, functions)

  /** Retain declared functions that have available implementations, using their bound schemas. */
  def available(tools: Chunk[ToolDefinition], functions: Chunk[FunctionToolDefinition]): Chunk[ToolDefinition] = {
    val available = functions.map(function => function.name -> function).toMap
    tools.flatMap { tool =>
      val selected = tool.functions.flatMap(function => available.get(function.name))
      if selected.isEmpty then Chunk.empty
      else if selected == tool.functions then Chunk.single(tool)
      else Chunk.single(ToolDefinition(tool.name, selected))
    }
  }
}
