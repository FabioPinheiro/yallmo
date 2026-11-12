package fmgp.yallmo.tool

import fmgp.yallmo.model.YallmoError

import fmgp.yallmo.model.tool.RepositoryChangesToolDefinition

import fmgp.yallmo.orchestration.*
import zio.*

import java.nio.file.Path
import fmgp.yallmo.orchestration.{ExternalFunctionCall, ExternalFunctionResult}

/** Inspects a Git work tree without accepting arbitrary Git arguments or mutating repository state. */
final class RepositoryChangesTool(workspace: Path = Path.of("").toAbsolutePath.normalize)
    extends AgentFunction[GitClient] {
  override val definition: FunctionToolDefinition = RepositoryChangesTool.definition

  override def execute(call: ExternalFunctionCall): ZIO[GitClient, YallmoError, ExternalFunctionResult] =
    view(call).flatMap { selectedView =>
      val operation = selectedView match
        case RepositoryChangesTool.View.Status   => ZIO.serviceWithZIO[GitClient](_.status(workspace))
        case RepositoryChangesTool.View.Unstaged => ZIO.serviceWithZIO[GitClient](_.unstagedDiff(workspace))
        case RepositoryChangesTool.View.Staged   => ZIO.serviceWithZIO[GitClient](_.stagedDiff(workspace))

      operation
        .mapError(error => RepositoryChangesTool.toYallmoError(definition.name, error))
        .map(RepositoryChangesTool.render(selectedView, _))
        .map(text => ExternalFunctionResult(text))
    }

  private def view(call: ExternalFunctionCall): IO[YallmoError, RepositoryChangesTool.View] =
    ZIO
      .fromEither(
        scala.util
          .Try(ujson.read(call.argumentsJson)("view").str)
          .toEither
          .left
          .map(_.getMessage)
          .flatMap(RepositoryChangesTool.View.parse)
      )
      .mapError(YallmoError.InvalidToolArguments(call.functionKey, _))
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

  val definition: FunctionToolDefinition = RepositoryChangesToolDefinition.InspectDefinition

  private[tool] def render(view: View, result: CommandOutput): String =
    ujson.write(
      ujson.Obj(
        "view" -> ujson.Str(view.value),
        "exitCode" -> ujson.Num(result.exitCode),
        "output" -> ujson.Str(result.output),
        "truncated" -> ujson.Bool(result.truncated)
      )
    )

  private def toYallmoError(functionKey: FunctionKey, error: GitError): YallmoError =
    error match
      case GitError.Unavailable(_)   => YallmoError.ToolUnavailable(functionKey, error.getMessage)
      case GitError.CommandFailed(_) => YallmoError.ToolExecutionFailed(functionKey, error.getMessage, error)
}
