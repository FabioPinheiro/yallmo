package fmgp.yallmo.view.web

import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

final class SdlcHttpSuite extends FunSuite {
  test("HTTP reads use the exact collection endpoints and decode full bare-array objects") {
    val paths = mutable.ArrayBuffer.empty[String]
    val http = new SdlcHttp(endpoint => {
      paths += endpoint
      Future.successful("""[{"id":"stored","text":"Title","unknown":{"keep":true}}]""")
    })
    for {
      tasks <- http.load(SdlcCollection.Tasks)
      intents <- http.load(SdlcCollection.Intents)
    } yield {
      assertEquals(paths.toList, List("/api/sdlc/tasks", "/api/sdlc/intents"))
      assertEquals(tasks, intents)
      assert(tasks.head.prettyJson.contains("unknown"))
    }
  }

  test("decode failures include the endpoint and failing field") {
    val http = new SdlcHttp(_ => Future.successful("""[{"id":"bad"}]"""))
    http.load(SdlcCollection.Intents).failed.map { error =>
      assert(error.getMessage.contains("/api/sdlc/intents"))
      assert(error.getMessage.contains("Item 1: Missing 'text'"))
    }
  }

  test("transport errors propagate to the page model without retries or fallback") {
    var calls = 0
    val http = new SdlcHttp(_ => {
      calls += 1
      Future.failed(new RuntimeException("GET /api/sdlc/tasks failed: HTTP 500"))
    })
    http.load(SdlcCollection.Tasks).failed.map { error =>
      assertEquals(error.getMessage, "GET /api/sdlc/tasks failed: HTTP 500")
      assertEquals(calls, 1)
    }
  }
}
