package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

private[web] enum SdlcLoadState {
  case Loading
  case Ready(records: List[SdlcRecord])
  case Failed(message: String)
}

private[web] final case class SdlcState(
    tasks: SdlcLoadState = SdlcLoadState.Loading,
    intents: SdlcLoadState = SdlcLoadState.Loading
)

/** Each mount/refresh owns a generation. Late successes and failures never update a newer or unmounted page. */
private[web] final class SdlcModel(load: SdlcCollection => Future[List[SdlcRecord]])(using ExecutionContext) {
  val state: Var[SdlcState] = Var(SdlcState())
  private var mounted = false
  private var generation = 0L

  def mount(): Unit = {
    mounted = true
    refresh()
  }

  def unmount(): Unit = {
    mounted = false
    generation += 1
  }

  def refresh(): Unit = if mounted then {
    generation += 1
    val current = generation
    state.set(SdlcState())
    SdlcCollection.values.foreach { collection =>
      val request = try load(collection)
      catch { case NonFatal(error) => Future.failed(error) }
      request.onComplete { result =>
        if mounted && current == generation then {
          val next = result match {
            case Success(records) => SdlcLoadState.Ready(records)
            case Failure(error)   =>
              SdlcLoadState.Failed(Option(error.getMessage).filter(_.nonEmpty).getOrElse("Request failed"))
          }
          state.update { previous =>
            collection match {
              case SdlcCollection.Tasks   => previous.copy(tasks = next)
              case SdlcCollection.Intents => previous.copy(intents = next)
            }
          }
        }
      }
    }
  }
}
