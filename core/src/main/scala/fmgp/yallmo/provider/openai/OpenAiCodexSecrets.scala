package fmgp.yallmo.provider.openai

import fmgp.yallmo.*
import fmgp.yallmo.model.YallmoError

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}

/** OAuth credentials for the single ChatGPT subscription account used by the ASAR server. */
final case class OpenAiCodexCredentials(
    accessToken: String,
    refreshToken: String,
    expiresAtMillis: Long
)

/** Reads and atomically updates only ASAR's Codex credential entries in a local `.env` file. */
final class OpenAiCodexSecrets private[openai] (envFile: Path, legacyEnvFile: Option[Path] = None) {
  import OpenAiCodexSecrets.*

  val path: Path = envFile.toAbsolutePath.normalize

  def load: Task[OpenAiCodexCredentials] =
    read(envFile).catchAll { primaryError =>
      legacyEnvFile.filter(Files.exists(_)) match {
        case Some(legacy) => read(legacy).tap(save)
        case None         => ZIO.fail(primaryError)
      }
    }

  private def read(file: Path): Task[OpenAiCodexCredentials] =
    ZIO
      .attemptBlocking {
        val values = parse(Files.readAllLines(file, StandardCharsets.UTF_8))
        OpenAiCodexCredentials(
          required(values, AccessTokenKey, file),
          required(values, RefreshTokenKey, file),
          required(values, ExpiresAtMillisKey, file).toLong
        )
      }
      .mapError(error => YallmoError.InvalidConfig(s"Unable to load OpenAI Codex credentials: ${error.getMessage}"))

  def save(credentials: OpenAiCodexCredentials): Task[Unit] =
    ZIO
      .attemptBlocking {
        val parent = Option(envFile.toAbsolutePath.getParent).getOrElse(Path.of(".").toAbsolutePath)
        Files.createDirectories(parent)
        val existing =
          if Files.exists(envFile) then Files.readAllLines(envFile, StandardCharsets.UTF_8) else java.util.List.of()
        val replacements = Map(
          AccessTokenKey -> credentials.accessToken,
          RefreshTokenKey -> credentials.refreshToken,
          ExpiresAtMillisKey -> credentials.expiresAtMillis.toString
        )
        val retained = existing.toArray(new Array[String](existing.size())).toList.filterNot { line =>
          replacements.keys.exists(key => line.trim.startsWith(s"$key="))
        }
        val content = (retained ++ replacements.toList.sortBy(_._1).map { case (key, value) => s"$key=$value" })
          .mkString("\n") + "\n"
        val temporary = Files.createTempFile(parent, ".asar-codex-", ".env")
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        setOwnerOnlyPermissions(temporary)
        try Files.move(temporary, envFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        catch {
          case _: AtomicMoveNotSupportedException => Files.move(temporary, envFile, StandardCopyOption.REPLACE_EXISTING)
        }
        setOwnerOnlyPermissions(envFile)
      }
      .mapError(error => YallmoError.InvalidConfig(s"Unable to save OpenAI Codex credentials: ${error.getMessage}"))

  private def required(values: Map[String, String], key: String, file: Path): String =
    values
      .get(key)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalArgumentException(s"Missing $key in $file"))

  private def setOwnerOnlyPermissions(path: Path): Unit =
    try Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    catch {
      case _: UnsupportedOperationException => ()
    }
}

object OpenAiCodexSecrets {
  val AccessTokenKey = "OPENAI_CODEX_ACCESS_TOKEN"
  val RefreshTokenKey = "OPENAI_CODEX_REFRESH_TOKEN"
  val ExpiresAtMillisKey = "OPENAI_CODEX_TOKEN_EXPIRES_AT"

  private val projectRoot = findProjectRoot(Path.of("").toAbsolutePath.normalize)
  val live: OpenAiCodexSecrets =
    new OpenAiCodexSecrets(projectRoot.resolve(".env"), Some(projectRoot.resolve("server/.env")))

  private[openai] def apply(envFile: Path): OpenAiCodexSecrets = new OpenAiCodexSecrets(envFile)

  private def findProjectRoot(start: Path): Path =
    Iterator
      .iterate(Option(start))(path => path.flatMap(value => Option(value.getParent)))
      .flatten
      .find(path => Files.exists(path.resolve("build.sbt")))
      .getOrElse(start)

  private[openai] def parse(lines: java.util.List[String]): Map[String, String] =
    lines
      .toArray(new Array[String](lines.size()))
      .iterator
      .flatMap { line =>
        line.trim.split("=", 2).toList match {
          case key :: value :: Nil if key.nonEmpty && !key.startsWith("#") => Some(key.trim -> value.trim)
          case _                                                           => None
        }
      }
      .toMap
}
