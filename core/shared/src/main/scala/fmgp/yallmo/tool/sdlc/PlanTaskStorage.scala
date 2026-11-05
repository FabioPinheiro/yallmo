package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.fs.{FsError, FsRead, FsWrite}
import fmgp.yallmo.tool.sdlc.llm.PlanTaskSimplifiedModel
import zio.*

/** Server-shared task journal, using only explicit rooted read/write capabilities.
  *
  * Share one instance across tools and agents for a server storage root: its semaphore covers read/modify/append, not
  * just writing. Replacement updates require the version read by the caller. Independent instances/processes must not
  * share this journal. Each Set appends one JSONL array of changed snapshots, so replay cannot accept half a batch.
  * Writes are synced but not transactions: an I/O failure can leave an incomplete final record or an uncertain
  * successful commit. Incomplete/corrupt journals fail closed; they are never silently reset or repaired.
  */
final class PlanTaskStorage private (read: FsRead, write: FsWrite, lock: Semaphore) {
  import PlanTaskStorage.*
  private val journalFile = new PlanJournal[PlanTask](read, write, JournalPath, _.id)

  /** Preserve history and compact current state under the same store lock. */
  def checkpoint: IO[FsError, Unit] = lock.withPermit {
    load.flatMap(journal => journalFile.checkpoint(journal.current.values.toList.sortBy(_.id), journal.contents))
  }

  /** Full current snapshots for HTTP inspection, including archived records and runtime metadata. */
  def listTasks: IO[FsError, List[PlanTask]] =
    lock.withPermit(load.map(_.current.values.toList.sortBy(_.id)))

  /** Returns requested tasks in request order, including archived tasks. Unknown IDs fail explicitly. */
  def readTasks(ids: List[String]): IO[FsError, List[PlanTaskSimplifiedModel]] = lock.withPermit {
    for {
      _ <- check(ids.size <= MaxBatchSize, s"Read at most $MaxBatchSize tasks at a time")
      _ <- check(ids.distinct.size == ids.size && ids.forall(validId), "Use unique task IDs such as T-1")
      journal <- load
      _ <- check(
        ids.forall(journal.current.contains),
        s"Unknown task IDs: ${ids.filterNot(journal.current.contains).mkString(", ")}"
      )
    } yield ids.map(id => journal.current(id).toSimplifiedModel)
  }

  /** Replaces only supplied tasks. Empty IDs request allocation; nonempty IDs must already exist.
    *
    * Planning validation is completed before opening the writer. Unmentioned tasks are retained, not deleted.
    */
  def set(tasks: List[PlanTaskSimplifiedModel]): IO[FsError, List[PlanTaskSimplifiedModel]] = lock.withPermit {
    for {
      _ <- check(tasks.size <= MaxBatchSize, s"Set at most $MaxBatchSize tasks at a time")
      _ <- ZIO.foreachDiscard(tasks)(task =>
        ZIO.fromEither(validateTask(task, allowEmptyId = true)).mapError(FsError.InvalidArgument.apply)
      )
      suppliedIds = tasks.map(_.id).filter(_.nonEmpty)
      _ <- check(suppliedIds.distinct.size == suppliedIds.size, "A task ID may appear only once in Set")
      journal <- load
      _ <- check(
        suppliedIds.forall(journal.current.contains),
        "Unknown task ID; use an empty id to create a task, or ReadTask before updating"
      )
      _ <- ZIO.foreachDiscard(tasks) { task =>
        val expected = journal.current.get(task.id).fold(0L)(_.revision)
        check(task.expectedRevision == expected, s"Revision conflict for ${task.id}; ReadTask again before editing")
      }
      allocated = allocate(tasks, journal.current.keySet)
      now <- Clock.instant
      snapshots = allocated.map { task =>
        val previous = journal.current.get(task.id)
        // A current-revision no-op does not create a redundant historical snapshot.
        val updatedAt = previous.filter(_.updatedAt.isAfter(now)).fold(now)(_.updatedAt)
        previous
          .filter(_.toSimplifiedModel == task)
          .getOrElse(
            task
              .toPlanTask(previous.fold(now)(_.createdAt), updatedAt)
              .copy(revision = previous.fold(1L)(_.revision + 1))
          )
      }
      _ <- ZIO.foreachDiscard(snapshots)(task =>
        ZIO
          .fromEither(validateTask(task.toSimplifiedModel, allowEmptyId = false))
          .mapError(FsError.InvalidArgument.apply)
      )
      current = journal.current ++ snapshots.map(task => task.id -> task)
      _ <- check(current.size <= MaxRetainedRecords, s"Project is limited to $MaxRetainedRecords retained tasks")
      _ <- check(
        current.values.count(task =>
          !task.archived && (task.state == TaskState.Open || task.state == TaskState.Doing)
        ) <= MaxTasks,
        s"Project is limited to $MaxTasks active tasks; complete or archive existing work first"
      )
      _ <- ZIO.fromEither(validateGraph(current)).mapError(FsError.InvalidArgument.apply)
      changes = snapshots.filterNot(task => journal.current.get(task.id).contains(task))
      _ <- journalFile.append(changes, journal.contents, current.values.toList, journal.current.values.toList)
    } yield snapshots.map(_.toSimplifiedModel)
  }

  private final case class Journal(current: Map[String, PlanTask], contents: PlanJournal.Contents[PlanTask])

  private def load: IO[FsError, Journal] =
    for {
      contents <- journalFile.load
      current <- ZIO.foldLeft(contents.records)(Map.empty[String, PlanTask]) { case (state, (original, index)) =>
        def corrupt(message: String) = FsError.InvalidJson(JournalPath, index.toLong + 1, message)
        val previous = state.get(original.id)
        // Old journals have no revision field. Infer each legacy version; checkpoints retain their positive versions.
        val revision = if original.revision == 0 then previous.fold(1L)(_.revision + 1) else original.revision
        val task = original.copy(revision = revision)
        for {
          _ <- ZIO.fromEither(validateTask(task.toSimplifiedModel, allowEmptyId = false)).mapError(corrupt)
          _ <- ZIO
            .fail(corrupt("Invalid task revision history"))
            .when(revision <= 0 || previous.exists(old => revision != old.revision + 1))
          _ <- ZIO
            .fail(corrupt("Invalid task timestamp history"))
            .when(
              task.updatedAt.isBefore(task.createdAt) || previous
                .exists(old => old.createdAt != task.createdAt || task.updatedAt.isBefore(old.updatedAt))
            )
        } yield state.updated(task.id, task)
      }
      _ <- ZIO
        .fail(FsError.IoFailure(JournalPath, "Retained task limit exceeded"))
        .when(current.size > MaxRetainedRecords)
      _ <- ZIO.fromEither(validateGraph(current)).mapError(message => FsError.InvalidJson(JournalPath, 1L, message))
    } yield Journal(current, contents)
}

object PlanTaskStorage {
  val Directory = PlanJournal.Directory
  val JournalPath = s"$Directory/tasks.jsonl"
  val MaxBatchSize = PlanJournal.MaxBatchSize
  private[sdlc] val MaxTasks = 1000
  private[sdlc] val MaxRetainedRecords = PlanJournal.MaxRecords / 2
  private[sdlc] val MaxRecords = PlanJournal.MaxRecords
  private[sdlc] val MaxJournalBytes = PlanJournal.MaxJournalBytes

  /** Construction performs no filesystem I/O. Supply capabilities rooted at server storage and reuse this owner. */
  def make(read: FsRead, write: FsWrite): UIO[PlanTaskStorage] =
    Semaphore.make(1).map(new PlanTaskStorage(read, write, _))

  private def check(condition: Boolean, message: => String): IO[FsError, Unit] =
    ZIO.fail(FsError.InvalidArgument(message)).unless(condition).unit

  private def validId(id: String): Boolean = id.matches("T-[1-9][0-9]{0,9}")

  private def validateTask(task: PlanTaskSimplifiedModel, allowEmptyId: Boolean): Either[String, Unit] = {
    val taskLinks = List(task.subtasks, task.dependsOn, task.leftoverOf)
    for {
      _ <- Either.cond(task.expectedRevision >= 0, (), "expectedRevision must be nonnegative")
      _ <- Either.cond(
        validId(task.id) || (allowEmptyId && task.id.isEmpty),
        (),
        "Use a task ID such as T-1, or empty id for a new task"
      )
      _ <- Either.cond(
        task.text.trim.nonEmpty && task.text.length <= 8000,
        (),
        "Task text must contain 1–8000 characters"
      )
      _ <- Either.cond(
        taskLinks.forall(ids => ids.size <= MaxTasks && ids.distinct.size == ids.size && ids.forall(validId)),
        (),
        "Task relationships must contain unique task IDs"
      )
      _ <- Either.cond(taskLinks.flatten.forall(_ != task.id), (), "A task cannot reference itself")
      _ <- Either.cond(
        task.contributesTo.size <= MaxTasks && task.contributesTo.distinct.size == task.contributesTo.size && task.contributesTo
          .forall(_.matches("I-[1-9][0-9]{0,9}")),
        (),
        "Contributions must contain unique intent IDs such as I-1"
      )
      _ <- Either.cond(
        task.assumptions.size <= 100 && task.assumptions.forall(text => text.trim.nonEmpty && text.length <= 2000),
        (),
        "Use at most 100 nonblank assumptions, each at most 2000 characters"
      )
    } yield ()
  }

  private def allocate(tasks: List[PlanTaskSimplifiedModel], existing: Set[String]): List[PlanTaskSimplifiedModel] = {
    var used = existing
    var next = 1
    tasks.map { task =>
      if task.id.nonEmpty then task
      else {
        while used.contains(s"T-$next") do next += 1
        val id = s"T-$next"
        used += id
        task.copy(id = id)
      }
    }
  }

  private def validateGraph(tasks: Map[String, PlanTask]): Either[String, Unit] = {
    val missing = tasks.valuesIterator
      .flatMap(task => task.subtasks ++ task.dependsOn ++ task.leftoverOf)
      .filterNot(tasks.contains)
      .toSet
    if missing.nonEmpty then Left(s"Unknown task references: ${missing.toList.sorted.mkString(", ")}")
    else if cyclic(tasks, _.subtasks) then Left("Subtask containment must not contain cycles")
    else if cyclic(tasks, _.dependsOn) then Left("Task dependencies must not contain cycles")
    else Right(())
  }

  /** Kahn traversal avoids recursive stack growth on long task chains. */
  private def cyclic(tasks: Map[String, PlanTask], links: PlanTask => List[String]): Boolean = {
    val incoming = scala.collection.mutable.Map.from(tasks.keysIterator.map(_ -> 0))
    tasks.valuesIterator.foreach(task => links(task).foreach(id => incoming(id) += 1))
    val ready = scala.collection.mutable.Queue.from(incoming.iterator.collect { case (id, 0) => id })
    var visited = 0
    while ready.nonEmpty do {
      val id = ready.dequeue()
      visited += 1
      links(tasks(id)).foreach { target =>
        incoming(target) -= 1
        if incoming(target) == 0 then ready.enqueue(target)
      }
    }
    visited != tasks.size
  }
}
