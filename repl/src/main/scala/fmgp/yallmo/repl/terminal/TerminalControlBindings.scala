package fmgp.yallmo.repl.terminal

import fmgp.yallmo.clientharness.ControlAction
import org.jline.reader.{LineReader, Reference, Widget}

/** JLine adapter for catalog actions. IDs are scoped by keymap so menu handlers cannot overwrite NORMAL handlers. */
private[terminal] object TerminalControlBindings {
  def widgetName(keyMap: String, control: ControlAction): String = s"yallmo-$keyMap-${control.id}"

  def reference(keyMap: String, control: ControlAction): Reference = new Reference(widgetName(keyMap, control))

  def bind(reader: LineReader, keyMap: String, control: ControlAction)(action: => Unit): Unit =
    bindKeys(reader, keyMap, control, control.terminalKey.toList)(action)

  /** Extra native sequences (arrows, interrupts) retain the same action identity as its catalog shortcut. */
  def bindKeys(reader: LineReader, keyMap: String, control: ControlAction, keys: Iterable[String])(
      action: => Unit
  ): Unit = {
    reader.getWidgets.put(
      widgetName(keyMap, control),
      new Widget {
        override def apply(): Boolean = { action; true }
      }
    )
    keys.foreach(key => reader.getKeyMaps.get(keyMap).bind(reference(keyMap, control), key))
  }
}
