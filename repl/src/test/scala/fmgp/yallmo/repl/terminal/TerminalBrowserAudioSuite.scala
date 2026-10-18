package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.MessagePayload
import fmgp.yallmo.orchestration.RunContext
import fmgp.yallmo.repl.ReplState
import fmgp.yallmo.repl.browser.{BrowserAudioBridge, BrowserAudioError}
import fmgp.yallmo.repl.voice.VoiceRecordingTermination
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import munit.FunSuite
import zio.*

final class TerminalBrowserAudioSuite extends FunSuite {
  // Tests use isolated ephemeral ports; the REPL defaults to 5180.
  private def makeBridge: ZIO[Scope, BrowserAudioError, BrowserAudioBridge] =
    BrowserAudioBridge.start(zio.http.Server.Config.default.binding("127.0.0.1", 0))

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  test("bridge construction is lazy and single-flight, status does not start or announce it") {
    run(ZIO.scoped {
      for {
        starts <- Ref.make(0)
        announcements <- Ref.make(List.empty[String])
        control <- TerminalBrowserAudio.make(
          url => announcements.update(url :: _),
          starts.update(_ + 1) *> makeBridge
        )
        before <- starts.get
        ready <- control.recordingStarted
        results <- ZIO.foreachPar(1 to 8)(_ => control.speak(ReplState()).either)
        after <- starts.get
        urls <- announcements.get
        _ <- ZIO.succeed {
          assertEquals(before, 0)
          assertEquals(ready, false)
          assertEquals(after, 1)
          assert(results.forall(_.left.toOption.exists(_.code == "no_response")))
          assertEquals(urls.size, 8)
          assertEquals(urls.distinct.size, 1)
        }
      } yield ()
    })
  }

  test("setup link is shown only while disconnected, not during connected speech or recording") {
    run(ZIO.scoped {
      for {
        bridge <- makeBridge
        announcements <- Ref.make(List.empty[String])
        control <- TerminalBrowserAudio.make(url => announcements.update(url :: _), ZIO.succeed(bridge))
        state = ReplState(RunContext(Chunk(MessagePayload.Assistant("Speak this reply"))))
        disconnected <- control.speak(state).either
        before <- announcements.get
        client = new BrowserTestClient(bridge.setupUrl)
        _ <- ZIO.attemptBlocking(client.connect())
        _ <- ZIO.foreachDiscard(1 to 2) { _ =>
          for {
            speaking <- control.speak(state).fork
            operation <- ZIO.attemptBlocking(client.awaitOperation("speak"))
            _ <- ZIO.attemptBlocking {
              client.event(operation._1, "started")
              client.event(operation._1, "ended")
            }
            _ <- speaking.join
          } yield ()
        }
        cancelled <- control.recorder.recordUntil(ZIO.succeed(VoiceRecordingTermination.Cancel))
        connected <- announcements.get
        _ <- ZIO.attemptBlocking(assertEquals(client.post("/api/disconnect", "").statusCode(), 200))
        afterDisconnect <- control.speak(state).either
        after <- announcements.get
        _ <- ZIO.attemptBlocking(client.connect())
        _ <- control.speak(ReplState()).either
        reconnected <- announcements.get
      } yield {
        assertEquals(disconnected.left.toOption.map(_.code), Some("not_connected"))
        assertEquals(before, List(bridge.setupUrl))
        assertEquals(connected, before)
        assertEquals(cancelled, None)
        assertEquals(afterDisconnect.left.toOption.map(_.code), Some("not_connected"))
        assertEquals(after, List(bridge.setupUrl, bridge.setupUrl))
        assertEquals(reconnected, after)
      }
    })
  }

  test("startup failure remains local and a later explicit action can retry") {
    run(ZIO.scoped {
      for {
        starts <- Ref.make(0)
        control <- TerminalBrowserAudio.make(
          _ => ZIO.unit,
          starts.update(_ + 1) *> ZIO.fail(BrowserAudioError("startup", "Startup failed"))
        )
        first <- control.recorder.recordUntil(ZIO.succeed(VoiceRecordingTermination.Stop)).either
        second <- control.speak(ReplState()).either
        count <- starts.get
      } yield {
        assertEquals(count, 2)
        assertEquals(first.left.toOption.map(_.getMessage), Some("Startup failed"))
        assertEquals(second.left.toOption.map(_.code), Some("startup"))
      }
    })
  }

  test("owning scope closes the server and fails a pending recorder") {
    val (url, recording) = run(ZIO.scoped {
      for {
        url <- Promise.make[Nothing, String]
        control <- TerminalBrowserAudio.make(value => url.succeed(value).unit, makeBridge)
        _ <- control.speak(ReplState()).either
        setup <- url.await
        client = new BrowserTestClient(setup)
        _ <- ZIO.attemptBlocking(client.connect())
        recording <- control.recorder.recordUntil(ZIO.never).forkDaemon
        _ <- ZIO.attemptBlocking(client.awaitOperation())
      } yield (setup, recording)
    })
    val result = run(recording.join.either)
    assertEquals(result.left.toOption.collect { case error: BrowserAudioError => error.code }, Some("closed"))
    intercept[java.io.IOException](new BrowserTestClient(url).get("/"))
  }

  test("actual packaged page and worklet are served with CSP and MIME") {
    run(ZIO.scoped {
      for {
        bridge <- makeBridge
        _ <- ZIO.attemptBlocking {
          val client = new BrowserTestClient(bridge.setupUrl)
          val html = client.get("/")
          assertEquals(html.statusCode(), 200)
          assert(html.body().contains("Enable audio"))
          assert(html.headers().firstValue("Content-Type").orElse("").contains("text/html"))
          assert(html.headers().firstValue("Content-Security-Policy").orElse("").contains("worker-src 'self'"))
          assert(!html.headers().firstValue("Access-Control-Allow-Origin").isPresent)
          for asset <- List(
              "app.js",
              "http-client.js",
              "bridge-client.js",
              "browser-audio.js",
              "audio-core.js",
              "capture-worklet.js",
              "styles.css"
            )
          do {
            val response = client.get(s"/$asset")
            assertEquals(response.statusCode(), 200, asset)
            assert(response.body().nonEmpty, asset)
          }
        }
      } yield ()
    })
  }

  test("latest assistant text comes only from the supplied current conversation") {
    def state(messages: MessagePayload*) = ReplState(RunContext(Chunk.fromIterable(messages)))
    val resumed = state(
      MessagePayload.Assistant("older"),
      MessagePayload.User("private user"),
      MessagePayload.Assistant("resumed reply"),
      MessagePayload.ToolResult("tool", "private tool"),
      MessagePayload.Assistant("  ")
    ).copy(sessionId = Some("resumed"), continuationStart = 5)
    assertEquals(TerminalBrowserAudio.latestAssistant(resumed), Some("resumed reply"))
    assertEquals(TerminalBrowserAudio.latestAssistant(ReplState(sessionId = Some("new"))), None)
    assertEquals(TerminalBrowserAudio.latestAssistant(state(MessagePayload.Assistant("different"))), Some("different"))
    assertEquals(
      TerminalBrowserAudio.latestAssistant(state(MessagePayload.User("user"), MessagePayload.ToolResult("x", "tool"))),
      None
    )
  }
}

/** Synthetic HTTP browser, not a real page, microphone or speech engine. */
private[terminal] final class BrowserTestClient(setupUrl: String) {
  import zio.json.*
  private case class Operation(id: String, kind: String, phase: String, text: Option[String]) derives JsonDecoder
  private case class Poll(operation: Option[Operation]) derives JsonDecoder
  private val uri = URI.create(setupUrl)
  private val base = s"http://127.0.0.1:${uri.getPort}"
  private val token = uri.getFragment.stripPrefix("token=")
  private val client = HttpClient.newHttpClient()
  private val clientId = "synthetic_browser_client"

  def get(path: String): HttpResponse[String] = request("GET", path, Array.emptyByteArray)
  def post(path: String, json: String): HttpResponse[String] =
    request("POST", path, json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  def request(method: String, path: String, body: Array[Byte]): HttpResponse[String] =
    client.send(
      HttpRequest
        .newBuilder(URI.create(base + path))
        .timeout(java.time.Duration.ofSeconds(3))
        .header("Authorization", s"Bearer $token")
        .header("X-Browser-Client", clientId)
        .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  def connect(): Unit = {
    val response = post("/api/connect", s"""{"clientId":"$clientId"}""")
    require(response.statusCode() == 200, response.body())
  }
  def awaitOperation(kind: String = "record", phase: String = "start"): (String, Option[String]) = {
    val deadline = java.lang.System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
    var found = Option.empty[(String, Option[String])]
    while found.isEmpty && java.lang.System.nanoTime() < deadline do {
      val response = get("/api/poll")
      require(response.statusCode() == 200, response.body())
      val poll = response.body().fromJson[Poll].fold(error => throw new Exception(error), identity)
      found = poll.operation.filter(op => op.kind == kind && op.phase == phase).map(op => (op.id, op.text))
      if found.isEmpty then Thread.sleep(20)
    }
    found.getOrElse(throw new Exception(s"No $kind/$phase operation"))
  }
  def event(id: String, value: String): Unit = {
    val response = post("/api/event", s"""{"operationId":"$id","event":"$value"}""")
    require(response.statusCode() == 200, response.body())
  }
  def upload(id: String): Unit = {
    val bytes = java.nio.ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    bytes
      .put("RIFF".getBytes)
      .putInt(38)
      .put("WAVEfmt ".getBytes)
      .putInt(16)
      .putShort(1.toShort)
      .putShort(1.toShort)
      .putInt(16000)
      .putInt(32000)
      .putShort(2.toShort)
      .putShort(16.toShort)
      .put("data".getBytes)
      .putInt(2)
      .putShort(0.toShort)
    val response = request("POST", s"/api/audio/$id", bytes.array())
    require(response.statusCode() == 200, response.body())
  }
}
