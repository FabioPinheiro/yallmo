package fmgp.yallmo.repl.terminal

/** A terminal interaction mode that installs its widgets and keybindings into the line editor. */
private[terminal] trait TerminalMode {

  /** Have dide effect */
  def install(): Unit
}
