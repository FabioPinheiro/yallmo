package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.YallmoError
import zio.{Config as _, *}
import zio.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileAlreadyExistsException, Files, NoSuchFileException, Path}
import java.nio.file.attribute.PosixFilePermissions

/** Reads a single private JSON configuration file, initializing an empty catalog when it is missing.
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
    * @param initialUserName
    *   operator name for a newly initialized file; ignored when an existing file is loaded
    */
  def loadOrInitialize(path: Path, initialUserName: String): IO[YallmoError, Config] =
    for {
      json <- ZIO
        .attemptBlocking {
          val target = path.toAbsolutePath.normalize
          try Files.readString(target, UTF_8)
          catch {
            case _: NoSuchFileException =>
              val initial = Config(initialUserName)
              if initialUserName.trim.isEmpty then throw IllegalArgumentException("Missing initial user name")
              val parent = target.getParent
              Files.createDirectories(parent)
              val posix = Files.getFileStore(parent).supportsFileAttributeView("posix")
              val attributes =
                if posix then Seq(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                else Seq.empty
              val temporary = Files.createTempFile(parent, ".yallmo-config-", ".json", attributes*)
              try {
                Files.writeString(temporary, initial.toJsonPretty + "\n", UTF_8)
                // Creating a hard link publishes the complete file atomically and cannot replace an existing target.
                try Files.createLink(target, temporary)
                catch { case _: FileAlreadyExistsException => () }
                Files.readString(target, UTF_8)
              } finally Files.deleteIfExists(temporary)
          }
        }
        .mapError(_ => YallmoError.InvalidConfig("Unable to read or initialize the configuration file"))
      config <- ZIO.fromEither(
        json
          .fromJson[Config]
          .left
          .map(error =>
            YallmoError.InvalidConfig(s"Invalid configuration JSON; check its structure and field types. Error: $error")
          )
      )
      _ <- ZIO.fromEither(validate(config))
    } yield config

  private def validate(config: Config): Either[YallmoError, Unit] =
    for {
      _ <- Either.cond(config.userName.trim.nonEmpty, (), YallmoError.InvalidConfig("userName must not be blank"))
      _ <- Either.cond(
        config.browserAudioPort >= 1 && config.browserAudioPort <= 65535,
        (),
        YallmoError.InvalidConfig("browserAudioPort must be between 1 and 65535")
      )
      _ <- Either.cond(
        config.defaultAgentId.forall(config.agents.contains),
        (),
        YallmoError.InvalidConfig("defaultAgentId must reference a configured agent")
      )
      _ <- Either.cond(
        config.agents.values.forall(_.providerIds.forall(config.providers.contains)),
        (),
        YallmoError.InvalidConfig("Agent providerIds must reference configured providers")
      )
    } yield ()
}
