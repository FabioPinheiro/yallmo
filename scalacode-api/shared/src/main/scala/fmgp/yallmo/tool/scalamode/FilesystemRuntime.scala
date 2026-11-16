package fmgp.yallmo.tool.scalamode

import fmgp.rpc.*
import fmgp.yallmo.fs.*
import zio.*
import zio.json.*

/** Reviewed concrete contracts and runners. No filesystem or transport can be acquired by submitted code. */
object FilesystemRuntime {
  private given RpcErrorMapper[FsError] =
    RpcErrorMapper(error => FsError.IoFailure(".", s"RPC ${error.code}"))
  private given JsonCodec[WriteMode] = DeriveJsonCodec.gen[WriteMode]
  private given JsonCodec[DirectoryDeleteMode] = DeriveJsonCodec.gen[DirectoryDeleteMode]
  private given JsonCodec[MoveMode] = DeriveJsonCodec.gen[MoveMode]

  val readContract: Rpc[FsRead] = Rpc.derived[FsRead]("yallmo.fs.read", 1)
  val writeContract: Rpc[FsWriteApi] = Rpc.derived[FsWriteApi]("yallmo.fs.write", 1)
  val contract: Rpc[FilesystemApi] = Rpc.derived[FilesystemApi]("yallmo.fs.read-write", 1)

  private[scalamode] def writeView(write: FsWrite): FsWriteApi = new FsWriteApi {
    def writeUtf8(path: String, contents: String, mode: WriteMode) = write.writeUtf8(path, contents, mode)
    def createDirectories(path: String) = write.createDirectories(path)
    def deleteFile(path: String) = write.deleteFile(path)
    def deleteDirectory(path: String, mode: DirectoryDeleteMode) = write.deleteDirectory(path, mode)
    def move(source: String, destination: String, mode: MoveMode) = write.move(source, destination, mode)
  }

  private[scalamode] def view(read: FsRead, write: FsWrite): FilesystemApi = new FilesystemApi {
    def readBytes(path: String) = read.readBytes(path)
    override def readUtf8(path: String) = read.readUtf8(path)
    def list(path: String) = read.list(path)
    def writeUtf8(path: String, contents: String, mode: WriteMode) = write.writeUtf8(path, contents, mode)
    def createDirectories(path: String) = write.createDirectories(path)
    def deleteFile(path: String) = write.deleteFile(path)
    def deleteDirectory(path: String, mode: DirectoryDeleteMode) = write.deleteDirectory(path, mode)
    def move(source: String, destination: String, mode: MoveMode) = write.move(source, destination, mode)
  }

  def runRead[E, A](evaluate: FsRead => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ScalaToolRuntime.runRpc(readContract)(evaluate)

  def runWrite[E, A](evaluate: FsWriteApi => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ScalaToolRuntime.runRpc(writeContract)(evaluate)

  def run[E, A](evaluate: FilesystemApi => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ScalaToolRuntime.runRpc(contract)(evaluate)
}
