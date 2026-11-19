package fmgp.yallmo.scalacode.fixture

import fmgp.rpc.Rpc
import fmgp.yallmo.tool.scalamode.ScalaToolRuntime
import zio.*

/** Test-only narrow compiled artifact: neither API nor runtime comes from generated source or core's test classes. */
@scala.annotation.experimental
@caps.assumeSafe
object Arithmetic {
  @caps.assumeSafe
  def twice(value: Int): Int = value * 2
}

object ArithmeticRuntime {
  @scala.annotation.experimental
  def run[E, A](evaluate: Arithmetic.type => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ScalaToolRuntime.runPure(_ => evaluate(Arithmetic))
}

trait GreetingApi {
  def greet(name: String): Task[String]
}

object GreetingRuntime {
  val contract: Rpc[GreetingApi] = Rpc.derived[GreetingApi]("test.greeting", 1)

  def run[E, A](evaluate: GreetingApi => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ScalaToolRuntime.runRpc(contract)(evaluate)
}
