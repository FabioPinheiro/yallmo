package fmgp.yallmo.tool.scalamode

import zio.*

/** Opt-in measurements using the production JVM executor and retained Scala.js compiler/linker. Every sample starts a
  * fresh evaluation child; Node reuses its compiler workspace across samples and cases until rotation. Sample 0 is the
  * first measured submission for that case, not a guarantee of cold compiler/linker or dependency caches.
  */
object ScalaCodeExecutorNodeBenchmark extends ZIOAppDefault {
  def run: Task[Unit] = {
    val cli = sys.env.getOrElse("YALLMO_TEST_SCALA_CLI", "scala-cli")
    ZIO.scoped(
      ScalaCodeExecutors
        .node(ScalaCodeExecutorNodeFixtures.realArtifacts, cli, ScalaCodeExecutorNodeFixtures.realNode)
        .flatMap { node =>
          val backends = List(
            "JVM" -> ScalaCodeExecutors.make(cli),
            "Node" -> node
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
                      .unless(
                        result.exitCode == 0 && result.output.trim == expected && result.executionSetupTime.nonEmpty
                      )
                    _ <- Console.printLine(
                      s"BENCH,$backend,$name,$sample,${result.compilationTime}," +
                        s"${result.executionSetupTime.get},${result.executionTime},${result.totalTime}"
                    )
                  } yield ()
                }
              }
            }
        }
    )
  }
}
