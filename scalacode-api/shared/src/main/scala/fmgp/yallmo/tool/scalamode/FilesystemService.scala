package fmgp.yallmo.tool.scalamode

import fmgp.rpc.RpcDispatcher
import fmgp.yallmo.fs.{FsRead, FsWrite}

/** Rooted filesystem services. Captures only explicitly supplied capabilities, never ambient host services.
  *
  * Use AgentToolWirings.withService after tool filtering, or ScalaTool.withServices directly. These factories do not
  * infer permission from native read tools: granting FsRead or FsWrite is a separate trusted host decision. For exact
  * delegation of filtered native read/list functions, retain AgentToolWirings.withFilesystem instead. Overloads without
  * a route use executor-provided loopback RPC with invocation-scoped tickets; the live executor layer reuses one
  * listener. Explicit route overloads retain caller-owned listener lifetime.
  */
object FilesystemService {

  /** Default service keeps hello available alongside the selected rooted filesystem view. */
  def default(read: FsRead, write: Option[FsWrite]): ScalamodeService = {
    val base = write.fold(FilesystemService.read(read))(value => readWrite(read, value))
    new ScalamodeService {
      val apiDocs = base.apiDocs
      override val scalaApi = base.scalaApi
      val binding = base.binding.copy(constants = Seq(HelloWorldService.inspection))
      override val localRpcBinding = base.localRpcBinding
    }
  }

  /** Streamed bytes, UTF-8 reads and structured direct-entry listings; no write API or dispatcher is bound. */
  def read(read: FsRead, route: ScalamodePlatform.RpcRoute, endpoint: String): ScalamodeService =
    onRoute(FilesystemService.read(read), route, endpoint)

  def read(read: FsRead): ScalamodeService =
    service(
      "fmgp.yallmo.fs.FsRead",
      "runRead",
      ReadApi,
      FilesystemRuntime.readContract.bind(read)
    )

  /** UTF-8 writes and mutations only. Does not expose reads, stream uploads or scoped writers. */
  def write(write: FsWrite, route: ScalamodePlatform.RpcRoute, endpoint: String): ScalamodeService =
    onRoute(FilesystemService.write(write), route, endpoint)

  def write(write: FsWrite): ScalamodeService =
    service(
      "fmgp.yallmo.tool.scalamode.FsWriteApi",
      "runWrite",
      WriteApi,
      FilesystemRuntime.writeContract.bind(FilesystemRuntime.writeView(write))
    )

  /** Both capabilities are supplied explicitly; they need not share a root, so the host must choose them deliberately.
    */
  def readWrite(
      read: FsRead,
      write: FsWrite,
      route: ScalamodePlatform.RpcRoute,
      endpoint: String
  ): ScalamodeService = onRoute(readWrite(read, write), route, endpoint)

  def readWrite(read: FsRead, write: FsWrite): ScalamodeService =
    service(
      "fmgp.yallmo.tool.scalamode.FilesystemApi",
      "run",
      ReadApi + "\n\n" + WriteApi +
        "\n\n// package fmgp.yallmo.tool.scalamode\ntrait FilesystemApi extends fmgp.yallmo.fs.FsRead with FsWriteApi",
      FilesystemRuntime.contract.bind(FilesystemRuntime.view(read, write))
    )

  private def service(
      apiType: String,
      runner: String,
      declarations: String,
      dispatcher: RpcDispatcher
  ): ScalamodeService = new ScalamodeService {
    val apiDocs =
      "Explicit rooted filesystem capability available as fs. Paths are relative; '.' denotes the root. " +
        "Empty/absolute paths, parent traversal and symlinks are rejected. FsError preserves domain failures; " +
        "transport failures become FsError.IoFailure('.', 'RPC <code>'). Effects require no environment. " +
        "Writes require explicit modes and existing parent directories. No stream uploads or scoped writers are exposed."
    override val scalaApi = Some(declarations + s"\n\n// Available as fs: $apiType")
    val binding = ScalamodeService.Binding(
      parameter = "fs",
      apiTypes = Seq(apiType),
      runner = s"fmgp.yallmo.tool.scalamode.FilesystemRuntime.$runner",
      errorType = "fmgp.yallmo.fs.FsError"
    )
    override val localRpcBinding = Some(dispatcher)
  }

  private def onRoute(base: ScalamodeService, route: ScalamodePlatform.RpcRoute, endpoint: String): ScalamodeService =
    new ScalamodeService {
      val apiDocs = base.apiDocs
      override val scalaApi = base.scalaApi
      val binding = base.binding
      override val rpcBinding = Some(ScalamodeService.RpcBinding(endpoint, route, base.localRpcBinding.get))
    }

  private val ReadApi = """// package fmgp.yallmo.fs
trait FsRead {
  def readBytes(path: String): zio.stream.ZStream[Any, fmgp.yallmo.fs.FsError, Byte]
  def readUtf8(path: String): zio.IO[fmgp.yallmo.fs.FsError, String]
  def list(path: String): zio.IO[fmgp.yallmo.fs.FsError, zio.Chunk[String]]
}"""

  private val WriteApi = """// package fmgp.yallmo.tool.scalamode
// import fmgp.yallmo.tool.scalamode.FilesystemModes
// Safe mode values: FilesystemModes.{createNew, replace, append, emptyOnly, recursive, failIfExists, replaceFile}.
// Mode types and FsError are in fmgp.yallmo.fs; use FilesystemModes rather than the original enum companions.
trait FsWriteApi {
  def writeUtf8(path: String, contents: String, mode: fmgp.yallmo.fs.WriteMode): zio.IO[fmgp.yallmo.fs.FsError, Unit]
  def createDirectories(path: String): zio.IO[fmgp.yallmo.fs.FsError, Unit]
  def deleteFile(path: String): zio.IO[fmgp.yallmo.fs.FsError, Unit]
  def deleteDirectory(path: String, mode: fmgp.yallmo.fs.DirectoryDeleteMode): zio.IO[fmgp.yallmo.fs.FsError, Unit]
  def move(source: String, destination: String, mode: fmgp.yallmo.fs.MoveMode): zio.IO[fmgp.yallmo.fs.FsError, Unit]
}"""
}
