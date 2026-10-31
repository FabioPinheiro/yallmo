package fmgp.yallmo.scalacode

import scala.annotation.experimental

/** Trusted runtime API imported by restricted Scala tool snippets, never included in the model's prompt.
  *
  * Keep privileged implementations narrow: submitted code cannot declare its own `@assumeSafe` methods.
  */
object ScalaToolApi {

  /** Prints a fixed greeting without granting general console or JVM access to the caller. */
  @experimental
  @caps.assumeSafe
  def api: Unit = scala.Console.err.println("Yes Tool API is available")
}
