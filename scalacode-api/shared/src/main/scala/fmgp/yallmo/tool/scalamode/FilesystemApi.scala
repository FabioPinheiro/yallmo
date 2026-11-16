package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.fs.{DirectoryDeleteMode, FsError, FsRead, MoveMode, WriteMode}
import zio.IO

/** Snippet-facing unary view of an explicitly supplied FsWrite capability.
  *
  * Paths and explicit mutation modes retain the underlying filesystem's restrictions. RPC does not support stream
  * arguments or exported scopes, so writeBytes and openUtf8Writer are deliberately not part of this view. No read
  * capability can be recovered from this interface.
  */
trait FsWriteApi {
  def writeUtf8(path: String, contents: String, mode: WriteMode): IO[FsError, Unit]
  def createDirectories(path: String): IO[FsError, Unit]
  def deleteFile(path: String): IO[FsError, Unit]
  def deleteDirectory(path: String, mode: DirectoryDeleteMode): IO[FsError, Unit]
  def move(source: String, destination: String, mode: MoveMode): IO[FsError, Unit]
}

/** Combined view, available only when the host explicitly supplies both capabilities. */
trait FilesystemApi extends FsRead with FsWriteApi
