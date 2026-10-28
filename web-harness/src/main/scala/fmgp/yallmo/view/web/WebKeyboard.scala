package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import com.raquo.laminar.keys.EventProp
import fmgp.yallmo.clientharness.{ControlAction, DictationKeyboard, InputMode, KeyInput, KeyboardControls}
import org.scalajs.dom

/** Browser effects only; names and shortcuts belong to the shared command catalog. */
private[web] final case class WebCommand(action: ControlAction, run: () => Unit) {
  def label: String = action.webLabel
  def key: String = action.shortcut.getOrElse("")
}

/** Root-owned DOM adapter for shared keyboard policy. Native controls retain editing and activation behavior. */
private[web] final class WebKeyboard(commands: List[WebCommand], onNormal: () => Unit, voice: VoiceModel) {
  private val dictation = new DictationKeyboard(() => voice.keyboardMode, () => voice.stop(), () => voice.cancel())
  private val available = commands.map(_.action) ++
    List(ControlAction.Normal, ControlAction.Commands, ControlAction.Next, ControlAction.Previous)
  private val query = Var("")
  private val editing = Var(false)
  private val matches = query.signal.map(value => WebKeyboard.filter(commands, value))
  private val palette: HtmlElement = dialogTag(
    cls := "command-palette",
    aria.label := "Website commands",
    onClick --> { event => if event.target == event.currentTarget then close() },
    div(
      cls := "command-heading",
      h2("Website commands"),
      button(typ := "button", "Close", onClick --> (_ => close()))
    ),
    p(cls := "muted", "Esc: NORMAL · i: edit · a: agent · j/k: move · Tab: next control · Enter: activate"),
    input(
      cls := "command-search",
      aria.label := "Search commands",
      placeholder := "Search commands…",
      value <-- query.signal,
      onInput.mapToValue --> query.writer,
      onKeyDown --> { event =>
        val input = WebKeyboard.input(event)
        if KeyboardControls.shouldSubmit(input) && !input.repeat then {
          event.preventDefault()
          WebKeyboard.filter(commands, query.now()).headOption.foreach(execute)
        }
      }
    ),
    ul(
      cls := "command-list",
      children <-- matches.map(_.map { command =>
        li(
          button(
            typ := "button",
            span(command.label),
            kbd(command.key),
            onClick --> (_ => execute(command))
          )
        )
      })
    ),
    p(cls := "muted", hidden <-- matches.map(_.nonEmpty), "No matching commands."),
    onKeyDown --> { event =>
      if !event.defaultPrevented then
        KeyboardControls.menuDirection(WebKeyboard.input(event)).foreach { direction =>
          event.preventDefault()
          WebKeyboard.moveFocus(palette.ref, direction)
        }
    }
  )

  private def dialog: dom.HTMLDialogElement = palette.ref.asInstanceOf[dom.HTMLDialogElement]
  private def close(): Unit = dialog.close()
  private def execute(command: WebCommand): Unit = { close(); command.run() }
  private def open(): Unit = {
    query.set("")
    if !dialog.open then dialog.showModal()
  }

  private def dispatch(action: ControlAction): Unit = action match {
    case ControlAction.Commands => open()
    case ControlAction.Normal   =>
      dom.document.activeElement match {
        case element: dom.HTMLElement => element.blur()
        case _                        => ()
      }
      editing.set(false)
      onNormal()
    case ControlAction.Next     => WebKeyboard.moveFocus(dom.document.body, 1)
    case ControlAction.Previous => WebKeyboard.moveFocus(dom.document.body, -1)
    case other                  => commands.find(_.action == other).foreach(_.run())
  }

  /** Place once at application root; Laminar owns and removes the window listeners with this element. */
  def view(): HtmlElement = div(
    cls := "keyboard-controls",
    windowEvents(_ => new EventProp[dom.FocusEvent]("focusin")) --> (event =>
      editing.set(WebKeyboard.isEditing(event.target))
    ),
    windowEvents(_.onKeyUp) --> { event =>
      if dictation.keyUp(event.key) then event.preventDefault()
    },
    windowEvents(_.onBlur) --> (_ => dictation.reset()),
    windowEvents(_.onKeyDown) --> { event =>
      val input = WebKeyboard.input(event)
      if dictation.keyDown(input) then {
        event.preventDefault()
        event.stopPropagation()
      } else if !event.defaultPrevented then {
        val mode = if dialog.open then InputMode.Menu
        else if WebKeyboard.isEditing(event.target) then InputMode.Insert
        else InputMode.Normal
        KeyboardControls.action(input, mode, available).foreach { action =>
          event.preventDefault()
          dispatch(action)
        }
      }
    },
    button(
      typ := "button",
      cls := "keyboard-help",
      aria.label := "Open website commands and keyboard shortcuts",
      title := "Commands: ? or Ctrl/Cmd+K",
      child.text <-- editing.signal.combineWith(voice.state.signal).map { (isEditing, state) =>
        if state.phase == VoicePhase.Recording then "DICTATION · Space: stop · Esc: cancel"
        else if state.busy then "DICTATION · Esc: cancel"
        else if isEditing then "INSERT · Esc: NORMAL"
        else "NORMAL · ?: commands"
      },
      onClick --> (_ => open())
    ),
    palette,
    onUnmountCallback(_ => close())
  )
}

private[web] object WebKeyboard {
  def input(event: dom.KeyboardEvent): KeyInput = KeyInput(
    event.key,
    shift = event.shiftKey,
    ctrl = event.ctrlKey,
    meta = event.metaKey,
    alt = event.altKey,
    composing = event.isComposing || event.keyCode == 229,
    repeat = event.repeat
  )

  def filter(commands: List[WebCommand], query: String): List[WebCommand] = {
    val matching = KeyboardControls.search(commands.map(_.action), query).toSet
    commands.filter(command => matching.contains(command.action))
  }

  def isEditing(target: dom.EventTarget): Boolean = target match {
    case element: dom.HTMLElement =>
      element.isContentEditable || element.closest("input, textarea, select, [role='textbox']") != null
    case _ => false
  }

  private def usable(element: dom.HTMLElement): Boolean =
    !element.matches(":disabled, [hidden], [aria-disabled='true']") && element.getClientRects().length > 0

  /** DOM focus is browser interop only; draft and application state remain owned by Laminar. */
  def focus(selector: String): Boolean = Option(dom.document.querySelector(selector)).exists {
    case element: dom.HTMLElement if usable(element) => element.focus(); true
    case _                                           => false
  }

  def activate(selector: String): Boolean = Option(dom.document.querySelector(selector)).exists {
    case element: dom.HTMLElement if usable(element) => element.click(); true
    case _                                           => false
  }

  def moveFocus(root: dom.HTMLElement, direction: Int): Unit = {
    val nodes = root.querySelectorAll("a[href], button, input, select, textarea, summary, [tabindex='0']")
    val controls = (0 until nodes.length).map(i => nodes(i).asInstanceOf[dom.HTMLElement]).filter(usable)
    if controls.nonEmpty then {
      val current = controls.indexOf(dom.document.activeElement)
      val next = if current < 0 then (if direction > 0 then 0 else controls.size - 1)
      else (current + direction + controls.size) % controls.size
      controls(next).focus()
    }
  }
}
