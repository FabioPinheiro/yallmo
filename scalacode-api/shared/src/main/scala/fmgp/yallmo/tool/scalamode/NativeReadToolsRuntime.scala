package fmgp.yallmo.tool.scalamode

import fmgp.rpc.*
import zio.*

/** Concrete wire contract and proxy construction are compiled once, not injected into submitted source. The host binds
  * only its selected native functions; these client views never grant a raw filesystem capability.
  */
object NativeReadToolsRuntime {
  private given RpcErrorMapper[ReadToolError] = RpcErrorMapper(error => ReadToolError("RpcError", error.code.toString))
  val contract: Rpc[NativeReadTools] = Rpc.derived[NativeReadTools]

  def run[E, A](evaluate: NativeReadTools => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ScalaToolRuntime.runRpc(contract)(evaluate)

  def runReadFile[E, A](evaluate: NativeReadFile => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ScalaToolRuntime.runRpc(contract) { client =>
      evaluate(new NativeReadFile {
        def readFile(path: String) = client.readFile(path)
      })
    }

  def runListFiles[E, A](evaluate: NativeListFiles => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ScalaToolRuntime.runRpc(contract) { client =>
      evaluate(new NativeListFiles {
        def listFiles(path: String) = client.listFiles(path)
      })
    }
}
