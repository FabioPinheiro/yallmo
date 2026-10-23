package fmgp.yallmo.tool

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.model.llm.JsonSchema
import zio.*

import java.nio.file.Path
import fmgp.yallmo.orchestration.{AgentToolDefinition, ExternalToolCall, ExternalToolResult}

/** Inspects a Git work tree without accepting arbitrary Git arguments or mutating repository state. */
final class RepositoryChangesTool(workspace: Path = Path.of("").toAbsolutePath.normalize) extends AgentTool[GitClient] {
  override val definition: AgentToolDefinition = RepositoryChangesTool.definition

  override def execute(call: ExternalToolCall): ZIO[GitClient, YallmoError, ExternalToolResult] =
    view(call).flatMap { selectedView =>
      val operation = selectedView match
        case RepositoryChangesTool.View.Status   => ZIO.serviceWithZIO[GitClient](_.status(workspace))
        case RepositoryChangesTool.View.Unstaged => ZIO.serviceWithZIO[GitClient](_.unstagedDiff(workspace))
        case RepositoryChangesTool.View.Staged   => ZIO.serviceWithZIO[GitClient](_.stagedDiff(workspace))

      operation
        .mapError(error => RepositoryChangesTool.toYallmoError(definition.key, error))
        .map(RepositoryChangesTool.render(selectedView, _))
        .map(ExternalToolResult.apply)
    }

  private def view(call: ExternalToolCall): IO[YallmoError, RepositoryChangesTool.View] =
    ZIO
      .fromEither(
        scala.util
          .Try(ujson.read(call.argumentsJson)("view").str)
          .toEither
          .left
          .map(_.getMessage)
          .flatMap(RepositoryChangesTool.View.parse)
      )
      .mapError(YallmoError.InvalidToolArguments(call.toolKey, _))
}

object RepositoryChangesTool {
  enum View(val value: String) {
    case Status extends View("status")
    case Unstaged extends View("unstaged")
    case Staged extends View("staged")
  }

  object View {
    def parse(value: String): Either[String, View] =
      View.values.find(_.value == value).toRight(s"view must be one of ${View.values.map(_.value).mkString(", ")}")
  }

  val definition: AgentToolDefinition = AgentToolDefinition(
    key = "repositoryChanges",
    description =
      "Inspect Git repository status, unstaged changes, or staged changes without modifying the repository.",
    inputSchema = JsonSchema.ObjectSchema(
      Map(
        "view" -> JsonSchema.StringSchema(
          Some("Repository view: status, unstaged (working tree), or staged (index ready to commit)"),
          Chunk(View.Status.value, View.Unstaged.value, View.Staged.value)
        )
      ),
      required = Set("view"),
      additionalProperties = false
    )
  )

  private[tool] def render(view: View, result: CommandOutput): String =
    ujson.write(
      ujson.Obj(
        "view" -> ujson.Str(view.value),
        "exitCode" -> ujson.Num(result.exitCode),
        "output" -> ujson.Str(result.output),
        "truncated" -> ujson.Bool(result.truncated)
      )
    )

  private def toYallmoError(toolKey: String, error: GitError): YallmoError =
    error match
      case GitError.Unavailable(_)   => YallmoError.ToolUnavailable(toolKey, error.getMessage)
      case GitError.CommandFailed(_) => YallmoError.ToolExecutionFailed(toolKey, error.getMessage, error)
}
