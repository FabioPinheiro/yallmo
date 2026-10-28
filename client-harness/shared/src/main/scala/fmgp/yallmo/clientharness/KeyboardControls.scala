package fmgp.yallmo.clientharness

/** Platform-neutral command catalog. Clients register only actions they can implement; no capability is implied.
  *
  * @param id
  *   Stable machine identifier, independent of enum names and display wording. Adapters scope it for native bindings.
  * @param webLabel
  *   Browser-facing command wording, also used by website command search.
  * @param terminalLabel
  *   Compact terminal-facing wording; never used as a widget identifier.
  * @param shortcut
  *   Optional default key. Catalog membership alone never installs a binding.
  */
enum ControlAction(val id: String, val webLabel: String, val terminalLabel: String, val shortcut: Option[String]) {
  case Edit extends ControlAction("edit", "Edit message", "edit", Some("i"))
  case Agents extends ControlAction("agents", "Choose configured agent", "agents", Some("a"))
  case Next extends ControlAction("next", "Next control", "next", Some("j"))
  case Previous extends ControlAction("previous", "Previous control", "previous", Some("k"))
  case Normal extends ControlAction("normal", "NORMAL", "NORMAL", Some("Escape"))
  case Commands extends ControlAction("commands", "Website commands", "commands", Some("?"))
  case Chat extends ControlAction("chat", "Go to Chat", "chat", Some("1"))
  case Sessions extends ControlAction("sessions", "Go to Sessions", "sessions", Some("2"))
  case Messages extends ControlAction("messages", "Go to All messages", "messages", Some("3"))
  case Graph extends ControlAction("graph", "Go to Conversation graph", "graph", Some("4"))
  case AgentRegistry extends ControlAction("agent-registry", "Go to Agents", "agent registry", Some("5"))
  case Blobs extends ControlAction("blobs", "Go to Blobs", "blobs", Some("6"))
  case Scalamode extends ControlAction("scalamode", "Go to Scalamode", "scalamode", Some("7"))
  case Conversations extends ControlAction("conversations", "Choose conversation", "conversations", Some("s"))
  case NewConversation extends ControlAction("new-conversation", "New conversation", "new conversation", Some("n"))
  case Reload extends ControlAction("reload", "Reload current view / history", "reload", Some("r"))
  case ReloadConversations
      extends ControlAction("reload-conversations", "Reload conversations", "reload conversations", None)
  case Username extends ControlAction("username", "Change username", "username", Some("u"))
  case Reconnect extends ControlAction("reconnect", "Reconnect", "reconnect", None)
  case Dictation extends ControlAction("dictation", "Focus dictation / Stop recording", "browser mic", Some("m"))
  case StopSpeech extends ControlAction("stop-speech", "Stop reading aloud", "stop speech", None)
  case Content extends ControlAction("content", "Focus page content", "content", None)
  case Providers extends ControlAction("providers", "Provider catalog", "provider", Some("p"))
  case Voice extends ControlAction("voice", "Voice input", "voice input", Some("v"))
  case Speak extends ControlAction("speak", "Speak latest reply", "speak reply", Some("t"))
  case Exit extends ControlAction("exit", "Exit", "exit", Some("q"))

  /** Terminal presentation is derived from the same key and label used to describe the action. */
  def terminalHint: String = shortcut.fold(terminalLabel) { key =>
    val display = if key == "Escape" then "Esc" else key
    s"$display: $terminalLabel"
  }

  /** JLine receives escape bytes, whereas DOM events use named keys. */
  def terminalKey: Option[String] = shortcut.map {
    case "Escape" => "\u001b"
    case key      => key
  }
}

/** Adapters normalize native events here; shared policy has no DOM, JLine, audio or effect dependencies. */
final case class KeyInput(
    key: String,
    shift: Boolean = false,
    ctrl: Boolean = false,
    meta: Boolean = false,
    alt: Boolean = false,
    composing: Boolean = false,
    repeat: Boolean = false
) {
  def modified: Boolean = ctrl || meta || alt
  def plain: Boolean = !modified && !shift
}

object KeyInput {
  def fromTerminal(character: Int): KeyInput = KeyInput(character match {
    case 27                                                  => "Escape"
    case 13 | 10                                             => "Enter"
    case value if value >= 0 && value <= Char.MaxValue.toInt => value.toChar.toString
    case _                                                   => ""
  })
}

enum InputMode {
  case Normal, Insert, Menu
}

object KeyboardControls {

  /** Text entry, platform shortcuts, IME composition and held keys never dispatch NORMAL commands. Menu dismissal stays
    * with its native adapter so browser focus restoration and terminal drafts are preserved.
    */
  def action(input: KeyInput, mode: InputMode, available: Iterable[ControlAction]): Option[ControlAction] = {
    import ControlAction.*
    if input.composing || input.repeat then None
    else if available.exists(_ == Commands) && input.key.toLowerCase == "k" &&
      (input.ctrl || input.meta) && !input.alt && !input.shift
    then Some(Commands)
    else if mode == InputMode.Menu then None
    else if input.key == "Escape" && !input.modified && available.exists(_ == Normal) then Some(Normal)
    else if mode != InputMode.Normal || input.modified then None
    else available.find(_.shortcut.contains(input.key))
  }

  def shouldSubmit(input: KeyInput): Boolean =
    input.key == "Enter" && input.plain && !input.composing

  /** Menu navigation can repeat; editing and platform-modified arrows retain native behavior. */
  def menuDirection(input: KeyInput): Option[Int] =
    if input.composing || input.modified then None
    else
      input.key match {
        case "ArrowUp"   => Some(-1)
        case "ArrowDown" => Some(1)
        case "Tab"       => Some(if input.shift then -1 else 1)
        case _           => None
      }

  def search(actions: List[ControlAction], query: String): List[ControlAction] = {
    val words = query.trim.toLowerCase.split("\\s+").filter(_.nonEmpty)
    actions.filter(action =>
      words.forall(word => s"${action.webLabel} ${action.shortcut.getOrElse("")}".toLowerCase.contains(word))
    )
  }
}
