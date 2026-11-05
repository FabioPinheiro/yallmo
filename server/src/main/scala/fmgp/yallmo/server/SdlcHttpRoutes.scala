package fmgp.yallmo.server

import fmgp.yallmo.fs.FsError
import fmgp.yallmo.tool.sdlc.SdlcService
import zio.*
import zio.http.*
import zio.json.*

/** Read-only HTTP inspection of the server's full planning snapshots, not the LLM projections.
  *
  * Same trusted-environment policy as the host: no authentication or Origin restriction. Planning text is sensitive;
  * never expose this listener publicly. Requests do not invoke agents or mutate planning/conversation storage.
  */
final class SdlcHttpRoutes(service: SdlcService) {
  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "api" / "sdlc" / "tasks" -> handler { (request: Request) =>
      render(service.listTasks, request)
    },
    Method.GET / "api" / "sdlc" / "intents" -> handler { (request: Request) =>
      render(service.listIntents, request)
    }
  )

  private def render[A: JsonEncoder](records: IO[FsError, List[A]], request: Request): UIO[Response] =
    records
      .map(values => Response.json(values.toJson))
      .catchAll { error =>
        ZIO
          .logError(s"SDLC inspection failed: $error")
          .as(Response.json("""{"error":"Unable to read SDLC data"}""").status(Status.InternalServerError))
      }
      .map(WebAssets.secure(request, _))
}
