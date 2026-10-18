package fmgp.yallmo.repl.terminal

import fmgp.yallmo.orchestration.config.ProviderId
import fmgp.yallmo.repl.ReplProviderCatalog
import fmgp.yallmo.repl.ReplProviderCatalog.*
import fmgp.yallmo.repl.LoginMethod
import munit.FunSuite
import zio.*

import java.nio.file.Files

final class TerminalProviderMenuSuite extends FunSuite {
  import TerminalProviderMenu.*

  private val local = Entry(
    ProviderId.unsafe("local"),
    "local",
    ProviderType.OAI,
    "https://example.com/v1",
    CredentialStatus.NotApplicable
  )
  private val account = Entry(
    ProviderId.unsafe("account"),
    "account",
    ProviderType.ChatGPT,
    "https://chatgpt.com/codex",
    CredentialStatus.Configured
  )
  private val entries = Vector(account, local)

  private def opened(values: Vector[Entry] = entries): TerminalProviderMenu = {
    val menu = new TerminalProviderMenu
    menu.open()
    assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Refresh)))
    menu.complete(Right(Reply.Listed(values)))
    menu
  }

  private def press(menu: TerminalProviderMenu, keys: Input*): Unit = keys.foreach(menu.input)
  private def newForm(menu: TerminalProviderMenu, chatGPT: Boolean = false): Unit = {
    menu.state.entries.foreach(_ => menu.input(Input.Down))
    menu.input(Input.Enter)
    if chatGPT then menu.input(Input.Down)
    menu.input(Input.Enter)
  }
  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }

  test("all providers and discoverable controls are listed; movement wraps and Enter inspects by stored ID") {
    val menu = opened()
    val lines = menu.lines.mkString("\n")
    for text <- Vector("account [ChatGPT]", "local [OAI]", "New provider", "Up/Down", "Enter", "Esc") do
      assert(lines.contains(text))
    press(menu, Input.Down, Input.Enter)
    assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Inspect(local.id))))
    menu.complete(Right(Reply.Loaded(Some(local))))
    assertEquals(menu.state.screen, Screen.Details(local))
    press(menu, Input.Back, Input.Up)
    assertEquals(menu.state.screen, Screen.Browse(2))
    press(menu, Input.Text("j"), Input.Text("k"))
    assertEquals(menu.state.screen, Screen.Browse(2))
  }

  test("details redact endpoint secrets and sanitize all display text; credential presence is not authentication") {
    val malicious = account.copy(
      displayId = "unsafe\u001b\n\u202e",
      endpoint = "https://user:synthetic-password@example.com/v1?key=synthetic-query#synthetic-fragment"
    )
    val menu = opened(Vector(malicious))
    menu.input(Input.Enter)
    menu.takeAction()
    menu.complete(Right(Reply.Loaded(Some(malicious))))
    val rendered = menu.lines.mkString("\n")
    assert(rendered.contains("https://example.com/v1"))
    assert(rendered.contains("present (validity not checked)"))
    assert(rendered.contains("Re-login (browser)"))
    for text <- Vector("synthetic-password", "synthetic-query", "synthetic-fragment", "\u001b", "\u202e") do
      assert(!rendered.contains(text))
    assert(menu.lines.forall(line => !line.contains('\n')))
  }

  test("list has no login rows or shortcuts; ChatGPT details scope browser login to the inspected ID") {
    val menu = opened()
    assert(!menu.lines.mkString.contains("Login"))
    press(menu, Input.Text("l"), Input.Text("d"))
    assertEquals(menu.takeAction(), None)
    press(menu, Input.Enter)
    menu.takeAction()
    menu.complete(Right(Reply.Loaded(Some(account))))
    press(menu, Input.Down, Input.Enter)
    assertEquals(menu.takeAction(), Some(Action.Login(account.id, LoginMethod.Browser)))
  }

  test("login shortcuts are scoped to each ChatGPT detail and OAI never emits unsupported actions") {
    val second =
      account.copy(id = ProviderId.unsafe("second"), displayId = "second", credentials = CredentialStatus.LoggedOut)
    for entry <- Vector(account, second, local) do
      val menu = opened(Vector(entry))
      press(menu, Input.Enter)
      menu.takeAction()
      menu.complete(Right(Reply.Loaded(Some(entry))))
      press(menu, Input.Text("l"))
      assertEquals(
        menu.takeAction(),
        Option.when(entry.providerType == ProviderType.ChatGPT)(Action.Login(entry.id, LoginMethod.Browser))
      )
      if entry.providerType == ProviderType.OAI then {
        assert(!menu.lines.mkString.toLowerCase.contains("login"))
        press(menu, Input.Down, Input.Enter)
        assertEquals(menu.takeAction(), None)
      }
  }

  test("login outcomes reload the same details and keep fixed feedback even if refresh fails") {
    val menu = opened(Vector(account))
    press(menu, Input.Enter)
    menu.takeAction()
    menu.complete(Right(Reply.Loaded(Some(account))))
    press(menu, Input.Down, Input.Enter)
    menu.takeAction()
    menu.completeLogin(fmgp.yallmo.repl.ProviderLoginService.Result.TimedOut)
    assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Inspect(account.id))))
    menu.complete(Left(Error.ReadFailed))
    assertEquals(menu.state.screen, Screen.Details(account, 0))
    assert(menu.lines.mkString.contains("Profile login timed out"))
    assert(menu.lines.mkString.contains("Cannot read provider configuration"))
  }

  test("OAI type, isolated ID/base URL editing, backtracking and explicit save retain exact input") {
    val menu = opened()
    newForm(menu)
    assertEquals(menu.state.screen, Screen.Id)
    press(menu, Input.Enter)
    assert(menu.state.notice.nonEmpty)
    press(menu, Input.Text(" Exact-ID x"), Input.Erase, Input.Enter)
    assertEquals(menu.state.screen, Screen.Endpoint)
    val url = "https://user:synthetic-password@example.com/v1?key=synthetic-query#synthetic-fragment"
    press(menu, Input.Text(url))
    assert(menu.lines.contains("Base URL: [hidden]"))
    assert(!menu.lines.mkString.contains("synthetic-"))
    assert(!menu.state.toString.contains("synthetic-"))
    press(menu, Input.Back)
    assertEquals(menu.state.draft.id, " Exact-ID ")
    press(menu, Input.Enter, Input.Enter)
    assertEquals(menu.state.screen, Screen.Confirm(0))
    assert(menu.lines.exists(_.contains("https://example.com/v1")))
    assert(!menu.lines.mkString.contains("synthetic-"))
    assertEquals(menu.takeAction(), None)
    press(menu, Input.Enter)
    val action = menu.takeAction()
    assertEquals(action, Some(Action.Catalog(Operation.Save(" Exact-ID ", Creation.OAI(url)))))
    assert(!action.toString.contains("synthetic-"))
  }

  test("ChatGPT picker creates logged-out default with no endpoint or credentials") {
    val menu = opened()
    newForm(menu, chatGPT = true)
    press(menu, Input.Text("new-chatgpt"), Input.Enter)
    assertEquals(menu.state.screen, Screen.Confirm(0))
    assert(menu.lines.contains("Credentials: logged out"))
    assert(menu.lines.exists(_.contains("trusted ChatGPT default")))
    press(menu, Input.Enter)
    assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Save("new-chatgpt", Creation.ChatGPT))))
  }

  test("Esc backtracking, Cancel row and Ctrl-C never save or retain an abandoned draft") {
    val menu = opened(Vector.empty)
    newForm(menu)
    press(menu, Input.Text("abandoned"), Input.Enter, Input.Text("https://example.com"), Input.Enter, Input.Back)
    assertEquals(menu.state.screen, Screen.Endpoint)
    press(menu, Input.Back, Input.Back)
    assertEquals(menu.state.screen, Screen.Types(0))
    press(menu, Input.Back)
    assertEquals(menu.state.screen, Screen.Browse(0))
    assertEquals(menu.takeAction(), None)
    newForm(menu, chatGPT = true)
    press(menu, Input.Text("cancelled"), Input.Enter, Input.Down, Input.Enter)
    assertEquals(menu.state.draft, Draft())
    newForm(menu)
    press(menu, Input.Text("discarded"), Input.Cancel)
    assertEquals(menu.state.draft, Draft())
    assertEquals(menu.takeAction(), None)
    press(menu, Input.Back)
    assertEquals(menu.takeAction(), Some(Action.Close))
  }

  test("save failure preserves the entire form and supports editing then retry") {
    val menu = opened(Vector.empty)
    newForm(menu)
    press(menu, Input.Text("retry"), Input.Enter, Input.Text("https://example.com"), Input.Enter, Input.Enter)
    menu.takeAction()
    val before = menu.state.draft
    menu.complete(Left(Error.SaveFailed))
    assertEquals(menu.state.draft, before)
    assertEquals(menu.state.screen, Screen.Confirm(0))
    assert(menu.lines.exists(_.contains("Form retained")))
    press(menu, Input.Back, Input.Erase, Input.Text("m"), Input.Enter, Input.Enter)
    assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Save("retry", Creation.OAI("https://example.com")))))
  }

  test("successful save requests fresh list; refresh failure cannot resubmit the published form") {
    val menu = opened(Vector.empty)
    newForm(menu, chatGPT = true)
    press(menu, Input.Text("saved"), Input.Enter, Input.Enter)
    menu.takeAction()
    val saved =
      account.copy(id = ProviderId.unsafe("saved"), displayId = "saved", credentials = CredentialStatus.LoggedOut)
    menu.complete(Right(Reply.Saved(saved)))
    assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Refresh)))
    assertEquals(menu.state.draft, Draft())
    menu.complete(Left(Error.ReadFailed))
    assert(menu.state.entries.contains(saved))
    press(menu, Input.Enter)
    assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Inspect(saved.id))))
  }

  test("busy input is ignored, read errors remain recoverable and missing providers return to list") {
    val menu = new TerminalProviderMenu
    menu.open()
    press(menu, Input.Down, Input.Enter, Input.Cancel, Input.Back)
    assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Refresh)))
    menu.complete(Left(Error.InvalidConfig))
    press(menu, Input.Text("r"))
    assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Refresh)))
    menu.complete(Right(Reply.Listed(entries)))
    press(menu, Input.Enter)
    menu.takeAction()
    menu.complete(Right(Reply.Loaded(None)))
    assertEquals(menu.state.screen, Screen.Browse(0))
    assert(menu.lines.exists(_.contains("no longer exists")))
  }

  test("unsafe pasted controls are not accepted as local form text; malformed endpoints are never echoed") {
    val menu = opened(Vector.empty)
    newForm(menu)
    press(menu, Input.Text("bad\u001b\n\u202e"))
    assertEquals(menu.state.draft.id, "")
    press(menu, Input.Text("safe"), Input.Enter, Input.Text("synthetic-secret-not-a-url"), Input.Enter)
    assert(menu.lines.contains("Endpoint: <invalid endpoint>"))
    assert(!menu.lines.mkString.contains("synthetic-secret"))
  }

  test("field limit rejects extra input without truncating saved values; backspace removes a Unicode code point") {
    val menu = opened(Vector.empty)
    newForm(menu)
    press(menu, Input.Text("a" * 4096), Input.Text("b"))
    assertEquals(menu.state.draft.id, "a" * 4096)
    assert(menu.lines.exists(_.contains("Additional input was not accepted")))
    press(menu, Input.Cancel)
    newForm(menu)
    press(menu, Input.Text("id-😀"), Input.Erase)
    assertEquals(menu.state.draft.id, "id-")
  }

  test("details show logged-out and noncredential providers truthfully and ignore Enter") {
    for (status, label) <- Vector(
        CredentialStatus.LoggedOut -> "logged out",
        CredentialStatus.NotApplicable -> "not applicable"
      )
    do {
      val entry = local.copy(credentials = status, endpoint = "invalid-synthetic-secret")
      val menu = opened(Vector(entry))
      press(menu, Input.Enter)
      menu.takeAction()
      menu.complete(Right(Reply.Loaded(Some(entry))))
      assert(menu.lines.contains(s"Credentials: $label"))
      assert(menu.lines.contains("Endpoint: <invalid endpoint>"))
      assert(!menu.lines.mkString.contains("synthetic-secret"))
      press(menu, Input.Enter)
      assertEquals(menu.takeAction(), None)
    }
  }

  test("catalog client is lazy and operates only on the explicitly supplied synthetic file") {
    val directory = Files.createTempDirectory("provider-menu-suite-")
    val path = directory.resolve("synthetic.json")
    val fixture = """{"userName":"synthetic","providers":{}}"""
    try {
      Files.writeString(path, fixture)
      val client = TerminalProviderMenu.client(run(ReplProviderCatalog.make(path)))
      val save = client.execute(Operation.Save("new", Creation.ChatGPT))
      assertEquals(Files.readString(path), fixture)
      assertEquals(run(client.execute(Operation.Refresh)), Reply.Listed(Vector.empty))
      val reply = run(save)
      val saved = reply match {
        case Reply.Saved(entry) => entry
        case _                  => fail("expected saved provider")
      }
      assertEquals(saved.credentials, CredentialStatus.LoggedOut)
      assertEquals(run(client.execute(Operation.Inspect(saved.id))), Reply.Loaded(Some(saved)))
      assertEquals(run(client.execute(Operation.Refresh)), Reply.Listed(Vector(saved)))
    } finally {
      Files.deleteIfExists(path)
      Files.deleteIfExists(directory)
    }
  }
}
