package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.fs.{FsError, FsRead, FsWrite}
import fmgp.yallmo.tool.sdlc.llm.PlanIntentSimplifiedModel
import zio.*

/** Intent snapshots under the shared server root, with the same fail-closed batch journal format as tasks.
  *
  * Use through SdlcService so tools and HTTP share one owner. This store checks reference shape; the service checks
  * task existence before mutations. Synced appends are not cross-process transactions or retry-idempotent creates.
  */
final class PlanIntentStorage private (read: FsRead, write: FsWrite, lock: Semaphore) {
  import PlanIntentStorage.*
  private val journalFile = new PlanJournal[PlanIntent](read, write, JournalPath, _.id)

  /** Preserve history and compact current state under the same store lock. */
  def checkpoint: IO[FsError, Unit] = lock.withPermit {
    load.flatMap(journal => journalFile.checkpoint(journal.current.values.toList.sortBy(_.id), journal.contents))
  }

  def listIntents: IO[FsError, List[PlanIntent]] =
    lock.withPermit(load.map(_.current.values.toList.sortBy(_.id)))

  def readIntents(ids: List[String]): IO[FsError, List[PlanIntentSimplifiedModel]] = lock.withPermit {
    for {
      _ <- check(ids.size <= MaxBatchSize, s"Read at most $MaxBatchSize intents at a time")
      _ <- check(ids.distinct.size == ids.size && ids.forall(validId), "Use unique intent IDs such as I-1")
      journal <- load
      _ <- check(
        ids.forall(journal.current.contains),
        s"Unknown intent IDs: ${ids.filterNot(journal.current.contains).mkString(", ")}"
      )
    } yield ids.map(id => journal.current(id).toSimplifiedModel)
  }

  /** Replaces supplied intent planning content; empty IDs allocate permanent I-n references. */
  def set(intents: List[PlanIntentSimplifiedModel]): IO[FsError, List[PlanIntentSimplifiedModel]] = lock.withPermit {
    for {
      _ <- check(intents.size <= MaxBatchSize, s"Set at most $MaxBatchSize intents at a time")
      _ <- ZIO.foreachDiscard(intents)(intent =>
        ZIO.fromEither(validate(intent, allowEmptyId = true)).mapError(FsError.InvalidArgument.apply)
      )
      supplied = intents.map(_.id).filter(_.nonEmpty)
      _ <- check(supplied.distinct.size == supplied.size, "An intent ID may appear only once in Set")
      journal <- load
      _ <- check(
        supplied.forall(journal.current.contains),
        "Unknown intent ID; use an empty id to create, or read the existing intent before updating"
      )
      _ <- ZIO.foreachDiscard(intents) { intent =>
        val expected = journal.current.get(intent.id).fold(0L)(_.revision)
        check(
          intent.expectedRevision == expected,
          s"Revision conflict for ${intent.id}; read the intent again before editing"
        )
      }
      allocated = allocate(intents, journal.current.keySet)
      now <- Clock.instant
      snapshots = allocated.map { intent =>
        val previous = journal.current.get(intent.id)
        val updatedAt = previous.filter(_.updatedAt.isAfter(now)).fold(now)(_.updatedAt)
        previous
          .filter(_.toSimplifiedModel == intent)
          .getOrElse(
            intent
              .toPlanIntent(previous.fold(now)(_.createdAt), updatedAt)
              .copy(revision = previous.fold(1L)(_.revision + 1))
          )
      }
      current = journal.current ++ snapshots.map(intent => intent.id -> intent)
      _ <- check(current.size <= MaxRetainedRecords, s"Project is limited to $MaxRetainedRecords retained intents")
      _ <- check(
        current.values.count(intent => !intent.archived) <= MaxIntents,
        s"Project is limited to $MaxIntents active intents; archive existing outcomes first"
      )
      _ <- check(snapshots.forall(_.revision > 0), "Intent revision is exhausted")
      changes = snapshots.filterNot(intent => journal.current.get(intent.id).contains(intent))
      _ <- journalFile.append(changes, journal.contents, current.values.toList, journal.current.values.toList)
    } yield snapshots.map(_.toSimplifiedModel)
  }

  private final case class Journal(current: Map[String, PlanIntent], contents: PlanJournal.Contents[PlanIntent])

  private def load: IO[FsError, Journal] =
    for {
      contents <- journalFile.load
      current <- ZIO.foldLeft(contents.records)(Map.empty[String, PlanIntent]) { case (state, (original, index)) =>
        def corrupt(message: String) = FsError.InvalidJson(JournalPath, index.toLong + 1, message)
        val previous = state.get(original.id)
        // Old journals have no revision field. Infer each legacy version; checkpoints retain their positive versions.
        val revision = if original.revision == 0 then previous.fold(1L)(_.revision + 1) else original.revision
        val intent = original.copy(revision = revision)
        for {
          _ <- ZIO.fromEither(validate(intent.toSimplifiedModel, allowEmptyId = false)).mapError(corrupt)
          _ <- ZIO
            .fail(corrupt("Invalid intent revision history"))
            .when(revision <= 0 || previous.exists(old => revision != old.revision + 1))
          _ <- ZIO
            .fail(corrupt("Invalid intent timestamp history"))
            .when(
              intent.updatedAt.isBefore(intent.createdAt) || previous
                .exists(old => old.createdAt != intent.createdAt || intent.updatedAt.isBefore(old.updatedAt))
            )
        } yield state.updated(intent.id, intent)
      }
      _ <- ZIO
        .fail(FsError.IoFailure(JournalPath, "Retained intent limit exceeded"))
        .when(current.size > MaxRetainedRecords)
    } yield Journal(current, contents)
}

object PlanIntentStorage {
  val Directory = PlanTaskStorage.Directory
  val JournalPath = s"$Directory/intents.jsonl"
  val MaxBatchSize = PlanTaskStorage.MaxBatchSize
  private[sdlc] val MaxIntents = 1000
  private[sdlc] val MaxRetainedRecords = PlanJournal.MaxRecords / 2
  private[sdlc] val MaxRecords = PlanJournal.MaxRecords
  private[sdlc] val MaxJournalBytes = PlanJournal.MaxJournalBytes

  def make(read: FsRead, write: FsWrite): UIO[PlanIntentStorage] =
    Semaphore.make(1).map(new PlanIntentStorage(read, write, _))

  private def check(condition: Boolean, message: => String): IO[FsError, Unit] =
    ZIO.fail(FsError.InvalidArgument(message)).unless(condition).unit

  private def validId(id: String): Boolean = id.matches("I-[1-9][0-9]{0,9}")

  private def validate(intent: PlanIntentSimplifiedModel, allowEmptyId: Boolean): Either[String, Unit] =
    for {
      _ <- Either.cond(intent.expectedRevision >= 0, (), "expectedRevision must be nonnegative")
      _ <- Either.cond(
        validId(intent.id) || (allowEmptyId && intent.id.isEmpty),
        (),
        "Use an intent ID such as I-1, or empty id to create"
      )
      _ <- Either.cond(
        intent.text.trim.nonEmpty && intent.text.length <= 8000,
        (),
        "Intent text must contain 1–8000 characters"
      )
      _ <- Either.cond(
        intent.dependsOn.size <= PlanTaskStorage.MaxTasks && intent.dependsOn.distinct.size == intent.dependsOn.size && intent.dependsOn
          .forall(_.matches("T-[1-9][0-9]{0,9}")),
        (),
        "Intent dependencies must contain unique task IDs such as T-1"
      )
    } yield ()

  private def allocate(
      intents: List[PlanIntentSimplifiedModel],
      existing: Set[String]
  ): List[PlanIntentSimplifiedModel] = {
    var used = existing
    var next = 1
    intents.map { intent =>
      if intent.id.nonEmpty then intent
      else {
        while used.contains(s"I-$next") do next += 1
        val id = s"I-$next"
        used += id
        intent.copy(id = id)
      }
    }
  }
}
