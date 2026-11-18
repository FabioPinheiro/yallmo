package fmgp.yallmo.server.application

import fmgp.yallmo.tool.scalamode.ScalaCodeExecutor
import java.io.{FileNotFoundException, IOException, InputStream, UncheckedIOException}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.*

/** Fixed nested JS IR resources, copied without unpacking archives or consulting application/storage paths. */
private[server] object BundledJsArtifacts {
  val artifactNames: Seq[String] = Seq("scalacode-api", "filesystem", "rpc", "model").map(name =>
    s"scala-${ScalaCodeExecutor.ScalaVersion}/yallmo-${name}_sjs1_3-2.0.0-SNAPSHOT.jar"
  )
  val resourceNames: Seq[String] = artifactNames.map(name => s"scalamode-js/$name")

  /** The caller owns this root until scope close. Failed/cancelled staging releases its child scope immediately. */
  def stage(
      openResource: String => InputStream = name => getClass.getClassLoader.getResourceAsStream(name),
      removeRoot: Path => Task[Unit] = remove
  ): ZIO[Scope, Throwable, Path] = ZIO.uninterruptibleMask { restore =>
    for {
      parent <- ZIO.service[Scope]
      ownership <- parent.fork
      root <- restore {
        for {
          root <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-scalamode-js-"))) { root =>
            // Optional JS cleanup is best-effort for filesystem I/O only, not interruption or programming defects.
            removeRoot(root).catchSomeCause {
              case cause
                  if cause.failures.nonEmpty && cause.failures.forall(isCleanupIo) &&
                    cause.defects.isEmpty && !cause.isInterrupted =>
                ZIO.logWarningCause("Bundled JS artifact cleanup failed; temporary files may remain", cause)
            }.orDie
          }
          _ <- ZIO.foreachDiscard(resourceNames.zip(artifactNames)) { case (resource, artifact) =>
            // Copy/close must finish before interruption can remove the directory or close its input stream.
            ZIO.attemptBlocking {
              Using.resource(Option(openResource(resource)).getOrElse(throw new FileNotFoundException(resource))) {
                input =>
                  val target = root.resolve(artifact)
                  Files.createDirectories(target.getParent)
                  Files.copy(input, target)
                  ()
              }
            }.uninterruptible
          }
        } yield root
      }.provideEnvironment(ZEnvironment[Scope](ownership)).onExit {
        case Exit.Success(_) => ZIO.unit
        case failure         => ownership.close(failure)
      }
    } yield root
  }

  private def isCleanupIo(error: Throwable): Boolean = error match {
    case _: IOException | _: UncheckedIOException => true
    case _                                        => false
  }

  private def remove(root: Path): Task[Unit] = ZIO.attemptBlocking {
    Using.resource(Files.walk(root)) { paths =>
      paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))
    }
  }
}
