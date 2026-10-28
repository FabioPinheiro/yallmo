package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js.timers.{clearTimeout, setTimeout}

private[web] final case class UiNotice(message: String, level: String = "info")

/** Temporary feedback owned by the application; recovery instructions stay in the conversation. */
private[web] final class UiNotifications {
  private val events = new EventBus[UiNotice]
  def publish(notice: UiNotice): Unit = events.emit(notice)

  def view(): HtmlElement = {
    val visible = Var(List.empty[(Long, UiNotice)])
    var nextId = 0L
    div(
      cls := "toasts",
      aria.live := "polite",
      aria.label := "Notifications",
      events.events --> { notice =>
        notice.level match {
          case "error" => dom.console.error(notice.message)
          case "warn"  => dom.console.warn(notice.message)
          case _       => dom.console.info(notice.message)
        }
        nextId += 1
        visible.update(items => (items :+ (nextId -> notice)).takeRight(3))
      },
      children <-- visible.signal.split(_._1) { (id, initial, _) =>
        var timer = Option.empty[scala.scalajs.js.timers.SetTimeoutHandle]
        div(
          cls := s"toast toast-${initial._2.level}",
          role := "status",
          initial._2.message,
          onMountCallback { _ =>
            timer = Some(setTimeout(5000) { visible.update(_.filterNot(_._1 == id)) })
          },
          onUnmountCallback { _ => timer.foreach(clearTimeout); timer = None }
        )
      }
    )
  }
}
