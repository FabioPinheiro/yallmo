package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.Var
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import zio.Chunk

private[web] final case class ProviderLoginDraft(providerId: String, started: ProvidersLoginStart.Result)

private[web] final case class ProvidersState(
    enabled: Boolean = false,
    entries: List[ProviderSummary] = Nil,
    loading: Boolean = false,
    busy: Boolean = false,
    login: Option[ProviderLoginDraft] = None,
    redirect: String = "",
    notice: String = ""
) {
  override def toString: String = "ProvidersState(<redacted>)"
}

/** One root-owned controller using the shared WebSocket. Never retries login mutations or stores secrets in browser
  * storage. Reconfiguration/disconnect invalidates late callbacks. Callback text is cleared on submit and tab exit.
  */
private[web] final class ProvidersModel(
    list: () => Future[Chunk[ProviderSummary]],
    start: ProvidersLoginStart.Params => Future[ProvidersLoginStart.Result],
    complete: ProvidersLoginComplete.Params => Future[ProvidersLoginComplete.Result],
    cancel: ProvidersLoginCancel.Params => Future[Empty],
    now: () => Long = () => System.currentTimeMillis()
)(using ExecutionContext) {
  val state = Var(ProvidersState())
  private var version = 0L
  private var listVersion = 0L

  def configure(info: Initialize.Result): Unit = {
    state.now().login.foreach(login => discard(login.started.attemptId))
    version += 1
    listVersion += 1
    val enabled = info.identity.role == Role.Operator && List(
      ProvidersList.method,
      ProvidersLoginStart.method,
      ProvidersLoginComplete.method,
      ProvidersLoginCancel.method
    ).forall(info.methods.contains)
    state.set(ProvidersState(enabled = enabled))
  }

  def disable(): Unit = {
    version += 1
    listVersion += 1
    val uncertain = state.now().busy
    state.set(
      ProvidersState(notice =
        if uncertain then
          "Connection lost during login. Reconnect and reload providers to check whether credentials were saved."
        else "Disconnected. Reconnect to manage providers."
      )
    )
  }

  def reload(): Unit = if state.now().enabled then {
    listVersion += 1
    val requestVersion = listVersion
    state.update(_.copy(loading = true))
    Try(list()).fold(Future.failed, identity).onComplete {
      case Success(entries) if requestVersion == listVersion =>
        state.update(_.copy(entries = entries.toList, loading = false))
      case Failure(_) if requestVersion == listVersion =>
        state.update(_.copy(loading = false, notice = "Unable to load providers."))
      case _ => ()
    }
  }

  def redirect(value: String): Unit = state.update(_.copy(redirect = value))
  def clearRedirect(): Unit = state.update(_.copy(redirect = ""))

  def begin(providerId: String): Unit = {
    val current = state.now()
    if current.enabled && !current.busy && current.login.isEmpty && current.entries.exists(entry =>
        entry.id == providerId && entry.providerType == ProviderKind.ChatGPT
      )
    then {
      version += 1
      val requestVersion = version
      state.update(_.copy(busy = true, redirect = "", notice = ""))
      Try(start(ProvidersLoginStart.Params(providerId))).fold(Future.failed, identity).onComplete {
        case Success(started) if requestVersion == version =>
          if started.authorizationUrl.startsWith(
              "https://auth.openai.com/oauth/authorize?"
            ) && started.expiresAtMillis > now()
          then state.update(_.copy(busy = false, login = Some(ProviderLoginDraft(providerId, started))))
          else {
            discard(started.attemptId)
            state.update(_.copy(busy = false, notice = "Invalid or expired login response. Start a new login."))
          }
        case Failure(_) if requestVersion == version =>
          state.update(_.copy(busy = false, notice = "Unable to start login. Try again."))
        case _ => ()
      }
    }
  }

  def finish(): Unit = {
    expire()
    val current = state.now()
    if current.enabled && !current.busy && current.redirect.trim.nonEmpty &&
      current.redirect.length <= ProvidersLoginComplete.MaxRedirectChars
    then
      current.login.foreach { login =>
        val params = ProvidersLoginComplete.Params(login.started.attemptId, current.redirect.trim)
        version += 1
        val requestVersion = version
        state.update(_.copy(busy = true, redirect = "", notice = ""))
        Try(complete(params)).fold(Future.failed, identity).onComplete {
          case Success(result) if requestVersion == version =>
            state.update(
              _.copy(
                busy = false,
                login = None,
                notice =
                  if result.restartRequired then "Login saved. Restart the Yallmo server to use the new credentials."
                  else "Login saved and active. New requests use the new credentials; no server restart needed."
              )
            )
            reload()
          case Failure(_) if requestVersion == version =>
            discard(login.started.attemptId)
            state.update(
              _.copy(
                busy = false,
                login = None,
                notice =
                  "Login did not complete or its outcome is unknown. Reload providers before starting a new login."
              )
            )
          case _ => ()
        }
      }
  }

  def cancelLogin(): Unit = if !state.now().busy then {
    version += 1
    state.now().login.foreach(login => discard(login.started.attemptId))
    state.update(_.copy(login = None, redirect = "", notice = "Login cancelled."))
  }

  def expire(): Unit = if !state.now().busy then {
    state.now().login.filter(_.started.expiresAtMillis <= now()).foreach { _ =>
      cancelLogin()
      state.update(_.copy(notice = "Login expired. Start a new login."))
    }
  }

  private def discard(attemptId: String): Unit = {
    val _ = Try(cancel(ProvidersLoginCancel.Params(attemptId)))
      .fold(Future.failed, identity)
      .recover { case _ => Empty() }
  }
}
