package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.fs.{FsError, FsRead, FsWrite}
import fmgp.yallmo.tool.sdlc.llm.{PlanIntentSimplifiedModel, PlanTaskSimplifiedModel}
import zio.*

/** One server-owned planning service shared by agent tools and read-only HTTP inspection.
  *
  * Full list operations retain storage metadata. Agent-facing operations exchange simplified planning snapshots. One
  * owner serializes cross-journal reference checks and writes; it does not provide cross-process transactions.
  */
trait SdlcService {
  def listTasks: IO[FsError, List[PlanTask]]
  def listIntents: IO[FsError, List[PlanIntent]]
  def discover(
      query: String = "",
      includeArchived: Boolean = false,
      limit: Int = 50,
      afterId: Option[String] = None
  ): IO[FsError, PlanDiscovery]
  def checkpoint: IO[FsError, Unit]
  def readTasks(ids: List[String]): IO[FsError, List[PlanTaskSimplifiedModel]]
  def readIntents(ids: List[String]): IO[FsError, List[PlanIntentSimplifiedModel]]
  def setTasks(tasks: List[PlanTaskSimplifiedModel]): IO[FsError, List[PlanTaskSimplifiedModel]]
  def setIntents(intents: List[PlanIntentSimplifiedModel]): IO[FsError, List[PlanIntentSimplifiedModel]]
}

object SdlcService {

  /** Pure construction: no files are read or created before a service operation is executed. */
  def make(read: FsRead, write: FsWrite): UIO[SdlcService] =
    for {
      tasks <- PlanTaskStorage.make(read, write)
      intents <- PlanIntentStorage.make(read, write)
      gate <- Semaphore.make(1)
    } yield new SdlcService {
      override def listTasks: IO[FsError, List[PlanTask]] = gate.withPermit(tasks.listTasks)
      override def listIntents: IO[FsError, List[PlanIntent]] = gate.withPermit(intents.listIntents)
      override def checkpoint: IO[FsError, Unit] = gate.withPermit(tasks.checkpoint *> intents.checkpoint)

      override def discover(
          query: String,
          includeArchived: Boolean,
          limit: Int,
          afterId: Option[String]
      ): IO[FsError, PlanDiscovery] = gate.withPermit {
        def title(text: String): String = text.takeWhile(c => c != '\n' && c != '\r').trim.take(160) match {
          case ""    => "(Untitled)"
          case value => value
        }
        for {
          _ <- ZIO
            .fail(FsError.InvalidArgument("Use limit 1–100, a query of at most 200 characters, and a valid afterId"))
            .unless(
              limit >= 1 && limit <= 100 && query.length <= 200 &&
                afterId.forall(_.matches("[TI]-[1-9][0-9]{0,9}"))
            )
          taskRecords <- tasks.listTasks
          intentRecords <- intents.listIntents
          needle = query.trim.toLowerCase
          matches = (taskRecords.map(task =>
            (
              PlanSummary(task.id.value, task.title, task.revision, Some(task.state), task.archived),
              task.title + "\n" + task.description + task.steps
                .map(step => s"\n${step.title}\n${step.description}")
                .mkString
            )
          ) ++
            intentRecords.map(intent =>
              (PlanSummary(intent.id, title(intent.text), intent.revision, None, intent.archived), intent.text)
            ))
            .filter { case (summary, text) =>
              (includeArchived || !summary.archived) && afterId.forall(summary.id > _) &&
              (summary.id.toLowerCase.contains(needle) || text.toLowerCase.contains(needle))
            }
            .map(_._1)
            .sortBy(_.id)
            .take(limit + 1)
          page = matches.take(limit)
        } yield PlanDiscovery(page, Option.when(matches.size > limit)(page.last.id))
      }
      override def readTasks(ids: List[String]): IO[FsError, List[PlanTaskSimplifiedModel]] =
        gate.withPermit(tasks.readTasks(ids))
      override def readIntents(ids: List[String]): IO[FsError, List[PlanIntentSimplifiedModel]] =
        gate.withPermit(intents.readIntents(ids))

      override def setTasks(models: List[PlanTaskSimplifiedModel]): IO[FsError, List[PlanTaskSimplifiedModel]] =
        gate.withPermit {
          for {
            _ <- ZIO.when(models.exists(_.contributesTo.nonEmpty)) {
              for {
                current <- intents.listIntents
                _ <- referencesExist(models.flatMap(_.contributesTo), current.map(_.id).toSet, "intent")
              } yield ()
            }
            saved <- tasks.set(models)
          } yield saved
        }

      override def setIntents(models: List[PlanIntentSimplifiedModel]): IO[FsError, List[PlanIntentSimplifiedModel]] =
        gate.withPermit {
          for {
            _ <- ZIO.when(models.exists(_.dependsOn.nonEmpty)) {
              for {
                current <- tasks.listTasks
                _ <- referencesExist(models.flatMap(_.dependsOn), current.map(_.id.value).toSet, "task")
              } yield ()
            }
            saved <- intents.set(models)
          } yield saved
        }
    }

  private def referencesExist(ids: List[String], known: Set[String], kind: String): IO[FsError, Unit] = {
    val missing = ids.toSet -- known
    ZIO
      .fail(FsError.InvalidArgument(s"Unknown $kind references: ${missing.toList.sorted.mkString(", ")}"))
      .when(missing.nonEmpty)
      .unit
  }
}
