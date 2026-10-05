package fmgp.yallmo.provider.openai

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Loads the paid OpenAI Platform API key without sharing Codex OAuth credentials. */
private[openai] object OpenAiTtsSecrets {
  private val ApiKey = "OPENAI_API_KEY"

  def load: Task[String] =
    System.env(ApiKey).flatMap {
      case Some(value) if value.trim.nonEmpty => ZIO.succeed(value.trim)
      case _                                  => loadFromDotEnv
    }

  private def loadFromDotEnv: Task[String] =
    ZIO
      .attemptBlocking {
        val values = dotEnvFiles.flatMap { file =>
          OpenAiCodexSecrets.parse(Files.readAllLines(file, StandardCharsets.UTF_8)).get(ApiKey)
        }
        values.lastOption
          .map(_.trim)
          .filter(_.nonEmpty)
          .getOrElse(
            throw IllegalArgumentException(s"$ApiKey is required for OpenAI TTS")
          )
      }
      .mapError(error =>
        OpenAiTtsError.MissingApiKey(
          Option(error.getMessage).filter(_.nonEmpty).getOrElse(s"$ApiKey is required for OpenAI TTS")
        )
      )

  private def dotEnvFiles: Seq[Path] = {
    val projectRoot = findProjectRoot(Path.of("").toAbsolutePath.normalize)
    Seq(projectRoot.resolve(".env"), projectRoot.resolve("server/.env")).filter(Files.isRegularFile(_))
  }

  private def findProjectRoot(start: Path): Path =
    Iterator
      .iterate(Option(start))(path => path.flatMap(value => Option(value.getParent)))
      .flatten
      .find(path => Files.exists(path.resolve("build.sbt")))
      .getOrElse(start)
}
