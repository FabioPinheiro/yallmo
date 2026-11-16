package fmgp.yallmo.tool.scalamode

import zio.*

/** Pure greeting capability available only through an explicitly bound Scalamode service. */
@scala.annotation.experimental
@caps.assumeSafe
object HelloWorldApi {
  @caps.assumeSafe
  def helloWorld: String = "Hello world"
}

/** Compiled runner: no RPC listener, filesystem or ambient host capability is required. */
object HelloWorldRuntime {
  @scala.annotation.experimental
  def run[E, A](evaluate: HelloWorldApi.type => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ScalaToolRuntime.runPure(_ => evaluate(HelloWorldApi))
}

/** Documentation and execution select the same compiled greeting API. */
object HelloWorldService extends ScalamodeService {
  val apiDocs = "hello.helloWorld: String returns exactly Hello world. Plain values and ZIO[Any, E, A] are supported."
  override val scalaApi = Some(
    "object HelloWorldApi { def helloWorld: String }\n// Available as hello: HelloWorldApi.type"
  )
  val binding = ScalamodeService.Binding(
    parameter = "hello",
    apiTypes = Seq("fmgp.yallmo.tool.scalamode.HelloWorldApi.type"),
    runner = "fmgp.yallmo.tool.scalamode.HelloWorldRuntime.run"
  )
}
