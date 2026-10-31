package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.{AgentId, YallmoError}
import zio.{Config as _, *}
import zio.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileAlreadyExistsException, Files, NoSuchFileException, Path}
import java.nio.file.attribute.PosixFilePermissions

/** Reads a single private JSON or YAML configuration file, initializing an empty catalog when it is missing.
  *
  * No environment references are resolved and no runtime instances are constructed. Error messages never include file
  * contents, decoder diagnostics or credentials. Existing files are never rewritten by loading.
  */
object ConfigFile {

  /** Loads/validates the file, or atomically publishes a new owner-readable configuration without overwriting a winner
    * from another process. Initial publication requires filesystem hard-link support; failure leaves the target alone.
    *
    * @param path
    *   explicitly selected configuration file; parent directories are created only when initializing a missing file
    */
  def loadOrInitialize(path: Path): IO[YallmoError, Config] =
    for {
      text <- ZIO
        .attemptBlocking {
          val target = path.toAbsolutePath.normalize
          try Files.readString(target, UTF_8)
          catch {
            case _: NoSuchFileException =>
              val initial = Config()
              val parent = target.getParent
              Files.createDirectories(parent)
              val posix = Files.getFileStore(parent).supportsFileAttributeView("posix")
              val attributes =
                if posix then Seq(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                else Seq.empty
              val temporary = Files.createTempFile(parent, ".yallmo-config-", ".json", attributes*)
              try {
                val contents =
                  if ConfigDocument.isYaml(path) then
                    ConfigDocument
                      .yaml(initial.toJsonAST.toOption.get.asInstanceOf[zio.json.ast.Json.Obj])
                      .fold(_ => throw new IllegalArgumentException("Unable to encode configuration"), identity)
                  else initial.toJsonPretty + "\n"
                Files.writeString(temporary, contents, UTF_8)
                // Creating a hard link publishes the complete file atomically and cannot replace an existing target.
                try Files.createLink(target, temporary)
                catch { case _: FileAlreadyExistsException => () }
                Files.readString(target, UTF_8)
              } finally Files.deleteIfExists(temporary)
          }
        }
        .mapError(_ => YallmoError.InvalidConfig("Unable to read or initialize the configuration file"))
      document <- ZIO.fromEither(ConfigDocument.parse(path, text))
      _ <- ZIO
        .logWarning(
          "Ignoring removed configuration keys userName, defaultAgentId, transcriberAgent; use REPL flags --user-name, --agent, --transcriber-agent instead"
        )
        .when(
          document.json.fields.exists { case (name, _) =>
            Set("userName", "defaultAgentId", "transcriberAgent").contains(name)
          }
        )
    } yield document.config

  /** Explicit JSON → YAML migration. Reads and validates an existing source, preserves unknown fields, and privately
    * publishes a new file. Neither the source nor an existing destination is ever overwritten; loading never migrates.
    */
  def migrateJsonToYaml(source: Path, destination: Path): IO[YallmoError, Unit] =
    for {
      _ <- ZIO
        .fail(YallmoError.InvalidConfig("Migration requires a JSON source and a .yaml or .yml destination"))
        .unless(
          Option(source.getFileName).exists(_.toString.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) &&
            ConfigDocument.isYaml(destination)
        )
      text <- ZIO
        .attemptBlocking(Files.readString(source, UTF_8))
        .mapError(_ => YallmoError.InvalidConfig("Unable to read migration source"))
      document <- ZIO.fromEither(ConfigDocument.parse(source, text))
      yaml <- ZIO.fromEither(ConfigDocument.yaml(document.json))
      _ <- ZIO
        .attemptBlocking {
          val target = destination.toAbsolutePath.normalize
          val parent = target.getParent
          Files.createDirectories(parent)
          val attributes =
            if Files.getFileStore(parent).supportsFileAttributeView("posix") then
              Seq(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            else Seq.empty
          val temporary = Files.createTempFile(parent, ".yallmo-migration-", ".yaml", attributes*)
          try {
            Files.writeString(temporary, yaml, UTF_8)
            Files.createLink(target, temporary)
            ()
          } finally Files.deleteIfExists(temporary)
        }
        .mapError(_ =>
          YallmoError.InvalidConfig("Unable to publish YAML migration; destination must not already exist")
        )
    } yield ()

  /** Validate capability IDs once the application's complete, unfiltered local catalogs are available. */
  def validateCapabilities[R](
      config: Config,
      available: fmgp.yallmo.orchestration.StaticContext[R]
  ): Either[YallmoError, Unit] =
    config.agents.toList.sortBy(pair => AgentId.value(pair._1)).foldLeft[Either[YallmoError, Unit]](Right(())) {
      case (result, (id, definition)) =>
        result.flatMap(_ => definition.capabilities.resolve(id, available).map(_ => ()))
    }

  def validate(config: Config): Either[YallmoError, Unit] =
    for {
      _ <- Either.cond(
        config.browserAudioPort >= 1 && config.browserAudioPort <= 65535,
        (),
        YallmoError.InvalidConfig("browserAudioPort must be between 1 and 65535")
      )
      _ <- Either.cond(
        config.agents.values.forall(_.filesystem.forall(_.root.trim.nonEmpty)),
        (),
        YallmoError.InvalidConfig("Agent filesystem.root must not be blank")
      )
      _ <- Either.cond(
        config.agents.values.forall(_.providerIds.forall(config.providers.contains)),
        (),
        YallmoError.InvalidConfig("Agent providerIds must reference configured providers")
      )
    } yield ()
}
