package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.debug.{ReplDebug, ReplDebugSnapshot}
import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

/** Writes a debug export on behalf of a local terminal client. */
object LocalDebugExport {
  val defaultPath: Path = Paths.get(java.lang.System.getProperty("user.home"), ".yallmo", "yallmo-debug.log")

  def write(snapshot: ReplDebugSnapshot, path: Path = defaultPath): Task[Path] =
    ZIO.attemptBlocking {
      Option(path.getParent).foreach(parent => Files.createDirectories(parent))
      Files.writeString(
        path,
        ReplDebug.renderJsonLines(snapshot.messages),
        UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      path
    }
}
