package fmgp.yallmo.repl.terminal

import fmgp.yallmo.orchestration.config.ProviderId
import fmgp.yallmo.provider.openai.OpenAiCodexClient
import fmgp.yallmo.repl.ProviderLoginService
import fmgp.yallmo.repl.ReplProviderCatalog
import fmgp.yallmo.repl.ReplProviderCatalog.*
import fmgp.yallmo.repl.LoginMethod
import zio.IO

/** Local provider UI model. Form text is owned here, never by the chat buffer, parser or history. Only an explicit
  * confirmation emits Save. The client runs catalog effects, then calls complete before more input.
  */
private[terminal] final class TerminalProviderMenu {
  import TerminalProviderMenu.*

  private var current = State()
  private var outgoing: Option[Action] = None
  private var loginNotice: Option[String] = None
  def state: State = current

  def open(): Unit = {
    current = State()
    loginNotice = None
    request(Operation.Refresh)
  }

  def takeAction(): Option[Action] = {
    val result = outgoing
    outgoing = None
    result
  }

  private def request(operation: Operation): Unit = {
    current = current.copy(waiting = true, notice = None)
    outgoing = Some(Action.Catalog(operation))
  }

  /** Supply exactly the completion of the outstanding catalog action. Save success requests a separate refresh; a
    * refresh failure must never return to a saveable form and accidentally duplicate an already published provider.
    */
  def complete(result: Either[Error, Reply]): Unit = {
    current = current.copy(waiting = false)
    result match {
      case Left(error)                  => current = current.copy(notice = Some(errorMessage(error)))
      case Right(Reply.Listed(entries)) =>
        val index = current.screen match {
          case Screen.Browse(index) => index.min(entries.size)
          case _                    => 0
        }
        current = current.copy(entries = entries, screen = Screen.Browse(index), notice = None)
      case Right(Reply.Loaded(Some(entry))) =>
        val index = current.screen match {
          case Screen.Details(_, selected) => selected
          case _                           => 0
        }
        current = current.copy(screen = Screen.Details(entry, index), notice = None)
      case Right(Reply.Loaded(None)) =>
        current = current.copy(screen = Screen.Browse(0), notice = Some("Provider no longer exists; r: refresh."))
      case Right(Reply.Saved(entry)) =>
        current = State(entries = (current.entries.filterNot(_.id == entry.id) :+ entry))
        request(Operation.Refresh)
    }
    loginNotice.foreach { message =>
      current = current.copy(notice = Some((Vector(message) ++ current.notice.toVector).mkString(" ")))
    }
    loginNotice = None
  }

  /** Reload the same profile after every outcome; credentials are presence-only and read from disk. */
  def completeLogin(result: ProviderLoginService.Result): Unit = current.screen match {
    case Screen.Details(entry, _) =>
      request(Operation.Inspect(entry.id))
      loginNotice = Some(result match {
        case ProviderLoginService.Result.Saved =>
          "Credentials saved to this profile's private config. Restart required; active agent/runtime unchanged."
        case ProviderLoginService.Result.Cancelled => "Profile login cancelled; no credentials saved."
        case ProviderLoginService.Result.TimedOut  => "Profile login timed out; no credentials saved."
        case ProviderLoginService.Result.Failed(_) => "Profile login failed; no credentials saved."
      })
    case _ => ()
  }

  private def login(entry: Entry, method: LoginMethod): Unit = {
    current = current.copy(waiting = true, notice = None)
    outgoing = Some(Action.Login(entry.id, method))
  }

  def input(key: Input): Unit = if !current.waiting then {
    key match {
      case Input.Cancel =>
        current = current.copy(screen = Screen.Browse(0), draft = Draft(), notice = None)
      case Input.Back => back()
      case _          =>
        current.screen match {
          case Screen.Browse(index) =>
            key match {
              case Input.Up                                     => browse(index - 1)
              case Input.Down                                   => browse(index + 1)
              case Input.Text("k")                              => browse(index - 1)
              case Input.Text("j")                              => browse(index + 1)
              case Input.Text("r")                              => request(Operation.Refresh)
              case Input.Enter if index < current.entries.size  => request(Operation.Inspect(current.entries(index).id))
              case Input.Enter if index == current.entries.size =>
                current = current.copy(screen = Screen.Types(0), draft = Draft(), notice = None)
              case _ => ()
            }
          case Screen.Details(entry, _) if entry.providerType == ProviderType.ChatGPT =>
            key match {
              case Input.Enter     => login(entry, LoginMethod.Browser)
              case Input.Text("l") => login(entry, LoginMethod.Browser)
              case _               => ()
            }
          case Screen.Details(_, _) => ()
          case Screen.Types(index)  =>
            key match {
              case Input.Up | Input.Down | Input.Text("j") | Input.Text("k") =>
                current = current.copy(screen = Screen.Types(1 - index))
              case Input.Enter =>
                current = current.copy(
                  screen = Screen.Id,
                  draft =
                    current.draft.copy(providerType = if index == 0 then ProviderType.OAI else ProviderType.ChatGPT),
                  notice = None
                )
              case _ => ()
            }
          case Screen.Id | Screen.Endpoint => edit(key)
          case Screen.Confirm(index)       =>
            key match {
              case Input.Up | Input.Down | Input.Text("j") | Input.Text("k") =>
                current = current.copy(screen = Screen.Confirm(1 - index))
              case Input.Enter if index == 1 => input(Input.Cancel)
              case Input.Enter               =>
                val draft = current.draft
                request(
                  Operation.Save(
                    draft.id,
                    draft.providerType match {
                      case ProviderType.OAI     => Creation.OAI(draft.endpoint)
                      case ProviderType.ChatGPT => Creation.ChatGPT
                    }
                  )
                )
              case _ => ()
            }
        }
    }
  }

  private def browse(index: Int): Unit =
    current = current.copy(screen = Screen.Browse(Math.floorMod(index, current.entries.size + 1)), notice = None)

  private def back(): Unit = {
    val screen = current.screen match {
      case Screen.Browse(_)     => outgoing = Some(Action.Close); current.screen
      case Screen.Details(_, _) => Screen.Browse(0)
      case Screen.Types(_)      =>
        current = current.copy(draft = Draft())
        Screen.Browse(0)
      case Screen.Id         => Screen.Types(if current.draft.providerType == ProviderType.OAI then 0 else 1)
      case Screen.Endpoint   => Screen.Id
      case Screen.Confirm(_) => if current.draft.providerType == ProviderType.OAI then Screen.Endpoint else Screen.Id
    }
    current = current.copy(screen = screen, notice = None)
  }

  private def edit(key: Input): Unit = {
    val isId = current.screen == Screen.Id
    val value = if isId then current.draft.id else current.draft.endpoint
    key match {
      case Input.Enter if value.trim.isEmpty => current = current.copy(notice = Some("This field is required."))
      case Input.Enter                       =>
        current = current.copy(
          screen =
            if isId && current.draft.providerType == ProviderType.OAI then Screen.Endpoint else Screen.Confirm(0),
          notice = None
        )
      case Input.Text(text) if text.nonEmpty && text.forall(safeCharacter) =>
        if value.length + text.length > 4096 then
          current = current.copy(notice = Some("Field limit: 4096 characters. Additional input was not accepted."))
        else {
          val updated = value + text
          current = current.copy(
            draft = if isId then current.draft.copy(id = updated) else current.draft.copy(endpoint = updated),
            notice = None
          )
        }
      case Input.Erase =>
        val updated = if value.isEmpty then value else value.substring(0, value.offsetByCodePoints(value.length, -1))
        current = current.copy(
          draft = if isId then current.draft.copy(id = updated) else current.draft.copy(endpoint = updated),
          notice = None
        )
      case _ => ()
    }
  }

  /** Plain sanitized lines only; the JLine adapter owns terminal control sequences. Endpoint input is always hidden. */
  def lines: Vector[String] = {
    def choices(items: Vector[String], index: Int): Vector[String] =
      items.zipWithIndex.map((text, i) => s"${if i == index then ">" else " "} $text")
    val content = current.screen match {
      case Screen.Browse(index) =>
        Vector("PROVIDER — configured providers") ++
          (if current.entries.isEmpty then Vector("No configured providers.") else Vector.empty) ++
          choices(
            current.entries.map(e => s"${display(e.displayId)} [${e.providerType}]") ++ Vector("New provider"),
            index
          ) ++
          Vector("Up/Down or j/k: select · Enter: open · r: refresh · Esc: NORMAL")
      case Screen.Details(entry, index) =>
        Vector(
          "PROVIDER configuration",
          s"ID: ${display(entry.displayId)}",
          s"Type: ${entry.providerType}",
          s"Endpoint: ${displayEndpoint(entry.endpoint)}",
          s"Credentials: ${credentialLabel(entry.credentials)}"
        ) ++ (if entry.providerType == ProviderType.ChatGPT then {
                val label = if entry.credentials == CredentialStatus.Configured then "Re-login" else "Login"
                choices(Vector(s"$label (browser)"), index) ++
                  Vector("Enter or l: browser login")
              } else Vector.empty) ++ Vector("Esc: provider list")
      case Screen.Types(index) =>
        Vector("New provider — choose TYPE") ++ choices(Vector("OAI", "ChatGPT (logged out)"), index) ++ Vector(
          "Up/Down or j/k: select · Enter: choose · Esc: cancel"
        )
      case Screen.Id =>
        Vector(
          "New provider — ID",
          s"ID: ${display(current.draft.id)}",
          "Type an exact, case-sensitive ID · Enter: next · Backspace: erase · Esc: back · Ctrl-C: cancel"
        )
      case Screen.Endpoint =>
        Vector(
          "New OAI provider — base URL",
          s"Base URL: ${if current.draft.endpoint.isEmpty then "(empty)" else "[hidden]"}",
          "Absolute HTTP(S) URL required; input hidden · Enter: review · Backspace: erase · Esc: ID · Ctrl-C: cancel"
        )
      case Screen.Confirm(index) =>
        Vector(
          "Confirm new provider",
          s"ID: ${display(current.draft.id)}",
          s"Type: ${current.draft.providerType}",
          s"Endpoint: ${
              if current.draft.providerType == ProviderType.OAI then displayEndpoint(current.draft.endpoint)
              else s"${displayEndpoint(OpenAiCodexClient.CodexResponsesUrl)} (trusted ChatGPT default)"
            }",
          s"Credentials: ${
              if current.draft.providerType == ProviderType.ChatGPT then "logged out" else "not applicable"
            }"
        ) ++
          choices(Vector("Save provider", "Cancel"), index) ++ Vector(
            "Up/Down: select · Enter: confirm · Esc: back · Ctrl-C: cancel"
          )
    }
    content ++ current.notice.toVector ++ (if current.waiting then Vector("Working…") else Vector.empty)
  }
}

private[terminal] object TerminalProviderMenu {
  enum Input {
    case Up, Down, Enter, Back, Cancel, Erase
    case Text(value: String)
  }
  enum Screen {
    case Browse(index: Int)
    case Details(entry: Entry, index: Int = 0)
    case Types(index: Int)
    case Id, Endpoint
    case Confirm(index: Int)
  }
  final case class Draft(providerType: ProviderType = ProviderType.OAI, id: String = "", endpoint: String = "") {
    override def toString: String = "Draft([private])"
  }
  final case class State(
      entries: Vector[Entry] = Vector.empty,
      screen: Screen = Screen.Browse(0),
      draft: Draft = Draft(),
      waiting: Boolean = false,
      notice: Option[String] = None
  )

  enum Operation {
    case Refresh
    case Inspect(id: ProviderId)
    case Save(id: String, creation: Creation)
    override def toString: String = productPrefix
  }
  enum Action {
    case Catalog(operation: Operation)
    case Login(id: ProviderId, method: LoginMethod)
    case Close
  }
  enum Reply {
    case Listed(entries: Vector[Entry])
    case Loaded(entry: Option[Entry])
    case Saved(entry: Entry)
  }

  /** The application boundary runs this effect, not a JLine widget. Reuse one catalog for the explicit startup path. */
  trait Client {
    def execute(operation: Operation): IO[Error, Reply]
  }
  def client(catalog: ReplProviderCatalog): Client = new Client {
    def execute(operation: Operation): IO[Error, Reply] = operation match {
      case Operation.Refresh            => catalog.list.map(Reply.Listed.apply)
      case Operation.Inspect(id)        => catalog.load(id).map(Reply.Loaded.apply)
      case Operation.Save(id, creation) => catalog.add(id, creation).map(Reply.Saved.apply)
    }
  }

  private def safeCharacter(char: Char): Boolean =
    !Character.isISOControl(char) && !Set(
      Character.FORMAT.toInt,
      Character.LINE_SEPARATOR.toInt,
      Character.PARAGRAPH_SEPARATOR.toInt
    ).contains(Character.getType(char))
  private def display(text: String): String = text.map(char => if safeCharacter(char) then char else '?')
  private def displayEndpoint(text: String): String =
    scala.util
      .Try(new java.net.URI(text))
      .toOption
      .filter(uri =>
        Option(uri.getScheme).exists(s =>
          s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https")
        ) && uri.getHost != null && text.forall(safeCharacter)
      )
      .fold("<invalid endpoint>")(uri =>
        display(
          s"${uri.getScheme}://${uri.getHost}${if uri.getPort == -1 then "" else s":${uri.getPort}"}${Option(uri.getRawPath).getOrElse("")}"
        )
      )
  private def credentialLabel(status: CredentialStatus): String = status match {
    case CredentialStatus.NotApplicable => "not applicable"
    case CredentialStatus.LoggedOut     => "logged out"
    case CredentialStatus.Configured    => "present (validity not checked)"
  }
  private def errorMessage(error: Error): String = error match {
    case Error.InvalidId       => "ID must not be blank. Esc: edit · Enter: retry."
    case Error.InvalidEndpoint => "Base URL must be an absolute HTTP(S) URL. Esc: edit · Enter: retry."
    case Error.DuplicateId     => "Provider ID already exists. Esc: edit · Enter: retry."
    case Error.InvalidConfig   => "Configuration is invalid. No form data was discarded."
    case Error.ReadFailed      => "Cannot read provider configuration. r: refresh from list."
    case Error.SaveFailed      => "Cannot save provider. Form retained; Enter: retry or Esc: edit."
  }
}
