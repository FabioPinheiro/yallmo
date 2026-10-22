package fmgp.yallmo.fs

import zio.{Chunk, IO, Scope, ZIO}
import zio.stream.{ZPipeline, ZStream}

/** Read capability for one trusted root.
  *
  * Paths are relative: "." denotes the root; empty paths, absolute paths, parent traversal and symlinks are rejected.
  * Implementations must not expose filesystem handles or a way to recover write access.
  */
trait FsRead {

  /** Validates and opens on consumption; closes on completion, failure, interruption or early termination. */
  def readBytes(path: String): ZStream[Any, FsError, Byte]

  /** Collects the whole file as UTF-8 text via readBytes; prefer readBytes for incremental consumption.
    *
    * Invalid UTF-8 fails with IoFailure; underlying filesystem errors are preserved.
    */
  def readUtf8(path: String): IO[FsError, String] =
    readBytes(path)
      .via(ZPipeline.utf8Decode.mapError(error => FsError.IoFailure(path, error.getMessage)))
      .runCollect
      .map(_.mkString)

  /** Sorted direct entry names, not full paths. Symlink entries are rejected in the initial implementation. */
  def list(path: String): IO[FsError, Chunk[String]]
}

/** Write capability for one trusted root, with the same path restrictions as FsRead. */
trait FsWrite {

  /** Consumes bytes incrementally with backpressure, preserving upstream environment requirements and errors.
    *
    * On execution, validates the path and opens one file before consuming the stream. Applies mode even to an empty
    * stream; parent directories must already exist. Closes on completion, failure or interruption without collecting
    * the whole stream. Failure can leave a partially written file; completion does not promise disk synchronization.
    */
  def writeBytes[R, E](
      path: String,
      bytes: ZStream[R, E, Byte],
      mode: WriteMode
  ): ZIO[R, E | FsError, Unit]

  /** Encodes text as UTF-8 and delegates to writeBytes, preserving its path validation and explicit open mode.
    *
    * Invalid input text fails with IoFailure; underlying filesystem errors are preserved. Parent directories must
    * already exist. Completion closes the file but does not promise disk synchronization.
    */
  def writeUtf8(path: String, contents: String, mode: WriteMode): IO[FsError, Unit] =
    writeBytes(
      path,
      ZStream.succeed(contents).via(ZPipeline.utf8Encode.mapError(error => FsError.IoFailure(path, error.getMessage))),
      mode
    )

  /** Keeps a buffered UTF-8 writer open until the scope ends. Parent directories must already exist.
    *
    * Operations on one writer must be serialized. Scope exit flushes and closes the writer; it does not imply sync.
    */
  def openUtf8Writer(path: String, mode: WriteMode): ZIO[Scope, FsError, FsUtf8Writer]

  /** Recursively creates directories. Existing directories succeed; an existing non-directory fails. */
  def createDirectories(path: String): IO[FsError, Unit]

  /** Deletes a regular file. Missing paths fail with NotFound; directories fail with WrongType. */
  def deleteFile(path: String): IO[FsError, Unit]

  /** Deletes a directory, never the configured root. Missing paths fail with NotFound; files fail with WrongType.
    *
    * EmptyOnly fails with DirectoryNotEmpty for non-empty directories. Recursive rejects symlinks in the subtree;
    * failure or interruption can leave a partially deleted tree, with no rollback.
    */
  def deleteDirectory(path: String, mode: DirectoryDeleteMode): IO[FsError, Unit]

  /** Moves a file or directory; a same-parent move is a rename. Both paths are relative to the same root.
    *
    * Destination parents must exist. Reject the root as either endpoint, identical endpoints, moving a directory into
    * itself, and symlinks (including in a moved subtree). No directory merging, cross-filesystem copy/delete fallback,
    * atomicity or disk synchronization is promised. A missing source fails with NotFound.
    */
  def move(source: String, destination: String, mode: MoveMode): IO[FsError, Unit]
}

/** Capability to write one already-open file, without opening other files or exposing its handle.
  *
  * Scope manages cleanup, not capability lifetime: ordinary Scala can retain this value after the scope ends.
  */
trait FsUtf8Writer {
  def write(text: String): IO[FsError, Unit]

  /** Delegates text followed by LF to one write call; does not validate JSON or embedded newlines. */
  def writeLine(line: String): IO[FsError, Unit] = write(line + "\n")

  /** Pushes application buffers to the operating system; does not guarantee disk persistence. */
  def flush: IO[FsError, Unit]

  /** Flushes and requests durable storage of file data and metadata; guarantees depend on the storage system. */
  def sync: IO[FsError, Unit]
}

/** Explicit behavior when opening a file for writing; none of these modes creates parent directories. */
enum WriteMode {

  /** Fails with AlreadyExists if any entry already occupies the path. */
  case CreateNew

  /** Creates a missing file or truncates an existing regular file. */
  case Replace

  /** Creates a missing file or appends to an existing regular file without truncating it. */
  case Append
}

/** Explicitly choose whether directory deletion may remove descendants. */
enum DirectoryDeleteMode {

  /** Only delete an empty directory. */
  case EmptyOnly

  /** Delete descendants and the directory itself; never follow symlinks. */
  case Recursive
}

/** Explicit destination behavior for moves and renames. Neither mode replaces or merges directories. */
enum MoveMode {

  /** Fail with AlreadyExists if any entry already occupies the destination. */
  case FailIfExists

  /** Allow replacing an existing regular file only when the source is also a regular file.
    *
    * A missing destination permits either a file or directory source. An existing destination directory fails with
    * AlreadyExists; a directory source targeting an existing regular file fails with WrongType.
    */
  case ReplaceFile
}
