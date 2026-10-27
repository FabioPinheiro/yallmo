package fmgp.yallmo.repl.terminal

import fmgp.yallmo.clientharness.ControlAction
import fmgp.yallmo.model.AgentId
import fmgp.yallmo.repl.{ReplActiveAgent, ReplAgentChoice}
import org.jline.keymap.KeyMap
import org.jline.reader.{Binding, Buffer, EndOfFileException, LineReader, Reference, Widget}
import org.jline.utils.AttributedString
import org.jline.utils.InfoCmp.Capability
import scala.jdk.CollectionConverters.*

/** Managed agent picker. Runtime selection runs between reads, never inside a JLine widget. */
private[terminal] final class TerminalAgentMode(reader: StatusLineReader) extends TerminalMode {
  private val name = TerminalMenus.AgentsKeyMap
  private var entries = Vector.empty[ReplAgentChoice]
  private var active: Option[AgentId] = None
  private var remoteConnection = false // FIXME REVIEW
  private var index = 0
  private var error: Option[String] = None
  private var pending: Option[AgentId] = None
  private var suspended: Option[Buffer] = None
  private var resumeMap = LineReader.VICMD

  override def install(): Unit = {
    val keys = new KeyMap[Binding]()
    keys.setAmbiguousTimeout(50L)
    reader.getKeyMaps.put(name, keys)
    bindNative("ignore", Vector.empty) { () }
    keys.setNomatch(new Reference(s"$name-ignore"))
    keys.setUnicode(new Reference(s"$name-ignore"))
    bind(
      ControlAction.Previous,
      ControlAction.Previous.terminalKey.toVector ++ Vector("\u001b[A", "\u001bOA") ++ capability(Capability.key_up)
    ) {
      index = (index - 1).max(0); error = None; render()
    }
    bind(
      ControlAction.Next,
      ControlAction.Next.terminalKey.toVector ++ Vector("\u001b[B", "\u001bOB") ++ capability(Capability.key_down)
    ) {
      index = (index + 1).min((entries.size - 1).max(0)); error = None; render()
    }
    bind(ControlAction.Normal, ControlAction.Normal.terminalKey.toVector ++ Vector("\u0003"))(leave(LineReader.VICMD))
    bind(ControlAction.Edit, ControlAction.Edit.terminalKey.toVector)(leave(LineReader.VIINS))
    bindNative("enter", Vector("\r", "\n")) {
      entries.lift(index).foreach { entry =>
        pending = Some(entry.id)
        suspended = Some(reader.getBuffer.copy())
        reader.clearPost()
        reader.getBuffer.clear()
        reader.yieldWithoutNewline()
      }
    }
    bindNative("paste", Vector("\u001b[200~"))(discardPaste())
    val existing = Option(reader.getWidgets.get(LineReader.CALLBACK_INIT))
    reader.getWidgets.put(
      LineReader.CALLBACK_INIT,
      new Widget {
        override def apply(): Boolean = {
          val result = existing.forall(_.apply())
          suspended.foreach { draft =>
            reader.getBuffer.copyFrom(draft)
            reader.setKeyMap(resumeMap)
            if resumeMap == name then render()
          }
          suspended = None
          result
        }
      }
    )
  }

  def open(choices: Vector[ReplAgentChoice], selected: Option[ReplActiveAgent]): Unit = {
    openSelection(choices, selected.map(value => AgentId(value.agentId)))
  }

  def openSelection(choices: Vector[ReplAgentChoice], selected: Option[AgentId], remote: Boolean = false): Unit = {
    remoteConnection = remote
    entries = choices
    active = selected
    index = entries.indexWhere(entry => active.contains(entry.id)).max(0)
    error = None
    reader.setKeyMap(name)
    render()
  }

  def takeSelection(): Option[AgentId] = {
    val result = pending
    pending = None
    result
  }

  /** Successful selection closes to NORMAL; failed resolution keeps the picker and the old conversation. */
  def complete(result: Either[String, Unit]): Unit = result match {
    case Right(_)      => error = None; resumeMap = LineReader.VICMD
    case Left(message) => error = Some(message); resumeMap = name
  }

  def close(): Unit = {
    pending = None
    suspended = None
    reader.clearPost()
  }

  private def leave(keyMap: String): Unit = {
    reader.clearPost()
    reader.setKeyMap(keyMap)
  }

  private def capability(value: Capability): Vector[String] =
    Option(KeyMap.key(reader.getTerminal, value)).filter(_.nonEmpty).toVector

  private def bind(control: ControlAction, keys: Vector[String])(action: => Unit): Unit =
    TerminalControlBindings.bindKeys(reader, name, control, keys)(action)

  private def bindNative(suffix: String, keys: Vector[String])(action: => Unit): Unit = {
    val widgetName = s"$name-$suffix"
    reader.getWidgets.put(
      widgetName,
      new Widget {
        override def apply(): Boolean = { action; true }
      }
    )
    keys.foreach(key => reader.getKeyMaps.get(name).bind(new Reference(widgetName), key))
  }

  private def discardPaste(): Unit = {
    val end = "\u001b[201~"
    val prefix = new java.lang.StringBuilder()
    while prefix.toString != end do {
      val character = reader.readCharacter()
      if character == -1 then throw new EndOfFileException()
      prefix.appendCodePoint(character)
      while !end.startsWith(prefix.toString) do prefix.deleteCharAt(0)
    }
  }

  private def render(): Unit = reader.showPost(() => {
    def clean(value: String): String = TerminalStatusLayout.sanitize(value)
    val selected = active.map(value => clean(AgentId.value(value))).getOrElse("none")
    val lines = Vector(s"AGENTS — selected: $selected") ++
      (if entries.isEmpty then
         Vector(if remoteConnection then "No agent IDs available."
         else "No configured agents. Add agents to the config and restart.")
       else
         entries.zipWithIndex.flatMap { case (entry, row) =>
           val marker = if row == index then ">" else " "
           val current =
             if active.contains(entry.id) then (if remoteConnection then " [preferred]" else " [active]") else ""
           Vector(
             s"$marker ${clean(AgentId.value(entry.id))} [${clean(entry.agentType)}] ${clean(entry.providers.mkString(", "))} · ${entry.model.map(clean).getOrElse("—")}$current"
           ) ++
             (if row == index then error.map(value => s"Selection failed: ${clean(value)}") else None)
         })
    val width = reader.getTerminal.getWidth.max(1)
    val rows = lines.flatMap(line =>
      new AttributedString(line)
        .columnSplitLength(width, false, false, reader.getTerminal)
        .asScala
    )
    val height = reader.availablePostRows
    val selectedRow = rows.indexWhere(_.toString.startsWith("> ")).max(0)
    val start = (selectedRow - height / 2).max(0).min((rows.size - height).max(0))
    new AttributedString(rows.slice(start, start + height).map(_.toString).mkString("\n"))
  })
}
