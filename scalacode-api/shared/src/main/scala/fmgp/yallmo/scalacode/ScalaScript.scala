package fmgp.yallmo.scalacode

import zio.ZIO

/** Parent-loaded contract shared by the host and dynamically compiled programs.
  *
  * The host selects `R` at each call. Generated implementations adapt their concrete service requirements at the
  * trusted compilation boundary; callers must supply those services. This contract is not a proof of snippet
  * requirements.
  */
trait ScalaScript {
  def program[R]: ZIO[R, Throwable, Any]
}
