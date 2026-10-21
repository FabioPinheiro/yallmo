package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Explicit, offline recovery of stores containing redacted AudioInput records.
  *
  * Writes a new store, never edits the source. Missing recordings become context notices, not playable attachments.
  * Message IDs, descendant parent IDs and session heads are recomputed together. Legacy audio hashes cannot be
  * verified because the old writer discarded the bytes used to compute them; all current-schema hashes are verified.
  */
object RecoverLegacyAudio extends ZIOAppDefault {
  override val run =
    (for {
      args <- ZIOAppArgs.getArgs
      _ <- ZIO.fail(new IllegalArgumentException("Usage: RecoverLegacyAudio <source> <new-destination>"))
        .unless(args.size == 2)
      count <- recover(Path.of(args(0)), Path.of(args(1)))
      _ <- Console.printLine(s"Recovered $count unavailable recording(s) into ${args(1)}; source unchanged.")
    } yield ()).exitCode

  private final case class Envelope(id: MessageId, kind: String, value: Json) derives JsonCodec
  private final case class LegacyAudio(
      bytes: Chunk[Byte],
      format: AudioFormat,
      sizeBytes: Long,
      durationSeconds: Option[Double] = None,
      description: Option[String] = None
  ) derives JsonCodec

  /** Both stores must be offline. The destination must not exist or be inside the source directory. */
  private[storage] def recover(source: Path, destination: Path): Task[Int] =
    ZIO.attemptBlocking {
      val from = source.toRealPath()
      val to = destination.toAbsolutePath.normalize()
      require(!to.startsWith(from), "destination must be outside the source store")
      require(!Files.exists(to, LinkOption.NOFOLLOW_LINKS), "destination already exists")
      // Resolve the parent too, so symlink aliases cannot place the copy inside the source.
      require(!to.getParent.toRealPath().startsWith(from), "destination must be outside the source store")
      var ids = Map.empty[MessageId, MessageId]
      var recovered = 0
      val lines = Files.readAllLines(from.resolve("objects.jsonl")).asScala.toVector.zipWithIndex.map {
        case (line, _) if line.isBlank => line
        case (line, offset) =>
          def checked[A](result: Either[String, A]): A =
            result.fold(error => throw new IllegalArgumentException(s"record ${offset + 1}: $error"), identity)
          val envelope = checked(line.fromJson[Envelope])
          require(envelope.kind == "msg", s"record ${offset + 1}: unsupported artifact kind ${envelope.kind}")
          val legacy = envelope.value.asObject.flatMap(_.get("payload")).flatMap(_.asObject)
            .flatMap(_.get("AudioInput"))
          val data = legacy match {
            case None =>
              val current = checked(envelope.value.toJson.fromJson[MessageData])
              require(Message(current).id == envelope.id, s"record ${offset + 1}: hash mismatch")
              current
            case Some(raw) =>
              val audio = checked(raw.toJson.fromJson[LegacyAudio])
              require(audio.bytes.isEmpty, s"record ${offset + 1}: inline audio bytes require manual recovery")
              require(audio.sizeBytes >= 0, s"record ${offset + 1}: invalid audio size")
              val notice: MessagePayload = MessagePayload.Context(
                "Unavailable legacy audio",
                s"Recording bytes were not retained by the old storage format. Original metadata: ${audio.toJson}"
              )
              val payload = checked(notice.toJson.fromJson[Json])
              val fields = envelope.value.asObject.get.toChunk.map {
                case ("payload", _) => "payload" -> payload
                case field          => field
              }
              recovered += 1
              checked(Json.Obj(fields).toJson.fromJson[MessageData])
          }
          val parent = data.parentId.map(id => ids.getOrElse(id,
            throw new IllegalArgumentException(s"record ${offset + 1}: missing parent $id")))
          val stored = CanonicalArtifactHash.store(data.copy(parentId = parent))
          require(ids.get(envelope.id).forall(_ == stored.id), s"record ${offset + 1}: conflicting object")
          ids = ids.updated(envelope.id, stored.id)
          checked(StorageJson.envelopeJson(DecodedArtifact.Msg(stored)))
      }
      val sessions = Files.list(from.resolve("refs/sessions"))
      val refs = try sessions.iterator.asScala.toVector.map { path =>
        val ref = Files.readString(path).fromJson[StorageFiles.SessionRef]
          .fold(error => throw new IllegalArgumentException(s"session ${path.getFileName}: $error"), identity)
        val head = ref.head.map(id => ids.getOrElse(id,
          throw new IllegalArgumentException(s"session ${path.getFileName}: missing head $id")))
        from.relativize(path) -> ref.copy(head = head).toJson
      }
      finally sessions.close()

      // Validate the entire input before creating any output. Preserve blobs, logs and private file permissions.
      val paths = Files.walk(from)
      try {
        val entries = paths.iterator.asScala.toVector
        require(!entries.exists(Files.isSymbolicLink(_)), "source store must not contain symbolic links")
        Files.createDirectory(to)
        if Files.getFileStore(to).supportsFileAttributeView("posix") then
          Files.setPosixFilePermissions(to, PosixFilePermissions.fromString("rwx------"))
        entries.filter(_ != from).foreach { path =>
          Files.copy(path, to.resolve(from.relativize(path)), StandardCopyOption.COPY_ATTRIBUTES)
        }
        Files.writeString(to.resolve("objects.jsonl"), lines.mkString("", "\n", "\n"))
        refs.foreach { case (path, json) => Files.writeString(to.resolve(path), json) }
      } finally paths.close()
      recovered
    }.flatMap(count => StorageFS.open(destination).flatMap(_.listSessions).as(count))
}
