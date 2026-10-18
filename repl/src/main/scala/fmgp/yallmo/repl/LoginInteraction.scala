package fmgp.yallmo.repl

import zio.Task

/** Authentication method for a selected ChatGPT provider profile. */
enum LoginMethod {
  case Browser
}

/** Terminal-private provider authentication input; callbacks never enter command parsing or history.
  *
  * TODO rename this and reuse on the other MODES
  */
trait LoginInteraction {
  def show(message: String): Task[Unit]
  def readRedirect: Task[Option[String]]
  def awaitCancellation: Task[Unit]
}
