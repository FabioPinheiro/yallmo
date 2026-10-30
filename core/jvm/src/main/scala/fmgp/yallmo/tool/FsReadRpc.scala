package fmgp.yallmo.tool

import fmgp.rpc.{Rpc, RpcErrorMapper}
import fmgp.yallmo.fs.{FsRead, FsError}

/** Infrastructure errors use a non-filesystem sentinel path and only the sanitized RPC code. Domain FsErrors bypass
  * this mapper. Keep the trusted child policy identical.
  */
private[tool] object FsReadRpc {
  given infrastructureErrors: RpcErrorMapper[FsError] =
    RpcErrorMapper(error => FsError.IoFailure("<rpc>", "RPC " + error.code.toString))
  val contract: Rpc[FsRead] = Rpc.derived[FsRead]
}
