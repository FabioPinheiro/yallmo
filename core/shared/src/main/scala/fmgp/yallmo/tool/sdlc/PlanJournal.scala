package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.fs.{FsError, FsJsonlRead, FsRead, FsWrite, MoveMode, WriteMode}
import zio.*
import zio.json.*
import zio.stream.ZStream

/** Shared JSONL I/O only; task/intent validation and revisions stay in their stores. Call under the owning store's
  * lock. Maintenance never renames away the only history copy.
  */
private[sdlc] final class PlanJournal[A: JsonCodec](read: FsRead, write: FsWrite, path: String, idOf: A => String) {
  import PlanJournal.*
  private val marker = path + ".maintenance"

  def load: IO[FsError, Contents[A]] =
    for {
      pending <- read.readBytes(marker).take(1).runDrain.as(true).catchSome { case FsError.NotFound(`marker`) =>
        ZIO.succeed(false)
      }
      _ <- ZIO
        .fail(FsError.IoFailure(path, s"Unfinished maintenance: inspect $marker before using this journal"))
        .when(pending)
      bytes <- bytesAt(path, missingIsEmpty = true)
      _ <- ZIO
        .fail(FsError.InvalidJson(path, 1L, "Incomplete final record; recover the journal before writing"))
        .when(bytes.nonEmpty && bytes.last != '\n'.toByte)
      reader = new FsJsonlRead {
        def readBytes(name: String): ZStream[Any, FsError, Byte] = ZStream.fromChunk(bytes)
        def list(name: String): IO[FsError, Chunk[String]] = PlanJournal.this.read.list(name)
      }
      batches <- reader.read[List[A]](path).take(MaxRecords.toLong + 1).runCollect
      _ <- ZIO.foreachDiscard(batches.zipWithIndex) { case (batch, index) =>
        ZIO
          .fail(FsError.InvalidJson(path, index.toLong + 1, "Invalid journal batch"))
          .when(batch.isEmpty || batch.size > MaxBatchSize || batch.map(idOf).distinct.size != batch.size)
      }
      records = batches.zipWithIndex.flatMap { case (batch, index) => batch.map(record => (record, index)) }
      _ <- ZIO
        .fail(FsError.IoFailure(path, "Journal exceeds its snapshot limit; maintenance is required"))
        .when(records.size > MaxRecords)
    } yield Contents(bytes, records)

  def append(changes: List[A], previous: Contents[A], current: List[A], previousCurrent: List[A]): IO[FsError, Unit] = {
    val text = if changes.isEmpty then "" else changes.toJson + "\n"
    def currentSize(records: List[A]): Long =
      records.grouped(MaxBatchSize).map(batch => (batch.toJson + "\n").getBytes("UTF-8").length.toLong).sum
    val size = currentSize(current)
    for {
      // Reserve half the journal for future revisions: checkpointing must leave room to make progress.
      _ <- ZIO
        .fail(
          FsError.InvalidArgument("Current planning content exceeds 2 MiB; shorten descriptions before adding content")
        )
        .when(changes.nonEmpty && size > MaxCurrentBytes && size >= currentSize(previousCurrent))
      _ <- requireCapacity(
        previous.records.size + changes.size <= MaxRecords &&
          previous.bytes.size.toLong + text.getBytes("UTF-8").length <= MaxJournalBytes
      )
      _ <- ZIO.when(changes.nonEmpty) {
        ZIO.uninterruptible(write.createDirectories(Directory) *> writeSynced(path, text, WriteMode.Append))
      }
    } yield ()
  }

  /** Explicit recoverable maintenance, not an atomic filesystem transaction. Retain a verified history copy and marker
    * until the replacement has been verified. Any uncertain failure leaves normal operations blocked, even if the
    * primary is missing or valid-looking.
    */
  def checkpoint(current: List[A], previous: Contents[A]): IO[FsError, Unit] =
    ZIO
      .when(previous.bytes.nonEmpty) {
        val text = current.grouped(MaxBatchSize).map(_.toJson + "\n").mkString
        for {
          _ <- requireCapacity(current.size <= MaxRecords && text.getBytes("UTF-8").length <= MaxJournalBytes)
          uuid <- Random.nextUUID
          backup = s"$Directory/history/${path.split('/').last}-$uuid"
          temporary = s"$path.$uuid.pending"
          _ <- ZIO.uninterruptible {
            for {
              _ <- write.createDirectories(s"$Directory/history")
              _ <- writeSynced(marker, CheckpointFiles(backup, temporary).toJson + "\n", WriteMode.CreateNew)
              original = new String(previous.bytes.toArray, "UTF-8")
              _ <- writeSynced(backup, original, WriteMode.CreateNew)
              _ <- verify(backup, previous.bytes)
              _ <- writeSynced(temporary, text, WriteMode.CreateNew)
              replacement = Chunk.fromArray(text.getBytes("UTF-8"))
              _ <- verify(temporary, replacement)
              _ <- write.move(temporary, path, MoveMode.ReplaceFile)
              _ <- verify(path, replacement)
              _ <- write.deleteFile(marker)
            } yield ()
          }
        } yield ()
      }
      .unit

  private def bytesAt(name: String, missingIsEmpty: Boolean = false): IO[FsError, Chunk[Byte]] =
    for {
      bytes <- read.readBytes(name).take(MaxJournalBytes.toLong + 1).runCollect.catchSome {
        case FsError.NotFound(`name`) if missingIsEmpty => ZIO.succeed(Chunk.empty)
      }
      _ <- ZIO.fail(FsError.IoFailure(name, "Journal exceeds its byte limit")).when(bytes.size > MaxJournalBytes)
    } yield bytes

  private def verify(name: String, expected: Chunk[Byte]): IO[FsError, Unit] =
    bytesAt(name).flatMap(actual =>
      ZIO
        .fail(FsError.IoFailure(name, "Maintenance verification failed; leave the marker and recover manually"))
        .unless(actual == expected)
        .unit
    )

  private def writeSynced(name: String, text: String, mode: WriteMode): IO[FsError, Unit] =
    ZIO.scoped {
      for {
        writer <- write.openUtf8Writer(name, mode)
        _ <- writer.write(text)
        _ <- writer.sync
      } yield ()
    }

  private def requireCapacity(condition: Boolean): IO[FsError, Unit] =
    ZIO
      .fail(FsError.InvalidArgument("Journal capacity reached; run SDLC checkpoint maintenance before retrying"))
      .unless(condition)
      .unit
}

private[sdlc] object PlanJournal {
  val Directory = "sdlc"
  val MaxBatchSize = 100
  val MaxRecords = 10000
  val MaxJournalBytes = 4 * 1024 * 1024
  val MaxCurrentBytes = MaxJournalBytes / 2
  final case class Contents[A](bytes: Chunk[Byte], records: Chunk[(A, Int)])
  private final case class CheckpointFiles(backup: String, temporary: String) derives JsonCodec
}
