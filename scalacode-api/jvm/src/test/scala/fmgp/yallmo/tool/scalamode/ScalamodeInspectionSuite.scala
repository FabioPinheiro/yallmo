package fmgp.yallmo.tool.scalamode

import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.{AgentFunction, AgentTool, ExternalFunctionCall, ExternalFunctionResult}
import fmgp.yallmo.tool.*
import munit.FunSuite
import zio.*
import zio.json.*

final class ScalamodeInspectionSuite extends FunSuite {
  private def run[A](effect: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def function(schema: FunctionToolDefinition): AgentFunction[Any] = new AgentFunction[Any] {
    val definition = schema
    def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
      ZIO.dieMessage("API inspection must not execute a function")
  }

  test("pure binding documents restricted builtins without claiming additional services") {
    val api = ScalaTool.definition.scalamode.get
    assertEquals(api.services, Nil)
    assert(api.builtins.contains("def succeed[A]"))
    assert(api.builtins.contains("def foreach[A, R, E, B]"))
    assert(!api.builtins.contains("def service"))
    assertEquals(ScalaTool.definition.inputSchema.required, Set("code"))
  }

  test("custom intersection bindings document all public API views without inspecting transport or artifacts") {
    val source = "trait MathApi { def twice(value: Int): Int }\ntrait TextApi { def upper(value: String): String }"
    val service = new ScalamodeService {
      val apiDocs = "Math and text helpers"
      override val scalaApi = Some(source)
      val binding = ScalamodeService.Binding(
        "helpers",
        Seq("example.MathApi", "example.TextApi"),
        "example.PrivateRuntime.run"
      )
      override def rpcBinding = throw new AssertionError("Inspection must not inspect transport")
    }
    val definition = ScalaTool.withServices(service).definition
    val api = definition.scalamode.get.services.head
    assertEquals(api.parameter, "helpers")
    assertEquals(api.apiTypes, List("example.MathApi", "example.TextApi"))
    assertEquals(api.scalaApi, Some(source))
    assert(definition.description.contains(source)) // The LLM sees the same interface the inspector displays.
    assert(!api.toJson.contains("PrivateRuntime"))
    assert(!api.toJson.contains("artifacts"))
    assertEquals(definition.inputSchema, ScalaTool.definition.inputSchema)
  }

  test("read-service declarations match each exact permitted view, including empty and partial bindings") {
    run(ZIO.scoped(for {
      route <- RpcWebSocket.route()
      _ <- ZIO.foreachDiscard(
        List(
          Chunk.empty[FunctionToolDefinition],
          Chunk(FilesystemToolDefinition.ReadFileDefinition),
          Chunk(FilesystemToolDefinition.ListFilesDefinition),
          Chunk(FilesystemToolDefinition.ReadFileDefinition, FilesystemToolDefinition.ListFilesDefinition)
        )
      ) { schemas =>
        val service = AgentToolWirings.readService(schemas.map(function), route, "ws://private-host:1234/rpc")
        val api = ScalaTool.withServices(service).definition.scalamode.get.services.head
        val source = api.scalaApi.get
        ZIO.succeed {
          assertEquals(source.contains("def readFile("), schemas.exists(_.name == FilesystemToolDefinition.ReadFile))
          assertEquals(source.contains("def listFiles("), schemas.exists(_.name == FilesystemToolDefinition.ListFiles))
          assertEquals(api.apiTypes, service.binding.apiTypes.toList)
          assert(!api.toJson.contains("private-host"))
          assert(!api.toJson.contains("NativeReadToolsRuntime"))
          assert(!api.toJson.contains("dispatcher"))
          assert(!api.toJson.contains("ticket"))
        }
      }
    } yield ()))
  }

  test("the final wired function carries its own service documentation and leaves unrelated definitions unchanged") {
    val executor = new ScalaCodeExecutor {
      def isAvailable = ZIO.succeed(true)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
        ZIO.dieMessage("Inspection must not execute Scala")
    }
    run(ZIO.scoped(for {
      route <- RpcWebSocket.route()
      original = AgentTool.group(
        Chunk(FilesystemToolDefinition, ScalamodeToolDefinition),
        Chunk(
          function(FilesystemToolDefinition.ReadFileDefinition),
          AgentFunction.provideEnvironment(new ScalaTool(), ZEnvironment(executor))
        )
      )
      wired <- AgentToolWirings.withFilesystem(executor, route, "ws://private-host:1234/rpc")(original)
    } yield {
      val definition = wired.find(_.name == ToolKey.Scalamode).get.functions.head.definition
      val api = definition.scalamode.get.services.head
      assert(api.scalaApi.get.contains("def readFile("))
      assert(!api.scalaApi.get.contains("def listFiles("))
      assertEquals(wired.find(_.name == ToolKey.Filesystem), original.find(_.name == ToolKey.Filesystem))
      assertEquals(wired.find(_.name == ToolKey.Scalamode).get.definition.functions, Set(definition))
    }))
  }
}
