package fmgp.yallmo.provider.jev

import fmgp.yallmo.model.som.*
import fmgp.yallmo.model.som.SO.*
import zio.*
import zio.http.Client
import zio.json.{JsonCodec, JsonEncoder}

/** Manual smoke test for the local Laya-compatible System One endpoint.
  *
  * It is a test-scope main, not an automated test: it needs a running service at
  * `host.docker.internal:8000`. Run with:
  *
  * {{ sbt 'core/Test/runMain fmgp.yallmo.provider.jev.MainJevHttpProvider' }}
  */
object MainJevHttpProvider extends ZIOAppDefault {
  private final case class DemoState(request: String, changedFiles: Int) derives JsonCodec

  private val demoRequest =
    requestFor(DemoState("Review this change before merging it.", changedFiles = 7))

  override def run: ZIO[Any, Any, ExitCode] =
    program
      .as(ExitCode.success)
      .catchAll(error =>
        Console
          .printLineError(s"Local Jev/Laya smoke test failed: ${message(error)}")
          .as(ExitCode.failure)
      )
      .provide(Client.default)

  private def program: ZIO[Client, Throwable, Unit] =
    for {
      config <- ZIO.fromEither(JevConfig.from("http://host.docker.internal:8000/v1/systemone", None))
      client <- ZIO.service[Client]
      provider = JevHttpProvider(client, config)
      response <- provider.evaluate(demoRequest)
      _ <- Console.printLine(JsonEncoder[SystemOneResponse].encodeJson(response).toString)
    } yield ()

  private def requestFor(state: DemoState): SystemOneRequest[DemoState] =
    request(
      state = state,
      model = "jev-latest"
    )(
      "worker" -> choice(
        "Which worker should handle `request`?",
        "review" -> "Audit a change",
        "coding" -> "Implement code"
      ),
      "needs_review" -> noul("Does `request` require review?"),
      "complexity" -> score("How difficult is `request`?", "simple", "complex")
    )

  private def message(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
}
