package fmgp.yallmo.repl.terminal

import fmgp.yallmo.clientharness.ControlAction
import fmgp.yallmo.repl.ReplActiveAgent
import org.jline.keymap.KeyMap
import org.jline.reader.{Binding, LineReader, Reference}
import org.jline.utils.InfoCmp.Capability

/** Local menus never interpret or submit the editable draft as a menu command. */
private[terminal] final class TerminalMenus(
    reader: LineReader,
    activeAgent: Option[ReplActiveAgent],
    openProvider: Option[() => Unit] = None,
    openAgents: Option[() => Unit] = None,
    remoteConnection: Boolean = false
) {
  import TerminalMenus.*

  def install(): Unit = {
    installNormal()
    if !remoteConnection then installMenu(ProviderKeyMap)
    installMenu(AgentsKeyMap)
    if openAgents.isEmpty then fallbackAgents.foreach(_.install())
    if !remoteConnection then
      bind(LineReader.VICMD, ControlAction.Providers) {
        openProvider match {
          case Some(open) => open()
          case None       =>
            reader.setKeyMap(ProviderKeyMap)
            reader.printAbove(
              s"PROVIDER: catalog unavailable · ${ControlAction.Normal.terminalHint} · ${ControlAction.Edit.terminalHint}"
            )
        }
      }
    bind(LineReader.VICMD, ControlAction.Agents) {
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
    bind(LineReader.VICMD, ControlAction.Normal) { () }
    keys.setNomatch(TerminalControlBindings.reference(LineReader.VICMD, ControlAction.Normal))
    keys.setUnicode(TerminalControlBindings.reference(LineReader.VICMD, ControlAction.Normal))

    // Keep non-editing navigation and line/interrupt controls, but no Vi operators or Meta bindings.
    for key <- List("h", "l", "b", "B", "w", "W", "e", "E", "0", "^", "$", "\r", "\n", "\u0003") ++
        List(ControlAction.Next, ControlAction.Previous).flatMap(_.terminalKey)
    do Option(previous.getBound(key)).foreach(binding => keys.bind(binding, key))
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
    bind(LineReader.VICMD, ControlAction.Edit)(reader.setKeyMap(LineReader.VIINS))
  }

  private def installMenu(name: String): Unit = {
    val keys = new KeyMap[Binding]()
    keys.setNomatch(new Reference(LineReader.UNDEFINED_KEY))
    keys.setUnicode(new Reference(LineReader.UNDEFINED_KEY))
    keys.bind(new Reference(LineReader.SEND_BREAK), "\u0003")
    reader.getKeyMaps.put(name, keys)
    bind(name, ControlAction.Normal)(reader.setKeyMap(LineReader.VICMD))
    bind(name, ControlAction.Edit)(reader.setKeyMap(LineReader.VIINS))
  }

  private def bind(keyMap: String, control: ControlAction)(action: => Unit): Unit =
    TerminalControlBindings.bind(reader, keyMap, control)(action)
}

private[terminal] object TerminalMenus {
  val ProviderKeyMap = "provider"
  val AgentsKeyMap = "agents"

  def hintForKeyMap(keyMap: String, remoteConnection: Boolean = false, canSubmit: Boolean = false): String = {
    import ControlAction.*
    if remoteConnection && canSubmit then
      keyMap match {
        case LineReader.VICMD =>
          s"[TRUSTED LOCAL · UNAUTHENTICATED]  ${Agents.terminalHint} (configured agent) · ${Edit.terminalHint} (prompts) · ${Exit.terminalHint}"
        case AgentsKeyMap => s"[AGENTS]  Model/provider unknown · Enter: select · ${Normal.terminalHint}"
        case _ => "[CONNECTED]  Enter: submit · /new [name] · /history · /session list|resume <id> · /log · /exit"
      }
    else if remoteConnection then
      keyMap match {
        case LineReader.VICMD =>
          s"[READ-ONLY]  ${Agents.terminalHint} (agent preference) · ${Edit.terminalHint} (commands) · ${Exit.terminalHint}"
        case AgentsKeyMap => s"[AGENTS]  IDs only; capability unknown · Enter: prefer · ${Normal.terminalHint}"
        case _            => "[READ-ONLY]  /help · /session list|resume <id> · /history · /log · /exit"
      }
    else
      keyMap match {
        case LineReader.VICMD =>
          "[NORMAL]  " + List(Providers, Agents, Edit, Voice, Dictation, Speak, Exit)
            .map(_.terminalHint)
            .mkString(" · ")
        case TerminalProviderMode.KeyMapName =>
          s"[PROVIDER]  Up/Down: select · Enter: open/save · Esc: back/${Normal.terminalLabel} · ChatGPT details: login"
        case ProviderKeyMap => s"[PROVIDER]  Catalog unavailable · ${Normal.terminalHint} · ${Edit.terminalHint}"
        case AgentsKeyMap   =>
          s"[AGENTS]  Up/Down: select · Enter: activate · ${Normal.terminalHint} · ${Edit.terminalHint}"
        case _ => s"[INSERT]  ${Normal.terminalHint} · Enter: submit"
      }
  }
}
