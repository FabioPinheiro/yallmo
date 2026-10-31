package fmgp.yallmo.scalacode

import scala.annotation.experimental

/** Trusted runtime API imported by restricted Scala tool snippets, never included in the model's prompt.
  *
  * Keep privileged implementations narrow: submitted code cannot declare its own `@assumeSafe` methods.
  */
object ScalaToolApi {

  type ZIO[-R, +E, +A] = zio.ZIO[R, E, A]

  /** Restricted constructors, not the ZIO companion: no runtime, services or ambient I/O is granted.
    *
    * Callbacks are still checked as safe code by the snippet compiler. Trace is supplied by the trusted runner.
    */
  object ZIO {
    @experimental
    @caps.assumeSafe
    def succeed[A](value: => A)(using zio.Trace): zio.UIO[A] = zio.ZIO.succeed(value)

    @experimental
    @caps.assumeSafe
    def fail[E](error: => E)(using zio.Trace): zio.IO[E, Nothing] = zio.ZIO.fail(error)

    @experimental
    @caps.assumeSafe
    def attempt[A](value: => A)(using zio.Trace): zio.Task[A] = zio.ZIO.attempt(value)

    @experimental
    @caps.assumeSafe
    def fromEither[E, A](value: => Either[E, A])(using zio.Trace): zio.IO[E, A] = zio.ZIO.fromEither(value)

    @experimental
    @caps.assumeSafe
    def foreach[A, R, E, B](values: Iterable[A])(f: A => zio.ZIO[R, E, B])(using
        zio.Trace
    ): zio.ZIO[R, E, List[B]] = zio.ZIO.foreach(values)(f).map(_.toList)

    @experimental
    @caps.assumeSafe
    def unit(using zio.Trace): zio.UIO[Unit] = zio.ZIO.unit
  }

  /** Prints a fixed greeting without granting general console or JVM access to the caller. */
  @experimental
  @caps.assumeSafe
  def api: Unit = scala.Console.err.println("Yes Tool API is available")
}
