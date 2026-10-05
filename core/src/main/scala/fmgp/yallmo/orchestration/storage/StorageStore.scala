package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.{MessageData, MessageId}
import fmgp.yallmo.model.YallmoError
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import zio.*

/** Append-only object database for one storage root. It persists messages only. */
private[storage] trait StorageStore {
  def get(id: MessageId): UIO[Option[DecodedArtifact]]
  def put(value: MessageData): IO[YallmoError, StoredArtifact[MessageData]]
}

private[storage] object StorageStore {
  private val ObjectsFileName = "objects.jsonl"

  def open(root: Path): IO[YallmoError, StorageStore] =
    for {
      _ <- ensureLayout(root)
      index <- load(root)
      ref <- Ref.make(index)
    } yield LiveStorageStore(root, ref)

  private final case class LiveStorageStore(root: Path, index: Ref[Map[MessageId, DecodedArtifact]])
      extends StorageStore {
    override def get(id: MessageId): UIO[Option[DecodedArtifact]] = index.get.map(_.get(id))

    override def put(value: MessageData): IO[YallmoError, StoredArtifact[MessageData]] = {
      val stored = CanonicalArtifactHash.store(value)
      withWriterChannel(root) { channel =>
        for {
          loaded <- load(root)
          _ <- validateInsert(loaded, DecodedArtifact.Msg(stored))
          _ <- loaded.get(stored.id) match
            case Some(_) => index.set(loaded)
            case None    =>
              for {
                line <- ZIO.fromEither(StorageJson.envelopeJson(DecodedArtifact.Msg(stored)).left.map(invalid))
                _ <- appendLine(channel, line, objectsPath(root))
                _ <- index.set(loaded.updated(stored.id, DecodedArtifact.Msg(stored)))
              } yield ()
        } yield stored
      }
    }
  }

  private def ensureLayout(root: Path): IO[YallmoError, Unit] =
    ZIO
      .attemptBlocking {
        Files.createDirectories(root)
        restrictPermissions(root, isDirectory = true)
        val objects = objectsPath(root)
        if Files.notExists(objects) then Files.createFile(objects)
        restrictPermissions(objects, isDirectory = false)
      }
      .mapError(error => failed("create storage object-store layout", error))

  private def load(root: Path): IO[YallmoError, Map[MessageId, DecodedArtifact]] =
    ZIO
      .attemptBlocking(Files.readAllLines(objectsPath(root), StandardCharsets.UTF_8))
      .mapError(error => failed("read storage objects", error))
      .flatMap { lines =>
        ZIO.foldLeft(lines.asScala.zipWithIndex)(Map.empty[MessageId, DecodedArtifact]) {
          case (index, (line, offset)) =>
            if line.isBlank then ZIO.succeed(index)
            else
              ZIO
                .fromEither(
                  StorageJson.decodeEnvelope(line).left.map(details => invalid(s"record ${offset + 1}: $details"))
                )
                .flatMap { artifact =>
                  CanonicalArtifactHash.verify(artifact) match
                    case Left((stored, computed)) =>
                      ZIO.fail(invalid(s"record ${offset + 1}: hash mismatch $stored != $computed"))
                    case Right(_) => validateInsert(index, artifact).as(index.updated(artifact.id, artifact))
                }
        }
      }

  private def validateInsert(
      index: Map[MessageId, DecodedArtifact],
      artifact: DecodedArtifact
  ): IO[YallmoError, Unit] =
    index.get(artifact.id) match
      case Some(existing) if existing == artifact => ZIO.unit
      case Some(_)                                => ZIO.fail(invalid(s"conflicting object ${artifact.id}"))
      case None                                   =>
        artifact match
          case DecodedArtifact.Msg(stored) =>
            ZIO
              .foreachDiscard(stored.value.parentId) { parent =>
                ZIO.fail(invalid(s"message parent $parent is missing")).when(!index.contains(parent))
              }

  private def withWriterChannel[A](root: Path)(
      use: FileChannel => IO[YallmoError, A]
  ): IO[YallmoError, A] =
    ZIO.acquireReleaseWith(
      ZIO
        .attemptBlocking(FileChannel.open(objectsPath(root), StandardOpenOption.CREATE, StandardOpenOption.WRITE))
        .mapError(error => failed("open storage writer", error))
    )(channel => ZIO.attemptBlocking(channel.close()).orDie)(use)

  private def appendLine(channel: FileChannel, line: String, path: Path): IO[YallmoError, Unit] =
    ZIO
      .attemptBlocking {
        val buffer = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8))
        channel.position(channel.size)
        while buffer.hasRemaining do channel.write(buffer)
        channel.force(true)
      }
      .mapError(error => failed(s"append storage object at $path", error))

  private def objectsPath(root: Path): Path = root.resolve(ObjectsFileName)

  private def invalid(message: String): YallmoError =
    YallmoError.StorageFailure(message, IllegalArgumentException(message))

  private def failed(operation: String, cause: Throwable): YallmoError = YallmoError.StorageFailure(operation, cause)

  private def restrictPermissions(path: Path, isDirectory: Boolean): Unit =
    val permissions = if isDirectory then "rwx------" else "rw-------"
    if Files.getFileStore(path).supportsFileAttributeView("posix") then
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
}
