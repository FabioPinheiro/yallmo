package fmgp.yallmo.tool.scalamode

import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.fs.FileSystem
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.tool.*
import munit.FunSuite
import zio.*

final class AgentToolWiringsSuite extends FunSuite {
  private def run[A](effect: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def function(schema: FunctionToolDefinition): AgentFunction[Any] = new AgentFunction[Any] {
    val definition = schema
    def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
      ZIO.dieMessage("Unrelated or original functions must not execute")
  }
  private def tool(key: ToolKey, functions: AgentFunction[Any]*): AgentTool[Any] =
    AgentTool(ToolDefinition(key, functions.map(_.definition).toSet), Chunk.fromIterable(functions))

  private val customDefinition = ScalamodeToolDefinition.withServices(
    Seq(ScalamodeServiceApi("custom", Seq("example.CustomApi"), "Custom service"))
  )

  private lazy val originalScala = AgentFunction.provideEnvironment(new ScalaTool(), ZEnvironment(executor))
  private lazy val scalamode = tool(ToolKey.Scalamode, originalScala)
  private val reads = tool(ToolKey.Filesystem, function(FilesystemToolDefinition.ReadFileDefinition))
  private val other =
    tool(ToolKey.unsafe("other"), function(FunctionToolDefinition(FunctionKey.unsafe("other"), "Other")))
  private val executor: ScalaCodeExecutor = new ScalaCodeExecutor with ScalaCodeExecutorServices {
    def isAvailable = ZIO.succeed(true)
    def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
      ZIO.dieMessage("Expected service execution")
    override def executeWithServices[R <: ScalamodeService](
        code: String,
        service: R,
        timeout: Duration,
        maxOutputBytes: Int
    ) = {
      assertEquals(timeout, 45.seconds)
      assertEquals(maxOutputBytes, 64 * 1024)
      ZIO.succeed(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed(service.binding.apiTypes.mkString(","))))
    }
  }

  test("default wiring preserves opaque default-schema custom closures") {
    val custom = function(ScalamodeToolDefinition.Default)
    val input = Seq(tool(ToolKey.Scalamode, custom))
    assert(run(AgentToolWirings.withDefaults(executor)(input)) eq input)
  }

  test("hello is bound only inside the existing Scalamode function, with matching inspection and code-only schema") {
    val input = Seq(other, scalamode, reads)
    val wired = run(AgentToolWirings.withHelloWorld(executor)(input))
    assertEquals(wired.map(_.name), input.map(_.name))
    assert(wired.head eq other)
    assert(wired.last eq reads)
    val bound = wired(1).functions.head
    assertEquals(bound.definition.name, ScalamodeToolDefinition.Execute)
    assertEquals(bound.definition.inputSchema, ScalaTool.definition.inputSchema)
    assertEquals(bound.definition.scalamode.get.services, List(HelloWorldService.inspection))
    assertEquals(wired(1).definition.functions, Set(bound.definition))
    val result = run(bound.execute(ExternalFunctionCall(bound.definition.name, """{"code":"hello.helloWorld"}""")))
    assertEquals(result.text, HelloWorldService.binding.apiTypes.mkString(","))
    val invalid = run(bound.execute(ExternalFunctionCall(bound.definition.name, "{}")).either)
    assert(invalid.left.toOption.exists(_.isInstanceOf[YallmoError.InvalidToolArguments]))
  }

  test("hello wiring preserves missing and custom Scalamode and is idempotent") {
    val wiring = AgentToolWirings.withHelloWorld(executor)
    val missing = Seq(other, reads)
    assert(run(wiring(missing)) eq missing)
    val custom = Seq(tool(ToolKey.Scalamode, function(customDefinition)))
    assert(run(wiring(custom)) eq custom)
    val wired = run(wiring(Seq(scalamode)))
    assert(run(wiring(wired)) eq wired)
    assertEquals(wired.flatMap(_.functions.map(_.definition.name)), Seq(ScalamodeToolDefinition.Execute))
  }

  test("withFilesystem is identity when either tool or usable bound read/list is missing") {
    run(ZIO.scoped(for {
      route <- RpcWebSocket.route()
      wire = AgentToolWirings.withFilesystem(executor, route, "ws://localhost:1/rpc")
      _ <- ZIO.foreachDiscard(
        List(
          Seq.empty,
          Seq(other, reads),
          Seq(other, scalamode),
          Seq(other, tool(ToolKey.Filesystem), scalamode),
          Seq(other, tool(ToolKey.Filesystem, other.functions.head), scalamode),
          Seq(tool(ToolKey.unsafe("not-filesystem"), reads.functions.head), scalamode)
        )
      )(input => wire(input).map(output => assert(output eq input)))
    } yield ()))
  }

  test("withFilesystem preserves custom service-bound Scalamode even when native reads are available") {
    val custom = tool(ToolKey.Scalamode, function(customDefinition))
    val input = Seq(other, custom, reads)
    run(ZIO.scoped(for {
      route <- RpcWebSocket.route()
      output <- AgentToolWirings.withFilesystem(executor, route, "ws://localhost:1/rpc")(input)
    } yield assert(output eq input)))
  }

  test(
    "withFilesystem replaces the default wrapped function for read-only, list-only and combined views, preserving order"
  ) {
    run(ZIO.scoped(for {
      route <- RpcWebSocket.route()
      _ <- ZIO.foreachDiscard(
        List(
          Seq(FilesystemToolDefinition.ReadFileDefinition),
          Seq(FilesystemToolDefinition.ListFilesDefinition),
          Seq(FilesystemToolDefinition.ReadFileDefinition, FilesystemToolDefinition.ListFilesDefinition)
        )
      ) { schemas =>
        val native = tool(ToolKey.Filesystem, schemas.map(function)*)
        val extra = other.functions.head
        val scala = tool(ToolKey.Scalamode, extra, originalScala)
        val input = Seq(other, scala, native)
        for {
          output <- AgentToolWirings.withFilesystem(executor, route, "ws://localhost:1/rpc")(input)
          bound = output(1).functions(1)
          result <- bound.execute(ExternalFunctionCall(ScalamodeToolDefinition.Execute, "{\"code\":\"42\"}"))
        } yield {
          assertEquals(output.map(_.name), input.map(_.name))
          assert(output.head eq other)
          assert(output(2) eq native)
          assert(output(1).functions.head eq extra)
          assert(!(bound eq originalScala))
          assertEquals(output(1).definition.functions, Set(extra.definition, bound.definition))
          assertEquals(bound.definition.inputSchema, ScalamodeToolDefinition.Default.inputSchema)
          val api = bound.definition.scalamode.get.services.head
          assertEquals(
            api.scalaApi.get.contains("def readFile("),
            schemas.exists(_.name == FilesystemToolDefinition.ReadFile)
          )
          assertEquals(
            api.scalaApi.get.contains("def listFiles("),
            schemas.exists(_.name == FilesystemToolDefinition.ListFiles)
          )
          assertEquals(result.text, api.apiTypes.mkString(","))
        }
      }
    } yield ()))
  }

  test("structured filesystem services require both native reads and default Scalamode; writes are explicit") {
    run(ZIO.scoped(for {
      fs <- FileSystem.open(".")
      route <- RpcWebSocket.route()
      readOnly = AgentToolWirings.withFilesystemServices(fs, None, executor, route, "ws://localhost:1/rpc")
      readWrite = AgentToolWirings.withFilesystemServices(fs, Some(fs), executor, route, "ws://localhost:1/rpc")
      filesystem = tool(
        ToolKey.Filesystem,
        function(FilesystemToolDefinition.ReadFileDefinition),
        function(FilesystemToolDefinition.ListFilesDefinition)
      )
      custom = tool(ToolKey.Scalamode, function(customDefinition))
      _ <- ZIO.foreachDiscard(List(Seq(scalamode), Seq(reads, scalamode), Seq(filesystem), Seq(filesystem, custom))) {
        input => readWrite(input).map(output => assert(output eq input))
      }
      input = Seq(other, filesystem, scalamode)
      read <- readOnly(input)
      both <- readWrite(input)
      again <- readWrite(read)
      hello <- AgentToolWirings.withHelloWorld(executor)(both)
    } yield {
      assertEquals(read.map(_.name), input.map(_.name))
      assert(read.head eq other)
      assert(read(1) eq filesystem)
      assertEquals(
        read.last.functions.head.definition.scalamode.get.services.head.apiTypes,
        List("fmgp.yallmo.fs.FsRead")
      )
      assertEquals(
        both.last.functions.head.definition.scalamode.get.services.head.apiTypes,
        List("fmgp.yallmo.tool.scalamode.FilesystemApi")
      )
      assert(again eq read) // No implicit escalation of an already-bound read-only service.
      assert(hello eq both)
    }))
  }

  test("default services bind filesystem plus hello only for the complete permitted filesystem view") {
    val fs = run(FileSystem.open("."))
    val filesystem = tool(
      ToolKey.Filesystem,
      function(FilesystemToolDefinition.ReadFileDefinition),
      function(FilesystemToolDefinition.ListFilesDefinition)
    )
    val defaults = AgentToolWirings.withDefaults(executor, Some(fs), Some(fs))
    val input = Seq(other, filesystem, scalamode)
    val wired = run(defaults(input))
    val services = wired.last.functions.head.definition.scalamode.get.services
    assertEquals(services.map(_.parameter), List("fs", "hello"))
    assertEquals(services.head.apiTypes, List("fmgp.yallmo.tool.scalamode.FilesystemApi"))
    assertEquals(services.last, HelloWorldService.inspection)
    assert(wired.head eq other)
    assert(wired(1) eq filesystem)
    assert(run(defaults(wired)) eq wired)
    val custom = Seq(filesystem, tool(ToolKey.Scalamode, function(customDefinition)))
    assert(run(defaults(custom)) eq custom)
    val missing = Seq(other, filesystem)
    assert(run(defaults(missing)) eq missing)
    for partial <- List(Seq(scalamode), Seq(reads, scalamode)) do {
      val selected = run(defaults(partial)).last.functions.head.definition.scalamode.get.services
      assertEquals(selected, List(HelloWorldService.inspection))
    }
    val noRoot = run(AgentToolWirings.withDefaults(executor)(input))
    assertEquals(noRoot.last.functions.head.definition.scalamode.get.services, List(HelloWorldService.inspection))
    val readOnly = run(AgentToolWirings.withDefaults(executor, Some(fs))(input))
    assertEquals(
      readOnly.last.functions.head.definition.scalamode.get.services.head.apiTypes,
      List("fmgp.yallmo.fs.FsRead")
    )
  }

  test("hello default wiring does not overwrite an explicitly selected filesystem service") {
    run(ZIO.scoped(for {
      route <- RpcWebSocket.route()
      input = Seq(other, scalamode, reads)
      filesystem <- AgentToolWirings.withFilesystem(executor, route, "ws://localhost:1/rpc")(input)
      wired <- AgentToolWirings.withHelloWorld(executor)(filesystem)
    } yield {
      assert(wired eq filesystem)
      assertEquals(wired.map(_.name), input.map(_.name))
      assertEquals(wired(1).functions.head.definition.scalamode.get.services.head.parameter, "reads")
    }))
  }
}
