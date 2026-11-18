package fmgp.yallmo.linker

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import org.scalajs.linker.{PathIRContainer, PathOutputDirectory, StandardImpl}
import org.scalajs.linker.interface.{ESVersion, ModuleInitializer, ModuleKind, OutputPatterns, StandardConfig}
import org.scalajs.logging.{Level, Logger}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

/** Production compiler infrastructure, NEVER evaluates submitted code. A dedicated process makes an in-flight linker
  * Future cancellable by its owning host scope. One immutable configuration, linker and IR cache per process.
  */
object IncrementalLinkerWorker {
  def main(args: Array[String]): Unit = {
    given ExecutionContext = ExecutionContext.global
    val root = Path.of(args(0))
    // Scala.js 1.22 defaults Closure off; do not opt into its deprecated Closure backend.
    val config = StandardConfig()
      .withModuleKind(ModuleKind.CommonJSModule)
      .withOptimizer(true)
      .withESFeatures(_.withESVersion(ESVersion.ES2015))
      .withMinify(false)
      .withSourceMap(false)
      .withBatchMode(false)
      .withOutputPatterns(OutputPatterns.fromJSFile("program.cjs"))
    val linker = StandardImpl.clearableLinker(config)
    val cache = StandardImpl.irFileCache()
    val files = cache.newCache
    var links = 0
    val identity =
      s"pid=${ProcessHandle.current().pid()} linker=${System.identityHashCode(linker)} cache=${System.identityHashCode(cache)}"
    def publish(name: String, text: String): Unit = {
      val temporary = root.resolve(name + ".tmp")
      Files.writeString(temporary, text, UTF_8)
      Files.move(temporary, root.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      ()
    }
    publish("ready", s"$identity Scala.js=1.22.0 fastLinkJS optimizer=true closure=false batchMode=false")
    try {
      val input = scala.io.Source.stdin.getLines()
      while input.hasNext && input.next() == "link" do {
        links += 1
        val messages = new StringBuilder
        val logger = new Logger {
          def log(level: Level, message: => String): Unit = messages.synchronized {
            if (level == Level.Warn || level == Level.Error) && messages.length < 24000 then {
              messages.append(level.toString).append(' ').append(message.take(2000)).append('\n'); ()
            }
          }
          def trace(error: => Throwable): Unit = log(Level.Error, error.toString)
        }
        cache.clearStats()
        val start = System.nanoTime()
        val exit = try {
          val classpath = Files
            .readString(root.resolve("classpath"), UTF_8)
            .trim
            .split(java.io.File.pathSeparator)
            .toSeq
            .map(Path.of(_))
          val ir = for {
            containers <- PathIRContainer.fromClasspath(classpath)
            cached <- files.cached(containers._1)
            report <- linker.link(
              cached,
              Seq(ModuleInitializer.mainMethodWithArgs("ScalaToolMain", "main")),
              PathOutputDirectory(root.resolve("linked")),
              logger
            )
          } yield report
          Await.result(ir, Duration.Inf)
          0
        } catch {
          case NonFatal(error) =>
            logger.log(Level.Error, "Link failed (details available only in server-owned workspace)")
            Files.writeString(root.resolve("link-error"), error.toString.take(32768), UTF_8)
            linker.clear() // A failed linker is not reused without resetting its internal state.
            1
        }
        val millis = (System.nanoTime() - start) / 1000000L
        val snippetIr = Files
          .readString(root.resolve("classpath"), UTF_8)
          .trim
          .split(java.io.File.pathSeparator)
          .iterator
          .map(Path.of(_).resolve("ScalaToolSnippet$.sjsir"))
          .find(Files.isRegularFile(_))
          .map(path =>
            java.security.MessageDigest
              .getInstance("SHA-256")
              .digest(Files.readAllBytes(path))
              .map(b => f"${b & 0xff}%02x")
              .mkString
          )
          .getOrElse("absent")
        val evidence = s"LINKER $identity links=$links linkCallMs=$millis snippetIr=$snippetIr ${cache.stats.logLine}\n"
        // Bound UTF-8 diagnostics even on pathological linking errors. Host charges them to its shared byte budget.
        val bytes = (evidence + messages.toString).getBytes(UTF_8).take(32768)
        publish("response", s"$exit\n" + new String(bytes, UTF_8))
      }
    } finally {
      files.free()
      linker.clear()
    }
  }
}
