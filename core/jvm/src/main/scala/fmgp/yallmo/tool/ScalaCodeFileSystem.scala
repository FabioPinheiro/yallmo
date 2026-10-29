package fmgp.yallmo.tool

import fmgp.yallmo.scalacode.{FileSystem as ScriptFileSystem}
import java.nio.file.Path
import zio.*

/** Adapts the existing host service without copying state or acquiring a second set of resources. */
final class ScalaCodeFileSystem(host: WorkspaceFileSystem) extends ScriptFileSystem {
  override def isDirectory(path: String): UIO[Boolean] =
    ZIO.attempt(Path.of(path)).foldZIO(_ => ZIO.succeed(false), host.isDirectory)

  override def readUtf8(path: String): Task[String] =
    for {
      resolved <- ZIO.attempt(Path.of(path))
      content <- host.readUtf8(resolved)
    } yield content

  override def list(path: String): Task[Chunk[String]] =
    for {
      resolved <- ZIO.attempt(Path.of(path))
      entries <- host.list(resolved)
    } yield entries
}
