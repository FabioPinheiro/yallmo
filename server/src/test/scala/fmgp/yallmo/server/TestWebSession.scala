package fmgp.yallmo.server

import fmgp.yallmo.model.{User, UserId}
import fmgp.yallmo.server.application.ServerExecutionService
import fmgp.yallmo.tool.sdlc.SdlcService
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.collection.concurrent.TrieMap
import zio.*
import zio.json.*

/** Real HTTP login for legacy transport fixtures; mock protocol peers still use their own handshake contracts. */
private[server] object TestWebSession {
  val password = "test-only-web-secret"
  // Precomputed with Python secrets.token_bytes(16) and hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 600000, 32).
  private val secretHash =
    "pbkdf2-sha256$600000$350d0134c3af0928fc095ad409e763dc$8abf90ca67af9d8cfacd36b73e52f07abd195f7cd780157af91ed26e97b59d11"
  val users: Map[UserId, User] =
    List(
      "observer",
      "alice",
      "bob",
      "human",
      "local-human",
      "fabio",
      "other",
      "presence",
      "first",
      "second",
      "operator"
    )
      .map(name => UserId(name) -> User(UserId(name), secretHash))
      .toMap
  val observerActors = Set(fmgp.yallmo.model.AgentId("user-observer"), fmgp.yallmo.model.AgentId("web-user-observer"))
  private val ports = TrieMap.empty[Int, Unit]
  private val loginGate = new java.util.concurrent.Semaphore(1)

  def start(backend: ServerExecutionService, sdlc: Option[SdlcService] = None): ZIO[Scope, Throwable, Int] = for {
    port <- new OrchestratorWSServer(backend, sdlc, Some(users)).start(0)
    _ <- ZIO.acquireRelease(ZIO.succeed(ports.put(port, ())))(_ => ZIO.succeed(ports.remove(port)))
  } yield port

  def connect(
      endpoint: String,
      requestTimeout: Duration = 15.seconds,
      userId: Option[UserId] = None,
      clientName: String = "yallmo-jvm"
  ): ZIO[Scope, OrchestratorClient.Error, OrchestratorWSClient] =
    if !scala.util.Try(URI.create(endpoint).getPort).toOption.exists(ports.contains) then
      OrchestratorWSClient.connect(endpoint, requestTimeout, userId, clientName)
    else
      ZIO.acquireReleaseWith(ZIO.attemptBlockingInterrupt(loginGate.acquire()).orDie)(_ =>
        ZIO.succeed(loginGate.release())
      ) { _ =>
        OrchestratorWSClient
          .connect(endpoint, requestTimeout, Some(userId.getOrElse(UserId("observer"))), clientName, Some(password))
          .ensuring(ZIO.sleep(210.millis))
      }

  def cookie(port: Int, user: String = "observer"): Task[String] =
    ZIO.acquireReleaseWith(ZIO.attemptBlockingInterrupt(loginGate.acquire()))(_ => ZIO.succeed(loginGate.release())) {
      _ =>
        ZIO
          .attemptBlocking {
            val client = HttpClient.newHttpClient()
            try {
              val origin = s"http://127.0.0.1:$port"
              val request = HttpRequest
                .newBuilder(URI.create(s"$origin/auth/login"))
                .header("Origin", origin)
                .header("X-Yallmo-CSRF", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Map("username" -> user, "password" -> password).toJson))
                .build()
              val response = client.send(request, HttpResponse.BodyHandlers.ofString())
              require(response.statusCode() == 200, s"Test login returned ${response.statusCode()}")
              response.headers().firstValue("Set-Cookie").orElseThrow().takeWhile(_ != ';')
            } finally client.close()
          }
          .ensuring(ZIO.sleep(210.millis))
    }
}
