package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.config.*
import fmgp.yallmo.server.OrchestratorRpc
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.tool.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.time.Instant
import scala.jdk.CollectionConverters.*
import zio.{Config as _, *}
import zio.json.*
import zio.json.ast.Json

/** Exercises the production bundled provisioning, standalone application and page RPC, not the private linker lab. */
private[application] object ProductionIncrementalRoute {
  val human = AgentId("user-operator")
  val assistant = AgentId("assistant")
  private val provider = ProviderId.unsafe("unused")
  private val config = Config(
    agents = Map(
      assistant -> AgentConfig(
        "OAIAgent",
        Seq(provider),
        Json.Obj("model" -> Json.Str("unused")),
        capabilities = AgentCapabilities(functions = Some(Set(FunctionKey.scalamode)))
      )
    ),
    providers = Map(provider -> ProviderConfig.OAI("http://unused.invalid/v1"))
  )

  def withServer[A](use: (ServerExecutionService, OrchestratorRpc, ScalaJsRuntime) => Task[A]): Task[A] = {
    val finished = new java.util.concurrent.atomic.AtomicLong(0L)
    ZIO
      .scoped {
        (for {
          root <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-production-route-")))(path =>
            ZIO.attemptBlocking {
              val paths = Files.walk(path)
              try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
              finally paths.close()
            }.orDie
          )
          configPath = root.resolve("config.json")
          _ <- ZIO.attemptBlocking(Files.writeString(configPath, config.toJson, UTF_8))
          (startup, manual) <- ScalaJsRuntime.provisionBundled.timed
          _ <- ZIO
            .fail(new IllegalStateException(manual.capabilities.reason))
            .unless(manual.capabilities.jsNodeAvailable)
          (open, backend) <- ServerApplication.open(root, configPath, Some(root), manualJs = manual).timed
          _ <- Console.printLine(
            s"ROUTE_LIFECYCLE provisioningMs=${startup.toMillis} applicationOpenMs=${open.toMillis}"
          )
          rpc = new OrchestratorRpc(backend)
          result <- use(backend, rpc, manual)
          _ <- ZIO.succeed(finished.set(java.lang.System.nanoTime()))
        } yield result).provideSomeLayer[Scope](ServerApplication.live)
      }
      .tap(_ =>
        Console.printLine(s"ROUTE_LIFECYCLE closeMs=${(java.lang.System.nanoTime() - finished.get()) / 1000000L}")
      )
  }

  def connection(rpc: OrchestratorRpc): UIO[OrchestratorRpc.Connection] = for {
    connection <- rpc.openConnection
    _ <- rpc.handle(
      JsonRpc
        .Request(
          Json.Str("init"),
          Initialize.method,
          Initialize.Params("production-incremental", Some(UserId("operator")))
        )
        .toJson,
      connection
    )
  } yield connection

  def invoke(
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection,
      code: String,
      js: Boolean = true
  ): Task[ScalaModeOutput] = for {
    input <- ZIO.succeed(
      Message(
        MessageData(
          human,
          None,
          Instant.now(),
          Message.ToolCall(
            FunctionCall(
              FunctionCallId.unsafe(java.util.UUID.randomUUID().toString),
              FunctionKey.scalamode,
              ujson.write(ujson.Obj("code" -> code))
            )
          )
        )
      )
    )
    response <- rpc.handle(
      JsonRpc
        .Request(
          Json.Str("run"),
          AgentsInvoke.method,
          AgentsInvoke.Params(assistant, input, human, if js then Some(ScalamodeExecution.JsNode) else None)
        )
        .toJson,
      connection
    )
    output <- ZIO.fromEither(response.get.fromJson[JsonRpc.Success[Message]].left.map(new IllegalStateException(_)))
    result <- ZIO.attempt(
      output.result.payload.asInstanceOf[MessagePayload.ToolOutput].output.asInstanceOf[ScalaModeOutput]
    )
  } yield result

  def evidence(output: ScalaModeOutput): String = output.logs.find(_.startsWith("LINKER ")).getOrElse("")
  def identity(output: ScalaModeOutput): String = evidence(output).takeWhile(_ != '\n').split(" links=").head
}

/** Focused actual-page-route measurement; emits existing timing fields as raw CSV. */
object ProductionIncrementalRouteBenchmark extends ZIOAppDefault {
  import ProductionIncrementalRoute.*
  private def quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
  def run: Task[Unit] = ZIO
    .attempt {
      val tooling = getClass.getClassLoader.getResource("scalamode-linker/classpath.txt")
      val api = getClass.getClassLoader.getResource(BundledJsArtifacts.resourceNames.head)
      require(tooling != null && tooling.getProtocol == "jar", "Measure packaged production worker tooling")
      require(api != null && api.getProtocol == "jar", "Measure packaged reviewed JS APIs")
      s"ROUTE_PROVENANCE tooling=$tooling api=$api"
    }
    .flatMap(Console.printLine(_)) *> Console.printLine(
    "ROUTE,backend,case,phase,sample,compilationTime,executionSetupTime,executionTime,totalTime,output,linkerEvidence"
  ) *>
    ZIO.foreachDiscard(List("value", "effect", "compute")) { name =>
      withServer { (_, rpc, _) =>
        for {
          conn <- connection(rpc)
          _ <- ZIO.foreachDiscard(List(true, false)) { js =>
            ZIO.foreachDiscard(0 to 10) { sample =>
              val edit = math.max(0, sample - 5)
              val code = name match {
                case "value"  => s"40 + ${2 + edit}"
                case "effect" => s"ZIO.succeed(40 + ${2 + edit})"
                case _        => s"(1 to ${100 + edit}).map(n => n * n).sum"
              }
              for {
                output <- ProductionIncrementalRoute.invoke(rpc, conn, code, js)
                expected = if name == "compute" then (1 to (100 + edit)).map(n => n * n).sum else 42 + edit
                _ <- ZIO.attempt(
                  require(output.exitCode == 0 && output.output.trim == expected.toString, output.toString)
                )
                _ <- Console.printLine(
                  "ROUTE," + Seq(
                    if js then "retainedNode" else "JVM",
                    name,
                    if sample == 0 then "first" else if edit == 0 then "identical" else "edited",
                    sample.toString,
                    output.compilationTime.toString,
                    output.executionSetupTime.fold("")(_.toString),
                    output.executionTime.toString,
                    output.totalTime.toString,
                    output.output.trim,
                    evidence(output)
                  ).map(quote).mkString(",")
                )
              } yield ()
            }
          }
          _ <- rpc.disconnect(conn)
        } yield ()
      }
    }
}
