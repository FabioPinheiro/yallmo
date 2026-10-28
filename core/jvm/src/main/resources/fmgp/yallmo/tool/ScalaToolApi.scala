/** Trusted runtime API compiled alongside every Scala tool snippet, never included in the model's prompt.
  *
  * Keep privileged implementations narrow: submitted code cannot declare its own `@assumeSafe` methods.
  */
object tool {

  /** Prints a fixed greeting without granting general console or JVM access to the caller. */
  @caps.assumeSafe
  def api: Unit = println("Yes Tool API is available")
}
