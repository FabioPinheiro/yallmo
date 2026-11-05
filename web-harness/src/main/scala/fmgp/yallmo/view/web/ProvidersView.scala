package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.server.protocol.YallmoProtocol.*

private[web] object ProvidersView {
  def apply(model: ProvidersModel, refreshes: Signal[Unit]): HtmlElement = {
    val state = model.state.signal
    div(
      cls := "page providers-page",
      refreshes --> (_ => model.reload()),
      EventStream.periodic(1000) --> (_ => model.expire()),
      onUnmountCallback(_ => model.clearRedirect()),
      h1("Providers"),
      p(
        cls := "muted",
        "Manage existing server-configured providers. Credential status shows presence only, not whether login is still valid."
      ),
      p(
        cls := "muted",
        "Administrative access: use only on a private, trusted server. Usernames are not authentication."
      ),
      button(
        typ := "button",
        "Reload providers",
        disabled <-- state.map(s => !s.enabled || s.loading),
        onClick --> (_ => model.reload())
      ),
      p(
        cls := "muted",
        role := "status",
        child.text <-- state.map(s =>
          if !s.enabled then "Connect as an operator to a configured server supporting provider administration."
          else if s.loading then "Loading providers…"
          else if s.entries.isEmpty then "No configured providers. Add a provider to the server configuration first."
          else ""
        )
      ),
      p(role := "status", child.text <-- state.map(_.notice)),
      ul(
        cls := "provider-list",
        children <-- state.map(_.entries).split(_.id) { (id, _, entry) =>
          li(
            cls := "provider-card",
            h2(code(id)),
            p(child.text <-- entry.map(e => s"Type: ${e.providerType}")),
            p(
              child.text <-- entry.map(e =>
                s"Credentials: ${e.credentials match {
                    case ProviderCredentialStatus.NotApplicable => "Not applicable"
                    case ProviderCredentialStatus.LoggedOut     => "Not configured"
                    case ProviderCredentialStatus.Configured    => "Configured (may need reconnecting)"
                  }}"
              )
            ),
            button(
              typ := "button",
              hidden <-- entry.map(_.providerType != ProviderKind.ChatGPT),
              disabled <-- state.map(s => !s.enabled || s.busy || s.login.nonEmpty),
              child.text <-- entry
                .map(e => if e.credentials == ProviderCredentialStatus.Configured then "Reconnect login" else "Log in"),
              onClick --> (_ => model.begin(id))
            )
          )
        }
      ),
      child <-- state.map(_.login).distinct.map {
        case None        => emptyNode
        case Some(login) =>
          sectionTag(
            cls := "provider-login",
            h2(s"Log in: ${login.providerId}"),
            p("1. Open OpenAI and approve access using your ChatGPT account."),
            a(
              href := login.started.authorizationUrl,
              target := "_blank",
              rel := "noopener noreferrer",
              "Open OpenAI login"
            ),
            p(
              "2. Copy the FULL http://localhost:1455/auth/callback URL from the address bar, including code and state. The localhost page may fail to load; copy its address anyway."
            ),
            p(
              cls := "muted",
              "Keep this URL private. This login expires after 15 minutes. Tokens are exchanged and saved only on the server."
            ),
            label(
              "Callback URL",
              input(
                cls := "provider-callback",
                typ := "password",
                autoComplete := "off",
                spellCheck := false,
                maxLength := ProvidersLoginComplete.MaxRedirectChars,
                value <-- state.map(_.redirect),
                disabled <-- state.map(_.busy),
                onInput.mapToValue --> model.redirect
              )
            ),
            div(
              cls := "composer-actions",
              button(
                typ := "button",
                child.text <-- state.map(s => if s.busy then "Saving login…" else "Complete login"),
                disabled <-- state.map(s =>
                  !s.enabled || s.busy || s.redirect.trim.isEmpty || s.redirect.length > ProvidersLoginComplete.MaxRedirectChars
                ),
                onClick --> (_ => model.finish())
              ),
              button(
                typ := "button",
                "Cancel",
                disabled <-- state.map(_.busy),
                onClick --> (_ => model.cancelLogin())
              )
            )
          )
      }
    )
  }
}
