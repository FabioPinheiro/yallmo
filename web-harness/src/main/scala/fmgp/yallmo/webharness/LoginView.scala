package fmgp.yallmo.webharness

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.{Failure, Success}
import zio.json.*

/** Only the server's HttpOnly cookie persists login. This gate mounts no application/socket before restoration. */
private[webharness] object LoginView {
  private[webharness] def username(body: String): Option[String] =
    body.fromJson[Map[String, String]].toOption.flatMap(_.get("username")).filter(_.nonEmpty)

  private def request(path: String, supplied: Option[(String, String)] = None): Future[dom.Response] = {
    val options = new dom.RequestInit {
      method = if path == "/auth/session" then dom.HttpMethod.GET else dom.HttpMethod.POST
      this.credentials = dom.RequestCredentials.`same-origin`
      headers = js.Dictionary("Content-Type" -> "application/json", "X-Yallmo-CSRF" -> "1")
      supplied.foreach { case (name, password) =>
        body = Map("username" -> name, "password" -> password).toJson
      }
    }
    dom.fetch(path, options).toFuture
  }

  def apply(application: (String, () => Unit, () => Unit) => HtmlElement)(using ExecutionContext): HtmlElement = {
    val user = Var(Option.empty[String])
    val restoring = Var(true)
    val busy = Var(false)
    val error = Var("")
    val name = Var("")
    val password = Var("")
    def restore(): Unit = {
      request("/auth/session")
        .flatMap { response =>
          if response.ok then response.text().toFuture.map(username)
          else if response.status == 401 then Future.successful(None)
          else Future.failed(new RuntimeException("Session check failed"))
        }
        .onComplete { result =>
          restoring.set(false)
          result match {
            case Success(value) => user.set(value)
            case Failure(_)     => error.set("Unable to check session. Please sign in again."); user.set(None)
          }
        }
    }
    def logout(): Unit = if !busy.now() then {
      busy.set(true)
      request("/auth/logout").onComplete { result =>
        busy.set(false)
        result match {
          case Success(response) if response.ok => user.set(None)
          case _                                => error.set("Logout failed. Please try again.")
        }
      }
    }
    def login(): Unit = if !busy.now() then {
      val supplied = (name.now(), password.now())
      password.set("")
      busy.set(true)
      error.set("")
      request("/auth/login", Some(supplied))
        .flatMap { response =>
          if response.ok then response.text().toFuture.map(username)
          else Future.successful(None)
        }
        .onComplete { result =>
          busy.set(false)
          result match {
            case Success(Some(value)) => user.set(Some(value))
            case _                    => error.set("Sign in failed. Check your credentials or try again shortly.")
          }
        }
    }
    div(
      onMountCallback(_ => restore()),
      child <-- restoring.signal.combineWith(user.signal).distinct.map {
        case (true, _)            => p(role := "status", "Checking session…")
        case (false, Some(value)) => application(value, () => logout(), () => restore())
        case _                    =>
          form(
            cls := "login",
            h1("Sign in to YALLMO"),
            label(
              "Username",
              input(
                idAttr := "login-username",
                nameAttr := "username",
                autoComplete := "username",
                required := true,
                maxLength := 128,
                value <-- name.signal,
                onInput.mapToValue --> name.writer
              )
            ),
            label(
              "Password",
              input(
                idAttr := "login-password",
                nameAttr := "password",
                typ := "password",
                autoComplete := "current-password",
                required := true,
                maxLength := 1024,
                value <-- password.signal,
                onInput.mapToValue --> password.writer
              )
            ),
            button(typ := "submit", "Sign in", disabled <-- busy.signal),
            onSubmit.preventDefault --> (_ => login())
          )
      },
      p(cls := "login-error", role := "alert", hidden <-- error.signal.map(_.isEmpty), child.text <-- error.signal)
    )
  }
}
