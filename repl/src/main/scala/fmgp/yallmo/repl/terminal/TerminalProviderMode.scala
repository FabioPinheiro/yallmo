package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.ProviderLoginService
import fmgp.yallmo.repl.ReplProviderCatalog.Error
import org.jline.keymap.KeyMap
import org.jline.reader.{Binding, Buffer, EndOfFileException, LineReader, Reference, Widget}
import org.jline.utils.AttributedString
import org.jline.utils.InfoCmp.Capability

import scala.jdk.CollectionConverters.*

/** Installed alongside TerminalMenus, whose PROVIDER entry calls open().
  *
  * Catalog/login requests yield an empty readLine without committing a prompt/newline, with the exact chat Buffer
  * suspended. The owner must check takeAction BEFORE submitting any line, run Client.execute(op).either at its effect
  * boundary, call complete, and drain any follow-up action (save requests refresh) before the next readLine. Login
  * actions carry the inspected profile ID and use terminal-private input at the application boundary. CALLBACK_INIT
  * restores the chat text/cursor; no form field enters its history. All methods touching JLine must run on the client's
  * terminal thread, never concurrently with readLine.
  */
private[terminal] final class TerminalProviderMode(reader: StatusLineReader) extends TerminalMode {
  import TerminalProviderMenu.*

  private val menu = new TerminalProviderMenu
  private var suspended: Option[Buffer] = None
  private val keyMapName = TerminalProviderMode.KeyMapName

  override def install(): Unit = {
    val name = keyMapName
    val keys = new KeyMap[Binding]()
    keys.setAmbiguousTimeout(50L)
    keys.setNomatch(new Reference(s"$name-text"))
    keys.setUnicode(new Reference(s"$name-text"))
    reader.getKeyMaps.put(name, keys)
    widget(name, "text")(dispatch(Input.Text(reader.getLastBinding)))
    widget(name, "paste")(paste())
    keys.bind(new Reference(s"$name-paste"), "\u001b[200~")
    bind(name, "up", Input.Up, Vector("\u001b[A", "\u001bOA") ++ capability(Capability.key_up))
    bind(name, "down", Input.Down, Vector("\u001b[B", "\u001bOB") ++ capability(Capability.key_down))
    bind(name, "enter", Input.Enter, Vector("\r", "\n"))
    bind(name, "back", Input.Back, Vector("\u001b"))
    bind(name, "cancel", Input.Cancel, Vector("\u0003"))
    bind(name, "erase", Input.Erase, Vector("\u007f", "\b"))
    val existing = Option(reader.getWidgets.get(LineReader.CALLBACK_INIT))
    reader.getWidgets.put(
      LineReader.CALLBACK_INIT,
      new Widget {
        override def apply(): Boolean = {
          val result = existing.forall(_.apply())
          suspended.foreach { draft =>
            reader.getBuffer.copyFrom(draft)
            reader.setKeyMap(keyMapName)
            render()
          }
          suspended = None
          result
        }
      }
    )
  }

  /** Called by a local menu widget during readLine, not outside a line-editing session. */
  def open(): Unit = {
    menu.open()
    yieldToClient()
  }

  /** Drain after each yielded readLine and after complete; never submit those empty lines to the agent. */
  def takeAction(): Option[Action] = {
    val action = pendingAction.orElse(menu.takeAction())
    pendingAction = None
    action
  }
  def complete(result: Either[Error, Reply]): Unit = menu.complete(result)

  def completeLogin(result: ProviderLoginService.Result): Unit = menu.completeLogin(result)

  def close(): Unit = {
    suspended = None
    pendingAction = None
    reader.clearPost()
    if reader.isReading then reader.redisplay()
  }

  private def capability(capability: Capability): Vector[String] =
    Option(KeyMap.key(reader.getTerminal, capability)).filter(_.nonEmpty).toVector

  private def widget(map: String, name: String)(action: => Unit): Unit =
    reader.getWidgets.put(
      s"$map-$name",
      new Widget {
        override def apply(): Boolean = { action; true }
      }
    )

  private def bind(map: String, name: String, input: Input, keys: Vector[String]): Unit = {
    widget(map, name)(dispatch(input))
    keys.foreach(key => reader.getKeyMaps.get(map).bind(new Reference(s"$map-$name"), key))
  }

  /** Consume the whole paste before dispatch: controls are rejected by field editing, never decoded as bindings. Keep
    * only a bounded payload and terminator prefix, even for ignored or oversized pastes. JLine's built-in paste widget
    * cannot be used here because it inserts into the suspended chat buffer.
    */
  private def paste(): Unit = {
    val terminator = "\u001b[201~"
    val prefix = new java.lang.StringBuilder()
    val text = new java.lang.StringBuilder()
    val isField = menu.state.screen == Screen.Id || menu.state.screen == Screen.Endpoint
    var oversized = false
    var complete = false
    while !complete do {
      val character = reader.readCharacter()
      if character == -1 then throw new EndOfFileException()
      prefix.appendCodePoint(character)
      while !terminator.startsWith(prefix.toString) do {
        if isField && !oversized then {
          if text.length < 4096 then text.append(prefix.charAt(0))
          else oversized = true
        }
        prefix.deleteCharAt(0)
      }
      complete = prefix.toString == terminator
    }
    if isField && !oversized then dispatch(Input.Text(text.toString))
  }

  private def dispatch(input: Input): Unit = {
    menu.input(input)
    // Close is handled locally. All other actions are returned untouched to the application boundary.
    menu.takeAction() match {
      case Some(Action.Close) => reader.clearPost(); reader.setKeyMap(LineReader.VICMD)
      case Some(action)       => pendingAction = Some(action); yieldToClient()
      case None               => reader.setKeyMap(keyMapName); render()
    }
  }

  private var pendingAction: Option[Action] = None

  private def yieldToClient(): Unit = {
    // Remove the live menu before accepting the empty line, so it never becomes scrollback.
    reader.clearPost()
    suspended = Some(reader.getBuffer.copy())
    reader.getBuffer.clear()
    reader.yieldWithoutNewline()
  }

  private def render(): Unit = reader.showPost(() => visibleMenu())

  /** Bound physical rows, not logical lines: long IDs and narrow terminals must keep the selection visible. The
    * supplier is evaluated on every JLine redisplay, including resize, without changing the chat buffer/cursor.
    */
  private def visibleMenu(): AttributedString = {
    val columns = reader.getTerminal.getWidth.max(1)
    val lines = menu.lines :+
      "Catalog only: active agent/runtime unchanged. Configure agent references and restart to use additions."
    val rows = lines.flatMap { line =>
      new AttributedString(line).columnSplitLength(columns, false, false, reader.getTerminal).asScala
    }
    val height = reader.availablePostRows
    val selected = rows.indexWhere(_.toString.startsWith("> ")).max(0)
    val start = (selected - height / 2).max(0).min((rows.size - height).max(0))
    new AttributedString(rows.slice(start, start + height).map(_.toString).mkString("\n"))
  }
}

private[terminal] object TerminalProviderMode {
  val KeyMapName = "provider-catalog"
}
