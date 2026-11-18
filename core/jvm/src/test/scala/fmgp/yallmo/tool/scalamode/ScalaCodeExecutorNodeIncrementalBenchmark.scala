package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.orchestration.ExternalFunctionResult
import fmgp.yallmo.tool.ScalaModeResult
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import zio.*

/** Reproducible lab, not a production backend. First link in a new worker per case, then five identical inputs and five
  * real edits. Fresh-build controls are remeasured in the same run; creation/close are separate CSV events.
  */
object ScalaCodeExecutorNodeIncrementalBenchmark extends ZIOAppDefault {
  import ScalaCodeExecutorNodeIncrementalLab.{Mode, Session}

  private def sha(bytes: Array[Byte]): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .map(b => f"${b & 0xff}%02x")
    .mkString
  private def quote(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

  private def emit(
      backend: String,
      name: String,
      phase: String,
      sample: Int,
      result: ExternalFunctionResult.ScalaMode,
      source: String = "",
      session: Option[Session] = None
  ): Task[Unit] = {
    val evidence = result.logs.filter(_.startsWith("LAB ")).mkString(" ")
    val hashes = session
      .map { lab =>
        val output = lab.directory.resolve("program.cjs")
        val cp = lab.directory.resolve("classpath")
        val ir = if Files.exists(cp) then
          Path
            .of(Files.readString(cp, UTF_8).takeWhile(_ != java.io.File.pathSeparatorChar))
            .resolve("ScalaToolSnippet$.sjsir")
        else lab.directory.resolve("absent")
        (
          if Files.exists(output) then sha(Files.readAllBytes(output)) else "",
          if Files.exists(ir) then sha(Files.readAllBytes(ir)) else ""
        )
      }
      .getOrElse(("", ""))
    Console.printLine(
      "INCR," + Seq(
        backend,
        name,
        phase,
        sample.toString,
        result.compilationTime.toString,
        result.executionSetupTime.fold("")(_.toString),
        result.executionTime.toString,
        result.totalTime.toString,
        if source.nonEmpty then sha(source.getBytes(UTF_8)) else "",
        hashes._1,
        hashes._2,
        evidence
      )
        .map(quote)
        .mkString(",")
    )
  }

  private def event(duration: Duration, evidence: String = ""): ExternalFunctionResult.ScalaMode =
    ExternalFunctionResult
      .ScalaMode(ScalaModeResult.Executed("lifecycle"), Seq(evidence), 0, false)
      .copy(totalTime = duration.toMillis)

  def run: Task[Unit] = {
    val cli = sys.env("YALLMO_TEST_SCALA_CLI")
    val freshJVM = ScalaCodeExecutorJVM.make(cli)
    val freshNode = ScalaCodeExecutorJVM.make(cli, backend = ScalaCodeExecutorNodeFixtures.realBackend)
    Console.printLine(
      "INCR,backend,case,phase,sample,compilationTime,executionSetupTime,executionTime,totalTime,sourceSha256,outputSha256,snippetIrSha256,linkerEvidence"
    ) *>
      ZIO.foreachDiscard(List("value", "effect", "compute")) { name =>
        ZIO.scoped {
          for {
            sessions <- ZIO.foreach(
              List("retainedCLI" -> Mode.CLI, "retainedLinker" -> Mode.Linker, "retainedJVM" -> Mode.JVM)
            ) { case (backend, mode) =>
              for {
                scope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
                (duration, lab) <- ScalaCodeExecutorNodeIncrementalLab
                  .open(mode)
                  .provideEnvironment(ZEnvironment[Scope](scope))
                  .timed
                evidence <- ZIO.attempt {
                  lab.worker.fold("")(_ => "LAB " + Files.readString(lab.directory.resolve("ready"), UTF_8))
                }
                _ <- emit(backend, name, "create", -1, event(duration, evidence))
              } yield (backend, scope, lab)
            }
            _ <- ZIO.foreachDiscard(0 to 10) { sample =>
              val edit = math.max(0, sample - 5)
              val phase = if sample == 0 then "first" else if edit == 0 then "identical" else "edited"
              val (code, expected) = name match {
                case "value"  => ((42 + edit).toString, (42 + edit).toString)
                case "effect" => (s"ZIO.succeed(${42 + edit})", (42 + edit).toString)
                case _        =>
                  (
                    "List.range(1, 1001).map(n => n * n).sum" + (if edit == 0 then "" else s" + $edit"),
                    (333833500 + edit).toString
                  )
              }
              val controls = List(
                ("freshJVM", freshJVM.execute(code, 120.seconds, 65536), Option.empty[Session]),
                ("freshNode", freshNode.execute(code, 120.seconds, 65536), Option.empty[Session])
              ) ++
                sessions.map { case (backend, _, lab) => (backend, lab.execute(code), Some(lab)) }
              ZIO.foreachDiscard(controls) { case (backend, execute, session) =>
                for {
                  result <- execute
                  _ <- ZIO
                    .fail(new IllegalStateException(s"$backend/$name/$phase/$sample failed: ${result.output}"))
                    .unless(
                      result.exitCode == 0 && result.output.trim == expected && result.executionSetupTime.nonEmpty && !result.truncated
                    )
                  _ <- emit(backend, name, phase, sample, result, code, session)
                } yield ()
              }
            }
            _ <- ZIO.foreachDiscard(sessions) { case (backend, scope, _) =>
              scope.close(Exit.unit).timed.flatMap { case (duration, _) =>
                emit(backend, name, "close", -1, event(duration))
              }
            }
          } yield ()
        }
      }
  }
}
