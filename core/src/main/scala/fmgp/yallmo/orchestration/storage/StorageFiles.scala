package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.orchestration.{SessionId}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import zio.*
import zio.json.*

/** Filesystem representation of mutable session heads and agent membership. */
private[storage] object StorageFiles {
  private[storage] final case class SessionRef(agentIds: Chunk[AgentId], head: Option[MessageId]) derives JsonCodec

  def ensureDirectories(root: Path): IO[YallmoError, Unit] =
    ZIO
      .attemptBlocking {
        val directory = sessionsDirectory(root)
        Files.createDirectories(directory)
        restrictPermissions(directory, isDirectory = true)
      }
      .mapError(error => failed("create storage session directories", error))

  def read(root: Path, id: SessionId): IO[YallmoError, SessionRef] = {
    val path = sessionPath(root, id)
    ZIO
      .attemptBlocking(Files.readString(path, StandardCharsets.UTF_8))
      .mapError {
        case _: java.nio.file.NoSuchFileException => YallmoError.SessionNotFound(id)
        case error                                => failed(s"read session '${id.value}'", error)
      }
      .flatMap(value => ZIO.fromEither(value.fromJson[SessionRef].left.map(invalid)))
  }

  def exists(root: Path, id: SessionId): UIO[Boolean] =
    ZIO.succeed(Files.isRegularFile(sessionPath(root, id)))

  def list(root: Path): IO[YallmoError, Chunk[SessionId]] =
    ZIO
      .attemptBlocking {
        val stream = Files.list(sessionsDirectory(root))
        try
          stream.iterator.asScala.toList
            .filter(Files.isRegularFile(_))
            .map(_.getFileName.toString)
            .sorted
        finally stream.close()
      }
      .mapError(error => failed("list storage sessions", error))
      .flatMap(names =>
        ZIO.foreach(names)(name => ZIO.fromEither(SessionId.from(name).left.map(invalid))).map(Chunk.fromIterable)
      )

  def write(root: Path, id: SessionId, agentIds: Chunk[AgentId], head: Option[MessageId]): IO[YallmoError, Unit] =
    writeAtomically(sessionPath(root, id), SessionRef(agentIds, head).toJson)

  private def writeAtomically(path: Path, contents: String): IO[YallmoError, Unit] =
    val temporary = path.resolveSibling(s".${path.getFileName}.${UUID.randomUUID()}.tmp")
    ZIO
      .attemptBlocking {
        val channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
          val bytes = ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8))
          while bytes.hasRemaining do channel.write(bytes)
          channel.force(true)
        } finally channel.close()
        restrictPermissions(temporary, isDirectory = false)
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      }
      .mapError {
        case error: AtomicMoveNotSupportedException => failed(s"atomically replace session file $path", error)
        case error                                  => failed(s"write session file $path", error)
      }
      .unit

  private def sessionsDirectory(root: Path): Path = root.resolve("refs/sessions")

  private def sessionPath(root: Path, id: SessionId): Path = sessionsDirectory(root).resolve(id.value)

  private def invalid(message: String): YallmoError =
    YallmoError.StorageFailure(message, IllegalArgumentException(message))

  private def failed(operation: String, cause: Throwable): YallmoError = YallmoError.StorageFailure(operation, cause)

  private def restrictPermissions(path: Path, isDirectory: Boolean): Unit =
    val permissions = if isDirectory then "rwx------" else "rw-------"
    if Files.getFileStore(path).supportsFileAttributeView("posix") then
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
}
