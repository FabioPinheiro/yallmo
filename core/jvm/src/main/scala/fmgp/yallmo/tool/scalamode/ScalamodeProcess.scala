package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.CommandOutput
import fmgp.rpc.RpcToken
import zio.*
import java.io.{ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import scala.jdk.CollectionConverters.*

private[scalamode] object ScalamodeProcess {

  /** Every phase owns its process and stream readers; both phases share the deadline and byte budget. */
  private[scalamode] def runProcess(
      command: Seq[String],
      directory: Path,
      deadline: Long,
      remaining: AtomicInteger,
      bootstrap: Option[(String, RpcToken)],
      setupTime: Option[AtomicLong] = None,
      nodeBackend: Boolean,
      classpathOutput: Boolean = false
  ): Task[CommandOutput] =
    if java.lang.System.nanoTime() >= deadline then ZIO.succeed(CommandOutput(-1, true, "", false))
    else
      ZIO.scoped {
        for {
          stopping <- ZIO.succeed(new AtomicBoolean(false))
          // Only the runtime receives the marker property. Never reuse a file left by compilation.
          _ <- ZIO.when(setupTime.nonEmpty)(
            ZIO.attemptBlocking(Files.deleteIfExists(directory.resolve(ScalaToolRuntime.ExecutionStartedFile)))
          )
          launchedAt <- ZIO.succeed(java.lang.System.nanoTime())
          process <- ZIO.acquireRelease(ZIO.attemptBlocking {
            val builder = new ProcessBuilder(command*)
              .directory(directory.toFile)
            // Preserve JVM policy; the Node backend additionally rejects preload/module-path injection.
            val injectionVariables = Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH") ++
              (if nodeBackend then Seq("NODE_OPTIONS", "NODE_PATH") else Seq.empty)
            injectionVariables.foreach(builder.environment().remove)
            builder.start()
          })(process => ZIO.attemptBlocking(stop(process, stopping)).orDie)
          captureOutput = (input: java.io.InputStream, budget: AtomicInteger) =>
            ZIO.attemptBlockingInterrupt {
              val bytes = new ByteArrayOutputStream()
              val buffer = new Array[Byte](8192)
              var truncated = false
              try {
                // Do not wait for EOF: a descendant can inherit stdout and keep the pipe open after CLI exit.
                // Polling available bytes also makes cancellation independent of interrupting a blocking pipe read.
                def capture(available: Int): Int = {
                  val count = input.read(buffer, 0, math.min(buffer.length, available))
                  if count > 0 then {
                    val retained = math.min(count, budget.getAndUpdate(left => math.max(0, left - count)))
                    bytes.write(buffer, 0, retained)
                    truncated ||= retained < count
                  }
                  count
                }
                while process.isAlive do {
                  val available = input.available()
                  if available > 0 then capture(available)
                  else Thread.sleep(10)
                }
                // Drain only bytes already pending at exit, not future output from an orphaned descendant.
                var pending = input.available()
                while pending > 0 do {
                  val count = capture(pending)
                  pending = if count > 0 then pending - count else 0
                }
              } catch {
                // Process.destroyForcibly can close the pipe while it is being polled. Preserve captured diagnostics.
                case _: IOException if stopping.get() || !process.isAlive => ()
              } finally input.close()
              (bytes.toString(UTF_8), truncated)
            }
          // Observe with the host's monotonic clock: nanoTime values from different JVMs are not comparable.
          setup <- ZIO.foreach(setupTime) { measured =>
            ZIO
              .attemptBlockingInterrupt {
                val marker = directory.resolve(ScalaToolRuntime.ExecutionStartedFile)
                while process.isAlive && !Files.exists(marker) do Thread.sleep(5)
                if Files.exists(marker) then
                  measured.set(TimeUnit.NANOSECONDS.toMillis(java.lang.System.nanoTime() - launchedAt))
              }
              .ignore
              .forkScoped
          }
          // --print-class-path is bounded compiler control data, not returned diagnostics. Never spend the user's
          // runtime/log budget on it (nor truncate it into a different linker input).
          output <- captureOutput(
            process.getInputStream,
            if classpathOutput then new AtomicInteger(64 * 1024) else remaining
          ).forkScoped
          diagnostics <- captureOutput(process.getErrorStream, remaining).forkScoped
          // The deadline runs concurrently with stdin delivery. Killing the child unblocks a pipe write;
          // interrupting a Java blocking write alone does not. The canceler also kills before awaiting delivery.
          completed <- ZIO
            .attemptBlockingInterrupt(
              process.waitFor(math.max(0L, deadline - java.lang.System.nanoTime()), TimeUnit.NANOSECONDS)
            )
            .ensuring(ZIO.attemptBlocking(stop(process, stopping)).orDie)
            .zipPar(
              ZIO.attemptBlockingCancelable {
                try {
                  try
                    bootstrap.foreach { case (endpoint, token) =>
                      process.getOutputStream.write((endpoint + "\n" + token.exportSecret + "\n").getBytes(UTF_8))
                      process.getOutputStream.flush()
                    }
                  finally process.getOutputStream.close()
                } catch {
                  case _: IOException if stopping.get() || !process.isAlive => ()
                }
              }(ZIO.attemptBlocking(stop(process, stopping)).orDie)
            )
          _ <- ZIO.foreachDiscard(setup)(_.join)
          captured <- output.join
          logs <- diagnostics.join
          status = s"Scala process finished stage=${command.lift(1).getOrElse("unknown")} " +
            s"exitCode=${process.exitValue()} timedOut=${!completed} " +
            s"stdoutBytes=${captured._1.getBytes(UTF_8).length} stderrBytes=${logs._1.getBytes(UTF_8).length} " +
            s"truncated=${captured._2 || logs._2}"
          // Diagnostics can contain submitted source or runtime secrets; return them to the caller, not host logs.
          _ <- if process.exitValue() != 0 || !completed then ZIO.logWarning(status) else ZIO.logInfo(status)
        } yield CommandOutput(
          process.exitValue(),
          !completed,
          captured._1,
          captured._2 || logs._2,
          logs._1.linesIterator.toVector
        )
      }

  private[scalamode] def readRuntimeLogs(directory: Path, remaining: AtomicInteger): Task[(Vector[String], Boolean)] =
    ZIO.attemptBlocking {
      val path = directory.resolve(fmgp.yallmo.tool.scalamode.ScalaToolRuntime.LogFile)
      if !Files.exists(path) then (Vector.empty, false)
      else {
        val input = Files.newInputStream(path)
        val bytes = try input.readNBytes(math.min(remaining.get().toLong + 1L, Int.MaxValue.toLong).toInt)
        finally input.close()
        val retained = math.min(bytes.length, remaining.getAndUpdate(left => math.max(0, left - bytes.length)))
        (new String(bytes, 0, retained, UTF_8).linesIterator.toVector, Files.size(path) > retained)
      }
    }

  private[scalamode] def stop(process: Process, stopping: AtomicBoolean): Unit = {
    stopping.set(true)
    val descendants = process.descendants()
    val children = try descendants.toList.asScala.toList
    finally descendants.close()
    children.reverse.foreach(_.destroyForcibly())
    if process.isAlive then process.destroyForcibly()
    process.waitFor(5, TimeUnit.SECONDS)
    ()
  }
}
