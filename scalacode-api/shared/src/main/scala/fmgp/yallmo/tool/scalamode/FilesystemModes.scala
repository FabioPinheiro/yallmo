package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.fs.{DirectoryDeleteMode, MoveMode, WriteMode}
import scala.annotation.experimental

/** Safe access to immutable mode values; the original enum companions are not compiled in safe mode.
  *
  * These constructors grant no filesystem authority. Every mutation still requires an explicitly bound API.
  */
object FilesystemModes {
  @experimental
  @caps.assumeSafe
  def createNew: WriteMode = WriteMode.CreateNew

  @experimental
  @caps.assumeSafe
  def replace: WriteMode = WriteMode.Replace

  @experimental
  @caps.assumeSafe
  def append: WriteMode = WriteMode.Append

  @experimental
  @caps.assumeSafe
  def emptyOnly: DirectoryDeleteMode = DirectoryDeleteMode.EmptyOnly

  @experimental
  @caps.assumeSafe
  def recursive: DirectoryDeleteMode = DirectoryDeleteMode.Recursive

  @experimental
  @caps.assumeSafe
  def failIfExists: MoveMode = MoveMode.FailIfExists

  @experimental
  @caps.assumeSafe
  def replaceFile: MoveMode = MoveMode.ReplaceFile
}
