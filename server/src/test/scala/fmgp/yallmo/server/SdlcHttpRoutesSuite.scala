package fmgp.yallmo.server

import fmgp.yallmo.fs.{FileSystem, WriteMode}
import fmgp.yallmo.model.Agent
import fmgp.yallmo.orchestration.{OrchestratorLive, RuntimeInstances}
import fmgp.yallmo.tool.sdlc.{PlanIntent, PlanIntentStorage, PlanTask, PlanTaskId, SdlcService, SdlcTools}
import fmgp.yallmo.tool.sdlc.llm.{PlanIntentSimplifiedModel, PlanTaskSimplifiedModel}
import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.orchestration.ExternalToolCall
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import zio.*
import zio.json.*

final class SdlcHttpRoutesSuite extends FunSuite {
  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()
  override def afterAll(): Unit = http.close()

  test("HTTP reads work without WebSocket/config initialization and do not create planning files or conversations") {
    run(ZIO.scoped {
      for {
        fixture <- start
        tasks <- request(fixture.port, "/api/sdlc/tasks")
        intents <- request(fixture.port, "/api/sdlc/intents")
        messages <- fixture.master.listMessages
        sessions <- fixture.master.listSessions
      } yield {
        List(tasks, intents).foreach { response =>
          assertEquals(response.statusCode(), 200)
          assertEquals(response.body(), "[]")
          assert(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
          assertEquals(response.headers().firstValue("Cache-Control").orElse(""), "no-store")
          assertEquals(response.headers().firstValue("X-Content-Type-Options").orElse(""), "nosniff")
          assert(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
        }
        assert(!Files.exists(fixture.root.resolve("sdlc")))
        assertEquals(messages, Chunk.empty)
        assertEquals(sessions, Chunk.empty)
      }
    })
  }

  test("HTTP returns full current PlanTask and PlanIntent objects, including timestamps and archived records") {
    run(ZIO.scoped {
      for {
        fixture <- start
        created <- fixture.service.setTasks(
          List(
            PlanTaskSimplifiedModel(
              PlanTaskId.New,
              "Task title",
              description = "Complete body",
              assumptions = List("Known callers")
            )
          )
        )
        intents <- fixture.service.setIntents(
          List(
            PlanIntentSimplifiedModel(
              "",
              "Intent title\nSuccess criteria",
              dependsOn = created.map(_.id.value),
              archived = true
            )
          )
        )
        _ <- fixture.service.setTasks(List(created.head.copy(contributesTo = intents.map(_.id), archived = true)))
        taskResponse <- request(fixture.port, "/api/sdlc/tasks")
        intentResponse <- request(fixture.port, "/api/sdlc/intents")
        fullTasks <- fixture.service.listTasks
        fullIntents <- fixture.service.listIntents
      } yield {
        assertEquals(taskResponse.statusCode(), 200)
        assertEquals(intentResponse.statusCode(), 200)
        assertEquals(taskResponse.body().fromJson[List[PlanTask]], Right(fullTasks))
        assertEquals(intentResponse.body().fromJson[List[PlanIntent]], Right(fullIntents))
        assert(fullTasks.head.archived)
        assert(fullIntents.head.archived)
        assert(taskResponse.body().contains("createdAt") && taskResponse.body().contains("updatedAt"))
        assert(intentResponse.body().contains("createdAt") && intentResponse.body().contains("updatedAt"))
        assertEquals(fullTasks.size, 1, "Historical task snapshots must not appear as duplicate current records")
        assertEquals(fullTasks.head.title, "Task title")
        assertEquals(fullTasks.head.description, "Complete body")
        assert(taskResponse.body().contains("\"title\"") && taskResponse.body().contains("\"description\""))
        assert(!taskResponse.body().contains("\"text\""))
      }
    })
  }

  test("tools and HTTP share the same service rather than separate storage owners") {
    run(ZIO.scoped {
      for {
        fixture <- start
        tools = SdlcTools.make(fixture.service)
        set = tools.find(_.definition.key == ToolKey.SdlcSetIntents).get
        created <- set.execute(
          ExternalToolCall(ToolKey.SdlcSetIntents, """{"intentsJson":[{"id":"","text":"Created by agent"}]}""")
        )
        read <- request(fixture.port, "/api/sdlc/intents")
        full <- fixture.service.listIntents
      } yield {
        assertEquals(created.text.fromJson[List[PlanIntentSimplifiedModel]], Right(full.map(_.toSimplifiedModel)))
        assertEquals(read.body().fromJson[List[PlanIntent]], Right(full))
      }
    })
  }

  test("the HTTP surface is GET-only and unknown SDLC paths remain 404, without filesystem writes") {
    run(ZIO.scoped {
      for {
        fixture <- start
        responses <- ZIO.foreach(List("POST", "PUT", "PATCH", "DELETE")) { method =>
          request(fixture.port, "/api/sdlc/intents", method)
        }
        unknown <- request(fixture.port, "/api/sdlc/unknown")
      } yield {
        responses.foreach { response =>
          assertEquals(response.statusCode(), 405)
          assertEquals(response.headers().firstValue("Allow").orElse(""), "GET")
        }
        assertEquals(unknown.statusCode(), 404)
        assert(!Files.exists(fixture.root.resolve("sdlc")))
      }
    })
  }

  test("journal errors yield sanitized JSON failures without hiding unrelated readable task data") {
    run(ZIO.scoped {
      for {
        fixture <- start
        _ <- fixture.service.setTasks(List(PlanTaskSimplifiedModel(PlanTaskId.New, "Readable task")))
        _ <- fixture.fs.writeUtf8(PlanIntentStorage.JournalPath, "PRIVATE invalid content\n", WriteMode.Replace)
        failed <- request(fixture.port, "/api/sdlc/intents")
        tasks <- request(fixture.port, "/api/sdlc/tasks")
        unchanged <- fixture.fs.readUtf8(PlanIntentStorage.JournalPath)
      } yield {
        assertEquals(failed.statusCode(), 500)
        assertEquals(failed.body(), """{"error":"Unable to read SDLC data"}""")
        assert(!failed.body().contains(fixture.root.toString))
        assert(!failed.body().contains("PRIVATE"))
        assertEquals(tasks.statusCode(), 200)
        assertEquals(unchanged, "PRIVATE invalid content\n")
      }
    })
  }

  private final case class Fixture(
      root: Path,
      fs: FileSystem,
      service: SdlcService,
      master: OrchestratorLive[Any],
      port: Int
  )

  private def start: ZIO[Scope, Throwable, Fixture] =
    for {
      root <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-sdlc-http-").toRealPath())) {
        root =>
          ZIO.attemptBlocking {
            val paths = Files.walk(root)
            try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
            finally paths.close()
          }.orDie
      }
      fs <- FileSystem.open(root.toString).mapError(error => new java.io.IOException(error.toString))
      service <- SdlcService.make(fs, fs)
      registry <- RuntimeInstances.fromAgents(Chunk.empty[Agent[Any]])
      master <- OrchestratorLive.inMemory(registry)
      port <- new OrchestratorWSServer(master, sdlc = Some(service)).start(0)
    } yield Fixture(root, fs, service, master, port)

  private def request(port: Int, path: String, method: String = "GET"): Task[HttpResponse[String]] =
    ZIO.attemptBlocking {
      http.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
          .timeout(java.time.Duration.ofSeconds(5))
          .method(method, HttpRequest.BodyPublishers.noBody())
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
