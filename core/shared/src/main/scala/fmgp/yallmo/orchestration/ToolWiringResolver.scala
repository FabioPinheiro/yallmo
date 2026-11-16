package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.orchestration.config.AgentCapabilities
import fmgp.yallmo.tool.{FunctionToolDefinition, ToolDefinition}
import zio.*

/** The resolution boundary, shared by the catalog and focused offline composition tests. */
private[orchestration] object ToolWiringResolver {
  final case class Result(context: StaticContext[Any], functions: Chunk[AgentFunction[Any]])

  def resolve(
      id: AgentId,
      context: StaticContext[Any],
      functions: Chunk[AgentFunction[Any]],
      wiring: ToolWiring,
      policy: AgentCapabilities,
      declaredPolicies: Chunk[AgentCapabilities]
  ): IO[YallmoError, Result] = {
    def asContext(tools: Seq[AgentTool[Any]]): StaticContext[Any] =
      context.copy(
        tools = Chunk.fromIterable(tools.map(_.definition)),
        functions = Chunk.fromIterable(tools.flatMap(_.functions.map(_.definition)))
      )

    for {
      _ <- ZIO.fromEither(validateCatalog(context.tools, functions.map(_.definition)))
      base = AgentTool.group(context.tools, functions)
      _ <- ZIO.fromEither(validateTools(base))
      filtered = policy.restrict(asContext(base))
      input = AgentTool.group(
        filtered.tools,
        base.flatMap(_.functions).filter(f => filtered.functions.contains(f.definition))
      )
      output <- wiring(input)
      _ <- ZIO.fromEither(validateTools(output))
      // Remember denied base IDs for policy validation, never for wiring input.
      known = asContext(base)
      available = asContext(output).copy(skills = filtered.skills)
      accumulated = known.copy(
        tools = (known.tools ++ available.tools).distinctBy(_.name),
        functions = (known.functions ++ available.functions).distinctBy(_.name)
      )
      _ <- ZIO.foreachDiscard(declaredPolicies :+ policy)(p => ZIO.fromEither(p.validateIds(id, accumulated)))
    } yield Result(available, Chunk.fromIterable(output.flatMap(_.functions)))
  }

  private def unique[A](kind: String, keys: Iterable[A]): Either[YallmoError, Unit] = {
    val duplicates = keys.groupBy(identity).collect { case (key, occurrences) if occurrences.size > 1 => key.toString }
    Either.cond(
      duplicates.isEmpty,
      (),
      YallmoError.InvalidConfig(
        s"Duplicate $kind keys in tool wiring catalog: ${duplicates.toList.sorted.mkString(", ")}"
      )
    )
  }

  /** Base schemas can be service-selected variants; ownership must still come from explicit declarations. */
  private def validateCatalog(
      definitions: Seq[ToolDefinition],
      functions: Seq[FunctionToolDefinition]
  ): Either[YallmoError, Unit] =
    for {
      _ <- unique("tool", definitions.map(_.name))
      _ <- unique("function", functions.map(_.name))
      _ <- unique("declared function", definitions.flatMap(_.functions.map(_.name)))
      _ <- Either.cond(
        functions.forall(f => definitions.exists(_.functions.exists(_.name == f.name))),
        (),
        YallmoError.InvalidConfig("Tool wiring function has no declared tool membership")
      )
    } yield ()

  private def validateTools(tools: Seq[AgentTool[Any]]): Either[YallmoError, Unit] =
    for {
      _ <- validateCatalog(tools.map(_.definition), tools.flatMap(_.functions.map(_.definition)))
      _ <- Either.cond(
        tools.forall(tool => tool.definition.functions == tool.functions.map(_.definition).toSet),
        (),
        YallmoError.InvalidConfig("Tool wiring declarations and bound function implementations do not match")
      )
    } yield ()
}
