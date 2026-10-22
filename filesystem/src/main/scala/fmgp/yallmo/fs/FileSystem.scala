package fmgp.yallmo.fs

import java.io.{BufferedWriter, IOException}
import java.nio.ByteBuffer
import java.nio.channels.{Channels, FileChannel}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{
  DirectoryNotEmptyException,
  FileAlreadyExistsException,
  Files,
  InvalidPathException,
  LinkOption,
  NoSuchFileException,
  NotDirectoryException,
  OpenOption,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*
import zio.*
import zio.stream.ZStream

/** Concrete filesystem rooted at one application-configured directory, not the machine's filesystem root.
  *
  * Construct with FileSystem.open. All operation paths are relative to that root. Keep this combined capability in
  * trusted application code and supply the narrower read/write interfaces to callers as appropriate. These are static
  * interfaces over the same object, not runtime-isolated views. Validation is not an OS sandbox: concurrent filesystem
  * mutation can race with path checks, and hard-link aliases are not prevented.
  */
final class FileSystem private (rootPath: Path) extends FsJsonlRead with FsJsonlWrite {
  import FileSystem.*

  override def readBytes(path: String): ZStream[Any, FsError, Byte] =
    ZStream.unwrapScoped {
      openChannel(path, None).map { channel =>
        ZStream.fromInputStream(Channels.newInputStream(channel)).mapError(error => fileError(path, error)) ++
          ZStream.fromZIO(blocking(path)(channel.close())).drain
      }
    }

  override def list(path: String): IO[FsError, Chunk[String]] =
    for {
      target <- resolve(path)
      _ <- requireDirectory(target, path)
      entries <- directoryEntries(target, path)
      _ <- ZIO.foreachDiscard(entries)(entry => attributes(entry, path).flatMap(rejectSymlink(_, path)))
    } yield Chunk.fromIterable(entries.map(_.getFileName.toString).sorted)

  override def writeBytes[R, E](path: String, bytes: ZStream[R, E, Byte], mode: WriteMode): ZIO[R, E | FsError, Unit] =
    ZIO.scoped[R] {
      for {
        channel <- openChannel(path, Some(mode))
        _ <- bytes.chunks.foreach { chunk =>
          blocking(path) {
            val buffer = ByteBuffer.wrap(chunk.toArray)
            while buffer.hasRemaining do channel.write(buffer)
          }
        }
        // Close on the success path as well, so a close failure is a typed error rather than a finalizer defect.
        _ <- blocking(path)(channel.close())
      } yield ()
    }

  override def openUtf8Writer(path: String, mode: WriteMode): ZIO[Scope, FsError, FsUtf8Writer] =
    for {
      channel <- openChannel(path, Some(mode))
      lock <- Semaphore.make(1)
      writer <- blocking(path)(new BufferedWriter(Channels.newWriter(channel, UTF_8.newEncoder(), -1)))
      _ <- ZIO.addFinalizer(lock.withPermit(blocking(path)(writer.close())).orDieWith(cleanupError))
    } yield new FsUtf8Writer {
      override def write(text: String): IO[FsError, Unit] = lock.withPermit(blocking(path)(writer.write(text)))
      override def flush: IO[FsError, Unit] = lock.withPermit(blocking(path)(writer.flush()))
      override def sync: IO[FsError, Unit] = lock.withPermit(blocking(path) {
        writer.flush()
        channel.force(true)
      })
    }

  override def createDirectories(path: String): IO[FsError, Unit] =
    for {
      target <- resolve(path)
      existing <- attributesIfExists(target, path)
      _ <- ZIO.foreachDiscard(existing)(attrs => ensure(attrs.isDirectory, FsError.WrongType(path, "directory")))
      _ <- blocking(path)(Files.createDirectories(target))
    } yield ()

  override def deleteFile(path: String): IO[FsError, Unit] =
    for {
      target <- resolve(path, allowRoot = false)
      _ <- requireFile(target, path)
      _ <- blocking(path)(Files.delete(target))
    } yield ()

  override def deleteDirectory(path: String, mode: DirectoryDeleteMode): IO[FsError, Unit] =
    for {
      target <- resolve(path, allowRoot = false)
      _ <- requireDirectory(target, path)
      _ <- mode match {
        case DirectoryDeleteMode.EmptyOnly => blocking(path)(Files.delete(target))
        case DirectoryDeleteMode.Recursive => walkDirectory(target, path, delete = true)
      }
    } yield ()

  override def move(source: String, destination: String, mode: MoveMode): IO[FsError, Unit] =
    for {
      from <- resolve(source, allowRoot = false)
      to <- resolve(destination, allowRoot = false)
      _ <- ensure(from != to, FsError.InvalidPath(destination, "Move endpoints must differ"))
      sourceAttrs <- attributes(from, source)
      _ <- ensure(sourceAttrs.isDirectory || sourceAttrs.isRegularFile, FsError.WrongType(source, "file or directory"))
      _ <- ensure(
        !sourceAttrs.isDirectory || !to.startsWith(from),
        FsError.InvalidPath(destination, "Cannot move a directory into itself")
      )
      _ <- requireDirectory(to.getParent, destination)
      existing <- attributesIfExists(to, destination)
      _ <- ZIO.foreachDiscard(existing) { attrs =>
        for {
          _ <- ensure(mode != MoveMode.FailIfExists && !attrs.isDirectory, FsError.AlreadyExists(destination))
          _ <- ensure(sourceAttrs.isRegularFile, FsError.WrongType(source, "regular file when replacing a file"))
          _ <- ensure(attrs.isRegularFile, FsError.WrongType(destination, "regular file"))
        } yield ()
      }
      _ <- if sourceAttrs.isDirectory then walkDirectory(from, source, delete = false) else ZIO.unit
      sameStore <- blocking(destination)(Files.getFileStore(from) == Files.getFileStore(to.getParent))
      _ <- ensure(sameStore, FsError.IoFailure(destination, "Cross-filesystem moves are not supported"))
      options = mode match {
        case MoveMode.FailIfExists => Array.empty[StandardCopyOption]
        case MoveMode.ReplaceFile  => Array(StandardCopyOption.REPLACE_EXISTING)
      }
      _ <- blocking(destination)(Files.move(from, to, options*))
    } yield ()

  private def openChannel(path: String, mode: Option[WriteMode]): ZIO[Scope, FsError, FileChannel] =
    ZIO.acquireRelease {
      for {
        target <- resolve(path)
        options <- mode match {
          case None =>
            requireFile(target, path).as(Array[OpenOption](StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
          case Some(writeMode) =>
            for {
              existing <- attributesIfExists(target, path)
              _ <- ZIO.foreachDiscard(existing) { attrs =>
                for {
                  _ <- ensure(writeMode != WriteMode.CreateNew, FsError.AlreadyExists(path))
                  _ <- ensure(attrs.isRegularFile, FsError.WrongType(path, "regular file"))
                } yield ()
              }
              _ <- requireDirectory(target.getParent, path)
            } yield writeOptions(writeMode)
        }
        channel <- blocking(path)(FileChannel.open(target, options*))
      } yield channel
    }(channel => blocking(path)(channel.close()).orDieWith(cleanupError))

  private def validateRoot(label: String): IO[FsError, Unit] =
    for {
      _ <- checkExistingPath(rootPath, label)
      _ <- requireDirectory(rootPath, label)
    } yield ()

  private def resolve(path: String, allowRoot: Boolean = true): IO[FsError, Path] =
    for {
      _ <- ensure(path.nonEmpty, FsError.InvalidPath(path, "Path must not be empty"))
      relative <- blocking(path)(Path.of(path))
      _ <- ensure(!relative.isAbsolute, FsError.InvalidPath(path, "Path must be relative to the configured root"))
      _ <- ensure(
        !relative.iterator().asScala.exists(_.toString == ".."),
        FsError.InvalidPath(path, "Parent traversal is forbidden")
      )
      target = rootPath.resolve(relative).normalize()
      _ <- ensure(target.startsWith(rootPath), FsError.InvalidPath(path, "Path escapes the configured root"))
      _ <- ensure(
        allowRoot || target != rootPath,
        FsError.InvalidPath(path, "Cannot delete or move the configured root")
      )
      _ <- checkExistingPath(target, path)
      _ <- requireDirectory(rootPath, path)
    } yield target

  /** Checks each existing component without following links. Missing suffixes are allowed for creation. */
  private def checkExistingPath(path: Path, label: String): IO[FsError, Unit] = {
    def loop(current: Path, names: List[Path]): IO[FsError, Unit] = names match {
      case Nil          => ZIO.unit
      case name :: tail =>
        val next = current.resolve(name)
        for {
          existing <- attributesIfExists(next, label)
          _ <- existing match {
            case None        => ZIO.unit
            case Some(attrs) =>
              for {
                _ <- rejectSymlink(attrs, label)
                _ <- ensure(tail.isEmpty || attrs.isDirectory, FsError.WrongType(label, "directory"))
                _ <- loop(next, tail)
              } yield ()
          }
        } yield ()
    }
    loop(path.getRoot, path.iterator().asScala.toList)
  }

  /** Traverses explicitly checked, non-symlink entries. Failed recursive deletion may already have removed entries. */
  private def walkDirectory(path: Path, label: String, delete: Boolean): IO[FsError, Unit] =
    for {
      entries <- directoryEntries(path, label)
      _ <- ZIO.foreachDiscard(entries) { entry =>
        for {
          attrs <- attributes(entry, label)
          _ <- rejectSymlink(attrs, label)
          _ <-
            if attrs.isDirectory then walkDirectory(entry, label, delete)
            else
              for {
                _ <- ensure(attrs.isRegularFile, FsError.WrongType(label, "regular file or directory"))
                _ <- if delete then blocking(label)(Files.delete(entry)) else ZIO.unit
              } yield ()
        } yield ()
      }
      _ <- if delete then blocking(label)(Files.delete(path)) else ZIO.unit
    } yield ()
}

object FileSystem {

  /** Validates one existing, symlink-free root directory and returns a concrete filesystem instance.
    *
    * rootPath is trusted application configuration (absolute or relative to the application's working directory).
    * Operation paths are always relative to this directory; no operation uses the machine root as its logical root.
    */
  def open(rootPath: String): IO[FsError, FileSystem] =
    for {
      _ <- ensure(rootPath.nonEmpty, FsError.InvalidPath(rootPath, "Root path must not be empty"))
      root <- blocking(rootPath)(Path.of(rootPath).toAbsolutePath.normalize())
      fs = new FileSystem(root)
      _ <- fs.validateRoot(rootPath)
    } yield fs

  private def ensure(condition: Boolean, error: => FsError): IO[FsError, Unit] =
    if condition then ZIO.unit else ZIO.fail(error)

  private def attributes(path: Path, label: String): IO[FsError, BasicFileAttributes] =
    blocking(label)(Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS))

  private def attributesIfExists(path: Path, label: String): IO[FsError, Option[BasicFileAttributes]] =
    attributes(path, label).asSome.catchSome { case FsError.NotFound(_) => ZIO.none }

  private def rejectSymlink(attrs: BasicFileAttributes, label: String): IO[FsError, Unit] =
    ensure(!attrs.isSymbolicLink, FsError.InvalidPath(label, "Symbolic links are forbidden"))

  private def requireDirectory(path: Path, label: String): IO[FsError, Unit] =
    for {
      attrs <- attributes(path, label)
      _ <- ensure(attrs.isDirectory, FsError.WrongType(label, "directory"))
    } yield ()

  private def requireFile(path: Path, label: String): IO[FsError, Unit] =
    for {
      attrs <- attributes(path, label)
      _ <- ensure(attrs.isRegularFile, FsError.WrongType(label, "regular file"))
    } yield ()

  private def directoryEntries(path: Path, label: String): IO[FsError, Vector[Path]] =
    ZIO.scoped {
      for {
        stream <- ZIO.acquireRelease(blocking(label)(Files.newDirectoryStream(path)))(stream =>
          blocking(label)(stream.close()).orDieWith(cleanupError)
        )
        entries <- blocking(label)(stream.iterator().asScala.toVector)
        _ <- blocking(label)(stream.close())
      } yield entries
    }

  private def writeOptions(mode: WriteMode): Array[OpenOption] = mode match {
    case WriteMode.CreateNew =>
      Array(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
    case WriteMode.Replace =>
      Array(
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        LinkOption.NOFOLLOW_LINKS
      )
    case WriteMode.Append =>
      Array(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
  }

  private def blocking[A](label: String)(effect: => A): IO[FsError, A] =
    ZIO.attemptBlocking(effect).mapError(error => fileError(label, error))

  private def fileError(label: String, error: Throwable): FsError = error match {
    case invalid: InvalidPathException => FsError.InvalidPath(label, invalid.getReason)
    case _: NoSuchFileException        => FsError.NotFound(label)
    case _: FileAlreadyExistsException => FsError.AlreadyExists(label)
    case _: DirectoryNotEmptyException => FsError.DirectoryNotEmpty(label)
    case _: NotDirectoryException      => FsError.WrongType(label, "directory")
    case other => FsError.IoFailure(label, Option(other.getMessage).getOrElse(other.getClass.getSimpleName))
  }

  // Scope finalizers cannot fail in the typed channel. Do not silently swallow cleanup I/O failures.
  private def cleanupError(error: FsError): IOException = new IOException(s"Filesystem cleanup failed: $error")
}
