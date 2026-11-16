package fmgp.yallmo.tool.scalamode

import fmgp.rpc.*
import fmgp.yallmo.orchestration.{AgentFunction, AgentTool, ExternalFunctionCall, ExternalFunctionResult}
import fmgp.yallmo.tool.*
import munit.FunSuite
import scala.concurrent.Future
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Portable contracts, wiring and evaluation must work without a JVM listener or compiler. */
final class ScalamodeSharedSuite extends FunSuite {
  private def run[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.runToFuture(effect) }

  private val service = new ScalamodeService {
    val apiDocs = "Pure arithmetic helper"
    override val scalaApi = Some("trait MathApi { def twice(value: Int): Int }")
    val binding = ScalamodeService.Binding("math", Seq("example.MathApi"), "example.MathRuntime.run")
    override def rpcBinding = throw new AssertionError("Portable inspection must not access transport")
  }

  test("service metadata and service-bound execution work with a portable executor") {
    val executor = new ScalaCodeExecutor {
      def isAvailable = ZIO.succeed(true)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
        ZIO.dieMessage("Expected service-bound execution")
      override def executeWithServices[R <: ScalamodeService](
          code: String,
          services: R,
          timeout: Duration,
          maxOutputBytes: Int
      ) = ZIO.succeed {
        assertEquals(code, "math.twice(21)")
        assert(services eq service)
        assertEquals(timeout, 45.seconds)
        assertEquals(maxOutputBytes, 64 * 1024)
        ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("42"))
      }
    }
    val bound = ScalaTool.withServices(service)
    assertEquals(bound.definition.scalamode.get.services, List(service.inspection))
    assertEquals(bound.definition.inputSchema, ScalaTool.definition.inputSchema)
    assert(!service.inspection.toJson.contains("MathRuntime"))
    run(
      bound
        .execute(ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"math.twice(21)"}"""))
        .provideEnvironment(ZEnvironment(executor))
        .map(result => assertEquals(result.text, "42"))
    )
  }

  test("binding validation rejects source injection on either platform") {
    intercept[IllegalArgumentException](ScalamodeService.Binding("bad;code", Seq("example.Api"), "example.Run.run"))
    intercept[IllegalArgumentException](ScalamodeService.Binding("api", Seq("example.Api;code"), "example.Run.run"))
    intercept[IllegalArgumentException](ScalamodeService.Binding("api", Seq("example.Api"), "example.Run.run()"))
  }

  test("additional singleton service bindings accept names only and reject collisions") {
    val base = HelloWorldService.binding.copy(parameter = "fs", constants = Seq(HelloWorldService.inspection))
    assertEquals(base.constants.map(_.parameter), Seq("hello"))
    intercept[IllegalArgumentException](base.copy(parameter = "hello"))
    intercept[IllegalArgumentException](
      base.copy(constants = Seq(HelloWorldService.inspection.copy(parameter = "tool")))
    )
    intercept[IllegalArgumentException](
      base.copy(constants = Seq(HelloWorldService.inspection.copy(parameter = "bad;code")))
    )
    intercept[IllegalArgumentException](
      base.copy(constants = Seq(HelloWorldService.inspection.copy(apiTypes = List("example.Api"))))
    )
    intercept[IllegalArgumentException](
      base.copy(constants = Seq(HelloWorldService.inspection.copy(apiTypes = List("example.Api;code.type"))))
    )
  }

  test("hello service wiring and execution work without platform services or a separate native tool") {
    val executor = new ScalaCodeExecutor {
      def isAvailable = ZIO.succeed(true)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
        ZIO.dieMessage("Expected service-bound execution")
      override def executeWithServices[R <: ScalamodeService](
          code: String,
          services: R,
          timeout: Duration,
          maxOutputBytes: Int
      ) = ZIO.succeed {
        assertEquals(code, "hello.helloWorld")
        assert(services eq HelloWorldService)
        ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("Hello world"))
      }
    }
    val original = new AgentFunction[Any] {
      val definition = ScalaTool.definition
      def execute(call: ExternalFunctionCall) = ZIO.dieMessage("Expected replacement")
    }
    val input = Seq(AgentTool(ScalamodeToolDefinition, Chunk(original)))
    run(for {
      tools <- AgentToolWirings.withHelloWorld(executor)(input)
      again <- AgentToolWirings.withHelloWorld(executor)(tools)
      function = tools.head.functions.head
      result <- function.execute(ExternalFunctionCall(function.definition.name, """{"code":"hello.helloWorld"}"""))
    } yield {
      assert(again eq tools)
      assertEquals(tools.map(_.name), Seq(ToolKey.Scalamode))
      assertEquals(function.definition.scalamode.get.services, List(HelloWorldService.inspection))
      assertEquals(result.text, "Hello world")
    })
  }

  test("native read contracts bind and construct clients without WebSockets") {
    val api = new NativeReadTools {
      def readFile(path: String) = ZIO.succeed(s"contents:$path")
      def listFiles(path: String) = ZIO.fail(ReadToolError("Denied", path))
    }
    val dispatcher = NativeReadToolsRuntime.contract.bind(api)
    val client = NativeReadToolsRuntime.contract.client(new RpcConnection {
      def call(serviceId: String, version: Int, method: String, arguments: Chunk[Json]) =
        dispatcher.dispatch(serviceId, version, method, arguments)
      def close = ZIO.unit
    })
    run(for {
      read <- client.readFile("file").orDieWith(error => new Exception(error.message))
      listed <- client.listFiles("folder").either
    } yield {
      assertEquals(read, "contents:file")
      assertEquals(listed, Left(ReadToolError("Denied", "folder")))
    })
  }

  test("pure runner evaluation stays lazy and preserves typed failures and finalizers") {
    var evaluated = false
    var finalized = false
    val effect = ScalaToolRuntime.runPure { _ =>
      evaluated = true
      ScalaToolRuntime.evaluate(ZIO.fail("expected").ensuring(ZIO.succeed { finalized = true }))
    }
    assert(!evaluated)
    run(effect.either.map { result =>
      assertEquals(result, Left("expected"))
      assert(evaluated)
      assert(finalized)
    })
  }
}
