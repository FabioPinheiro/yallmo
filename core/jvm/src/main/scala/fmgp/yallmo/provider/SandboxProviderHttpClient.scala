package fmgp.yallmo.provider

import fmgp.yallmo.model.YallmoError
import zio.http.{Client, ClientSSLConfig, Proxy, URL}

import java.net.URI
import scala.util.Try

/** Explicit Sandbox-only transport for provider calls, not the server's internal RPC/WebSocket client.
  *
  * Netty does not automatically use HTTP_PROXY or the Sandbox's PEM CA bundle. Opt in through
  * YALLMO_SBX_HTTP_PROXY=true; keep TLS verification enabled and send provider requests through Docker's credential
  * injection proxy. Loopback provider endpoints remain local to the VM. No proxy URL/error details are logged.
  */
object SandboxProviderHttpClient {
  def configure(
      client: Client,
      endpoint: String,
      environment: Map[String, String] = sys.env
  ): Either[YallmoError, Client] = {
    def invalid = YallmoError.InvalidConfig("Invalid or missing Sandbox provider HTTP proxy configuration")
    if !environment.get("YALLMO_SBX_HTTP_PROXY").contains("true") then Right(client)
    else
      for {
        target <- Try(URI.create(endpoint)).toEither.left.map(_ => invalid)
        configured <-
          if Set("localhost", "127.0.0.1", "[::1]", "::1").contains(target.getHost) then Right(client)
          else {
            val keys =
              if target.getScheme == "https" then List("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
              else List("HTTP_PROXY", "http_proxy")
            for {
              raw <- keys.iterator.flatMap(environment.get).find(_.nonEmpty).toRight(invalid)
              uri <- Try(URI.create(raw)).toEither.left.map(_ => invalid)
              _ <- Either.cond(
                uri.getScheme == "http" && Option(uri.getHost).exists(_.nonEmpty) && uri.getRawUserInfo == null &&
                  uri.getRawQuery == null && uri.getRawFragment == null &&
                  Set("", "/").contains(Option(uri.getRawPath).getOrElse("")),
                (),
                invalid
              )
              url <- URL.decode(raw).left.map(_ => invalid)
              proxied = client.proxy(Proxy(url))
            } yield
              if target.getScheme == "https" then
                proxied.ssl(
                  ClientSSLConfig.FromCertFile(
                    environment
                      .get("SSL_CERT_FILE")
                      .filter(_.nonEmpty)
                      .getOrElse("/etc/ssl/certs/ca-certificates.crt")
                  )
                )
              else proxied
          }
      } yield configured
  }
}
