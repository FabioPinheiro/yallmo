package fmgp.yallmo.provider.jev

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.som.{SOMProvider, SystemOneResponse}
import zio.*
import zio.http.*
import zio.json.*

/** HTTP System One provider compatible with TypeSafe Jev and the supported Laya envelope subset.
  *
  * This class deliberately implements only the System One protocol. It is not an LLM or an [[fmgp.yallmo.model.Agent]]:
  * callers use typed System One answers in their own deterministic orchestration policy.
  */
final case class JevHttpProvider private[jev] (client: Client, config: JevConfig) extends SOMProvider {

  override protected def evaluateRequestJson(requestJson: String): IO[YallmoError, SystemOneResponse] = {
    val unauthenticatedRequest = baseRequest(requestJson)
    val request = config.apiKey.fold(unauthenticatedRequest) { apiKey =>
      unauthenticatedRequest.addHeader("Authorization", s"Bearer $apiKey")
    }

    (for {
      response <- Client.batched(request).provideEnvironment(ZEnvironment(client))
      body <- response.body.asString
      result <-
        if response.status.isSuccess then
          ZIO.fromEither(JevHttpProvider.decodeResponse(body).left.map(YallmoError.ProtocolFailure.apply))
        else if response.status.code == 401 then
          ZIO.fail(YallmoError.AuthenticationFailure("Jev System One rejected the configured API key"))
        else
          ZIO.fail(
            YallmoError.HttpFailure(
              s"Jev System One returned HTTP ${response.status.code}: ${JevHttpProvider.safeErrorBody(body)}"
            )
          )
    } yield result)
      .mapError(YallmoError.fromThrowable)
      .timeoutFail(YallmoError.HttpFailure("Jev System One request timed out"))(config.requestTimeout)
  }

  private def baseRequest(requestJson: String): Request =
    Request
      .post(config.systemOneUrl, Body.fromString(requestJson))
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "application/json")
}

object JevHttpProvider {

  /** Constructs the provider service from the shared ZIO HTTP client and environment configuration. */
  val live: ZLayer[Client, YallmoError.InvalidConfig, SOMProvider] =
    ZLayer.fromZIO {
      for {
        client <- ZIO.service[Client]
        config <- JevConfig.load
      } yield (JevHttpProvider(client, config): SOMProvider)
    }

  private[jev] def decodeResponse(body: String): Either[String, SystemOneResponse] =
    body.fromJson[SystemOneResponse]

  private[jev] def safeErrorBody(body: String): String =
    body.trim.replaceAll("\\s+", " ").take(1000) match {
      case ""    => "<empty response body>"
      case value => value
    }
}

/** Connection settings for a System One endpoint.
  *
  * `apiKey` is optional only because a deliberately configured local Laya service may be unauthenticated. Production
  * composition for the official Jev endpoint must provide `TYPESAFE_API_KEY`.
  */
final case class JevConfig private (systemOneUrl: String, apiKey: Option[String], requestTimeout: Duration)

object JevConfig {
  val SystemOneUrlEnvironmentVariable = "JEV_SYSTEM_ONE_URL"
  val ApiKeyEnvironmentVariable = "TYPESAFE_API_KEY"
  val DefaultRequestTimeout: Duration = 20.seconds

  def load: IO[YallmoError.InvalidConfig, JevConfig] =
    ZIO.fromEither {
      Option(java.lang.System.getenv(SystemOneUrlEnvironmentVariable))
        .toRight(
          YallmoError.InvalidConfig(s"$SystemOneUrlEnvironmentVariable is required")
        )
        .flatMap(url => from(url, Option(java.lang.System.getenv(ApiKeyEnvironmentVariable))))
    }

  private[jev] def from(
      systemOneUrl: String,
      apiKey: Option[String],
      requestTimeout: Duration = DefaultRequestTimeout
  ): Either[YallmoError.InvalidConfig, JevConfig] = {
    val normalizedUrl = systemOneUrl.trim.stripSuffix("/")
    for {
      _ <- Either.cond(
        normalizedUrl.nonEmpty,
        (),
        YallmoError.InvalidConfig(s"$SystemOneUrlEnvironmentVariable must not be blank")
      )
      uri <- scala.util.Try(java.net.URI.create(normalizedUrl)).toEither.left.map { error =>
        YallmoError.InvalidConfig(s"Invalid $SystemOneUrlEnvironmentVariable: ${error.getMessage}")
      }
      _ <- Either.cond(
        uri.getScheme == "http" || uri.getScheme == "https",
        (),
        YallmoError.InvalidConfig(s"$SystemOneUrlEnvironmentVariable must use http or https")
      )
      _ <- Either.cond(
        uri.getHost != null,
        (),
        YallmoError.InvalidConfig(s"$SystemOneUrlEnvironmentVariable must include a host")
      )
      _ <- Either.cond(
        requestTimeout > Duration.Zero,
        (),
        YallmoError.InvalidConfig("Jev request timeout must be positive")
      )
    } yield JevConfig(normalizedUrl, apiKey.map(_.trim).filter(_.nonEmpty), requestTimeout)
  }
}
