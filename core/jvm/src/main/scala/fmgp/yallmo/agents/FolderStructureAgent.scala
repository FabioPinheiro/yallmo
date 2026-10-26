package fmgp.yallmo.agents

import fmgp.yallmo.fs.*
import fmgp.yallmo.model.{Agent, AgentId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason}
import zio.*
import zio.json.*
import zio.stream.ZStream

/** Provider-free example that reviews names, writes fs-structure.json and appends a counter to state.jsonl.
  *
  * Each turn ignores request content and reviews the current tree. Descriptions are regenerated from names; workspace
  * file contents are never read. The two root-level output files are excluded from the tree. Assumes sequential turns
  * and exclusive ownership of its root; no locking is provided. The two output writes are not transactional.
  */
final class FolderStructureAgent private (val id: AgentId) extends Agent[FileSystem] {
  import FolderStructureAgent.*

  override def turn(request: AgentTurnRequest): ZIO[FileSystem, YallmoError, AgentTurnResponse] =
    ZIO.serviceWithZIO[FileSystem] { fs =>
      (for {
        previous <- loadState(fs)
        structure <- reviewFolder(fs, ".", ".")
        next = State(previous.reviewCount + 1)
        _ <- saveStructure(fs, structure)
        _ <- appendState(fs, next)
      } yield AgentTurnResponse(Chunk(MessagePayload.Assistant(structure.toJsonPretty)), LlmStopReason.Stop))
        .mapError(error => YallmoError.AgentExecutionFailed(id, error.toString, new RuntimeException(error.toString)))
    }

  private def loadState(fs: FsJsonlRead): IO[FsError, State] =
    for {
      state <- fs
        .read[State](StatePath)
        .runLast
        .flatMap {
          case Some(value) => ZIO.succeed(value)
          case None        => ZIO.fail(FsError.InvalidArgument("state.jsonl contains no state"))
        }
        .catchSome { case FsError.NotFound(StatePath) =>
          ZIO.succeed(State(0))
        }
      _ <- ZIO
        .fail(FsError.InvalidArgument("Review count must be non-negative and below Long.MaxValue"))
        .when(state.reviewCount < 0 || state.reviewCount == Long.MaxValue)
    } yield state

  private def reviewFolder(fs: FsRead, path: String, name: String): IO[FsError, Folder] =
    for {
      names <- fs.list(path)
      children <- ZIO.foreach(names.filterNot(name => path == "." && (name == StatePath || name == StructurePath))) {
        child =>
          val childPath = if path == "." then child else s"$path/$child"
          reviewFolder(fs, childPath, child).map[Either[Folder, File]](Left(_)).catchSome {
            // FsRead exposes names, not metadata; only a failed directory listing identifies a leaf.
            case FsError.WrongType(`childPath`, "directory") => ZIO.succeed(Right(File(child, s"File $child.")))
          }
      }
    } yield Folder(
      name,
      if path == "." then "Configured workspace." else s"Directory $name.",
      children.collect { case Left(folder) => folder }.toList,
      children.collect { case Right(file) => file }.toList
    )

  private def saveStructure(fs: FsWrite, structure: Folder): IO[FsError, Unit] =
    fs.writeUtf8(StructurePath, structure.toJsonPretty + "\n", WriteMode.Replace)

  private def appendState(fs: FsJsonlWrite, state: State): IO[FsError, Unit] =
    ZStream.succeed(state).run(fs.write[State](StatePath, WriteMode.Append))
}

object FolderStructureAgent {
  private val StatePath = "state.jsonl"
  private val StructurePath = "fs-structure.json"

  /** Last persisted review count; no counter is cached in memory. */
  final case class State(reviewCount: Long) derives JsonCodec

  /** A discovered file name with a generated, non-empty description. */
  final case class File(name: String, description: String) derives JsonCodec

  /** A directory, including empty ones; "." names the configured root. Children use direct names, not full paths. */
  final case class Folder(name: String, description: String, folders: List[Folder], files: List[File]) derives JsonCodec

  /** Binds an already-initialized filesystem once; the returned agent needs no per-turn environment. */
  def make(id: AgentId, fs: FileSystem): Agent[Any] = {
    val implementation = new FolderStructureAgent(id)
    new Agent[Any] {
      override val id: AgentId = implementation.id
      override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        implementation.turn(request).provideEnvironment(ZEnvironment(fs))
    }
  }
}
