package fmgp.yallmo.tool.scalamode

import zio.*

/** Opt-in end-to-end measurements using the actual executors. Each sample compiles fresh source in a new directory and
  * starts a fresh child. Sample 0 is first-use/case-cold, NOT empty-cache or compiler-daemon-cold. Shared provisioned
  * dependency caches and the default compiler server are retained, exactly as in normal execution.
  */
object ScalaCodeExecutorNodeBenchmark extends ZIOAppDefault {
  def run: Task[Unit] = {
    val cli = sys.env.getOrElse("YALLMO_TEST_SCALA_CLI", "scala-cli")
    val backends = List(
      "JVM" -> ScalaCodeExecutorJVM.make(cli),
      "Node" -> ScalaCodeExecutorJVM.make(cli, backend = ScalaCodeExecutorNodeFixtures.realBackend)
    )
    val cases = List(
      ("value", "42", "42"),
      ("effect", "ZIO.succeed(42)", "42"),
      ("compute", "List.range(1, 1001).map(n => n * n).sum", "333833500")
    )
    Console.printLine("BENCH,backend,case,sample,compilationTime,executionSetupTime,executionTime,totalTime") *>
      ZIO.foreachDiscard(cases) { case (name, code, expected) =>
        ZIO.foreachDiscard(0 to 5) { sample =>
          ZIO.foreachDiscard(backends) { case (backend, executor) =>
            for {
              result <- executor.execute(code, 120.seconds, 64 * 1024)
              _ <- ZIO
                .fail(new IllegalStateException(s"$backend/$name/$sample failed: ${result.output}"))
                .unless(result.exitCode == 0 && result.output.trim == expected && result.executionSetupTime.nonEmpty)
              _ <- Console.printLine(
                s"BENCH,$backend,$name,$sample,${result.compilationTime}," +
                  s"${result.executionSetupTime.get},${result.executionTime},${result.totalTime}"
              )
            } yield ()
          }
        }
      }
  }
}
