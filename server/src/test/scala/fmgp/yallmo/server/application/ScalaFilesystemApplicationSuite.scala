package fmgp.yallmo.server.application

import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.model.{AgentId, ToolKey}
import fmgp.yallmo.orchestration.{AgentTask, SessionId}
import fmgp.yallmo.orchestration.config.{AgentConfig, Config, FileSystemConfig, ProviderConfig, ProviderId}
import fmgp.yallmo.server.{OrchestratorClient, OrchestratorWSClient, OrchestratorWSServer}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.tool.{BashExecutor, GitClient, ScalaCodeExecutor, ScalaTool}
import java.nio.file.Files
import java.util.Comparator
import munit.FunSuite
import zio.{Config as _, *}
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Opt-in real compiler/child and loopback sockets; only the provider's decisions are deterministic fakes. */
final class ScalaFilesystemApplicationSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(3).minutes

  test(
    "configured WebSocket turn advertises filesystem mode and reads only the selected private root via a real child"
  ) {
    val executable = sys.env.get("YALLMO_TEST_SCALA_CLI")
    assume(executable.nonEmpty, "Set YALLMO_TEST_SCALA_CLI for the configured cross-JVM acceptance check")
    val agent = AgentId("private-reader")
    val human = AgentId("operator")
    val provider = ProviderId.unsafe("local-fake")
    val code = """for {
      |  text <- fs.readUtf8("notes.txt")
      |  denied <- fs.readUtf8("../workspace/notes.txt").fold((error: FsError) => error.toString, identity)
      |  missing <- fs.readUtf8("workspace-only.txt").fold((error: FsError) => error.toString, identity)
      |} yield text + "|" + denied + "|" + missing
      |""".stripMargin
    val expected =
      "private-content|InvalidPath(../workspace/notes.txt,Parent traversal is forbidden)|NotFound(workspace-only.txt)"
    val effect = ZIO.scoped {
      for {
        root <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("yallmo-configured-scala-"))) { root =>
          ZIO.attempt {
            val paths = Files.walk(root)
            try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
            finally paths.close()
          }.orDie
        }
        workspace <- ZIO.attempt(Files.createDirectory(root.resolve("workspace")))
        privateRoot <- ZIO.attempt(Files.createDirectory(root.resolve("private")))
        storage <- ZIO.attempt(Files.createDirectory(root.resolve("storage")))
        _ <- ZIO.attempt {
          Files.writeString(workspace.resolve("notes.txt"), "wrong-workspace-content")
          Files.writeString(workspace.resolve("workspace-only.txt"), "must-not-fall-back")
          Files.writeString(privateRoot.resolve("notes.txt"), "private-content")
        }
        requests <- Ref.make(Vector.empty[String])
        providerRoutes = Routes(Method.POST / "v1" / "chat" / "completions" -> handler { (request: Request) =>
          for {
            text <- request.body.asString
            count <- requests.modify(previous => (previous.size + 1, previous :+ text))
          } yield {
            val outputs = ujson.read(text)("messages").arr.filter(_("role").str == "tool")
            val message = if outputs.nonEmpty then
              ujson.Obj(
                "role" -> "assistant",
                "content" -> ujson.read(outputs.head("content").str)("output").str.linesIterator.toList.last
              )
            else
              ujson.Obj(
                "role" -> "assistant",
                "tool_calls" -> ujson.Arr(
                  ujson.Obj(
                    "id" -> "read-private",
                    "type" -> "function",
                    "function" -> ujson.Obj(
                      "name" -> "scalamode",
                      "arguments" -> ujson.write(ujson.Obj("code" -> code, "mode" -> "filesystem"))
                    )
                  )
                )
              )
            if count > 2 then Response.status(Status.InternalServerError)
            else
              Response.json(
                ujson.write(
                  ujson.Obj(
                    "choices" -> ujson.Arr(
                      ujson.Obj(
                        "message" -> message,
                        "finish_reason" -> (if outputs.nonEmpty then "stop" else "tool_calls")
                      )
                    )
                  )
                )
              )
          }
        })
        providerServer <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
        providerPort <- Server.install(providerRoutes.sandbox).provideEnvironment(providerServer)
        config = Config(
          agents = Map(
            agent -> AgentConfig(
              "OAIAgent",
              Seq(provider),
              Json.Obj("model" -> Json.Str("fake-model")),
              filesystem = Some(FileSystemConfig(privateRoot.toString))
            )
          ),
          providers = Map(provider -> ProviderConfig.OAI(s"http://127.0.0.1:$providerPort/v1"))
        )
        configPath <- ZIO.attempt(Files.writeString(root.resolve("config.json"), config.toJson))
        route <- RpcWebSocket.route()
        childServer <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
        childPort <- Server.install(route.routes).provideEnvironment(childServer)
        services <- (Client.default ++ BashExecutor.live ++ GitClient.live ++ ScalaCodeExecutor.layer(executable.get) ++
          ZLayer.succeed(route)).build
        application <- ServerApplication
          .openRpc(storage, Some(configPath), Some(workspace), s"ws://127.0.0.1:$childPort/rpc")
          .provideSomeEnvironment[Scope](_.union(services))
        selected <- application.configured.get.runtime.resolve(agent)
        uiServer <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
        uiPort <- Server
          .install(new OrchestratorWSServer(application.master, application.configured).routes)
          .provideEnvironment(uiServer)
        endpoint = s"ws://127.0.0.1:$uiPort/rpc"
        viewer <- OrchestratorWSClient.connect(endpoint)
        denied <- viewer.createSession(Some(SessionId.unsafe("denied"))).either
        before <- requests.get
        client <- OrchestratorWSClient.connect(endpoint, userId = Some(human))
        info <- client.capabilities
        session <- client.createSession(Some(SessionId.unsafe("private-read")))
        outcome <- client.submitTurn(session.id, agent, AgentTask("read the selected private notes"))
        stored <- client.loadSession(session.id)
        bodies <- requests.get
      } yield {
        assertEquals(
          denied,
          Left(
            OrchestratorClient.Error.Remote(
              JsonRpc.Error(ErrorCode.Forbidden, "Operator permission unavailable for this connection")
            )
          )
        )
        assertEquals(before, Vector.empty)
        assertEquals(info.get.identity.role, Role.Operator)
        assertEquals(
          application.configured.get.assembly.context(Some(selected)).tools.find(_.key == ToolKey.Scalamode).get,
          ScalaTool.filesystemDefinition
        )
        val result = outcome.asInstanceOf[TurnOutcome.Succeeded]
        assertEquals(result.result.text, expected)
        assertEquals(stored.messages.last.content, expected)
        assertEquals(bodies.size, 2, bodies.mkString("\n"))
        val advertised = ujson.read(bodies.head)("tools").arr.map(_("function")).find(_("name").str == "scalamode").get
        assert(advertised("parameters")("properties")("mode")("enum").arr.contains(ujson.Str("filesystem")))
        val output =
          ujson.read(ujson.read(bodies.last)("messages").arr.find(_("role").str == "tool").get("content").str)
        assertEquals(output("exitCode").num.toInt, 0)
        assertEquals(output("timedOut").bool, false)
        assertEquals(output("truncated").bool, false)
        assertEquals(output("output").str.linesIterator.toList.last, expected)
      }
    }
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new AssertionError("Configured Scala acceptance timed out"))(150.seconds))
        .getOrThrowFiberFailure()
    }
  }
}
