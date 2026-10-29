package fmgp.yallmo.scalacode

import zio.{Chunk, Task, UIO}

/** Script-facing filesystem service. The host supplies its implementation and controls its lifetime. This interface is
  * not a sandbox or a guarantee that paths are confined to a workspace. Paths are portable strings interpreted by the
  * host implementation.
  */
trait FileSystem {
  def isDirectory(path: String): UIO[Boolean]
  def readUtf8(path: String): Task[String]
  def list(path: String): Task[Chunk[String]]
}
