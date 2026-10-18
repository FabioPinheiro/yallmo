package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.ReplActiveAgent
import org.jline.keymap.KeyMap
import org.jline.reader.{Binding, LineReader, Reference, Widget}
import org.jline.utils.InfoCmp.Capability

/** Local menus never interpret or submit the editable draft as a menu command. */
private[terminal] final class TerminalMenus(
    reader: LineReader,
    activeAgent: Option[ReplActiveAgent],
    openProvider: Option[() => Unit] = None,
    openAgents: Option[() => Unit] = None
) {
  import TerminalMenus.*

  def install(): Unit = {
    installNormal()
    installMenu(ProviderKeyMap)
    installMenu(AgentsKeyMap)
    if openAgents.isEmpty then fallbackAgents.foreach(_.install())
    bind(LineReader.VICMD, "p", "open-provider") {
      openProvider match {
        case Some(open) => open()
        case None       =>
          reader.setKeyMap(ProviderKeyMap)
          reader.printAbove("PROVIDER: catalog unavailable · Esc: NORMAL · i: edit")
      }
    }
    bind(LineReader.VICMD, "a", "open-agents") {
      openAgents match {
        case Some(open) => open()
        case None       => fallbackAgents.foreach(_.open(Vector.empty, activeAgent))
      }
    }
  }

  private lazy val fallbackAgents: Option[TerminalAgentMode] = reader match {
    case line: StatusLineReader => Some(new TerminalAgentMode(line))
    case _                      => None
  }

  /** NORMAL is a command menu, not the full Vi editor: only `i` may enter INSERT. */
  private def installNormal(): Unit = {
    val previous = reader.getKeyMaps.get(LineReader.VICMD)
    val keys = new KeyMap[Binding]()
    keys.setAmbiguousTimeout(50L)
    reader.getKeyMaps.put(LineReader.VICMD, keys)
    bind(LineReader.VICMD, "\u001b", "normal-ignore") { () }
    keys.setNomatch(new Reference("normal-ignore"))
    keys.setUnicode(new Reference("normal-ignore"))

    // Keep non-editing navigation and line/interrupt controls, but no Vi operators or Meta bindings.
    for key <- List("h", "j", "k", "l", "b", "B", "w", "W", "e", "E", "0", "^", "$", "\r", "\n", "\u0003") do
      Option(previous.getBound(key)).foreach(binding => keys.bind(binding, key))
    for (capability, widget, sequences) <- List(
        (Capability.key_left, LineReader.BACKWARD_CHAR, List("\u001b[D", "\u001bOD")),
        (Capability.key_right, LineReader.FORWARD_CHAR, List("\u001b[C", "\u001bOC")),
        (Capability.key_up, LineReader.UP_HISTORY, List("\u001b[A", "\u001bOA")),
        (Capability.key_down, LineReader.DOWN_HISTORY, List("\u001b[B", "\u001bOB")),
        (Capability.key_home, LineReader.BEGINNING_OF_LINE, List("\u001b[H", "\u001bOH")),
        (Capability.key_end, LineReader.END_OF_LINE, List("\u001b[F", "\u001bOF"))
      )
    do
      (sequences ++ Option(KeyMap.key(reader.getTerminal, capability)).filter(_.nonEmpty))
        .foreach(key => keys.bind(new Reference(widget), key))
    bind(LineReader.VICMD, "i", "normal-insert")(reader.setKeyMap(LineReader.VIINS))
  }

  private def installMenu(name: String): Unit = {
    val keys = new KeyMap[Binding]()
    keys.setNomatch(new Reference(LineReader.UNDEFINED_KEY))
    keys.setUnicode(new Reference(LineReader.UNDEFINED_KEY))
    keys.bind(new Reference(LineReader.SEND_BREAK), "\u0003")
    reader.getKeyMaps.put(name, keys)
    bind(name, "\u001b", s"$name-normal")(reader.setKeyMap(LineReader.VICMD))
    bind(name, "i", s"$name-insert")(reader.setKeyMap(LineReader.VIINS))
  }

  private def bind(keyMap: String, key: String, name: String)(action: => Unit): Unit = {
    reader.getWidgets.put(
      name,
      new Widget {
        override def apply(): Boolean = { action; true }
      }
    )
    reader.getKeyMaps.get(keyMap).bind(new Reference(name), key)
  }
}

private[terminal] object TerminalMenus {
  val ProviderKeyMap = "provider"
  val AgentsKeyMap = "agents"

  def hintForKeyMap(keyMap: String): String = keyMap match {
    case LineReader.VICMD =>
      "[NORMAL]  p: provider · a: agents · i: edit · v: voice input · m: browser mic · t: speak reply · q: exit"
    case TerminalProviderMode.KeyMapName =>
      "[PROVIDER]  Up/Down: select · Enter: open/save · Esc: back/NORMAL · ChatGPT details: login"
    case ProviderKeyMap => "[PROVIDER]  Catalog unavailable · Esc: NORMAL · i: edit"
    case AgentsKeyMap   => "[AGENTS]  Up/Down: select · Enter: activate · Esc: NORMAL · i: edit"
    case _              => "[INSERT]  Esc: NORMAL · Enter: submit"
  }
}
