package fmgp.yallmo.tool.utpc

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.provider.SandboxProviderHttpClient
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.Try
import scala.util.matching.Regex

/** HTTP execution for trusted UTCP templates, using an application-owned ZIO HTTP client.
  *
  * Supports JSON/form/text bodies, scalar query/header arguments, API keys, Basic auth and OAuth2 client credentials.
  * Variables resolve from the supplied map, then the process environment. Responses are bounded and requests are not
  * retried or redirected. Supply a plain client without retry/redirect aspects or ambient authentication headers.
  * Existing Sandbox proxy/CA configuration applies when opted in; it does not bypass network approvals. HTTPS is
  * required except for literal loopback hosts; deployment egress policy remains the host's responsibility. No schema
  * validation, automatic OpenAPI conversion, MCP support or tool registration is performed.
  */
final class UTPCHttpProviderImp(
    client: Client,
    variables: Map[String, String] = Map.empty,
    requestTimeout: Duration = 30.seconds,
    maxResponseBytes: Int = 1024 * 1024
) extends UTPCHttpProvider {
  import UTPCHttpProviderImp.*

  override def discover(transport: UTPCHttpTransport): IO[YallmoError, UTCPManual] =
    execute(transport, Json.Obj()).flatMap { case (_, body) =>
      ZIO.fromEither(body.fromJson[UTCPManual].left.map(_ => YallmoError.ProtocolFailure("Invalid UTCP manual")))
    }

  override def call(transport: UTPCHttpTransport, arguments: Json.Obj): IO[YallmoError, Json] =
    execute(transport, arguments).flatMap { case (contentType, body) =>
      if isJson(contentType) then
        ZIO.fromEither(body.fromJson[Json].left.map(_ => YallmoError.ProtocolFailure("Invalid JSON response")))
      else ZIO.succeed(Json.Str(body))
    }

  private def execute(transport: UTPCHttpTransport, arguments: Json.Obj): IO[YallmoError, (String, String)] =
    (for {
      _ <- ZIO
        .fail(YallmoError.InvalidConfig("HTTP timeout and response limit must be positive"))
        .unless(requestTimeout > Duration.Zero && maxResponseBytes > 0)
      // Validate trusted direct constructions too. Conversion-only metadata is not used during execution.
      raw <- ZIO.fromEither(
        transport
          .copy(authTools = None, extensions = Map.empty)
          .toJsonAST
          .left
          .map(_ => YallmoError.InvalidConfig("Invalid HTTP transport"))
      )
      resolved <- resolve(raw)
      configured <- ZIO.fromEither(
        resolved.as[UTPCHttpTransport].left.map(_ => YallmoError.InvalidConfig("Invalid HTTP transport"))
      )
      request <- ZIO.fromEither(prepare(configured, arguments))
      authenticated <- authenticate(request, configured.auth)
      result <- send(authenticated)
    } yield result).timeoutFail(
      YallmoError.HttpFailure("UTCP HTTP request timed out; execution outcome may be unknown")
    )(requestTimeout)

  private def resolve(value: Json): IO[YallmoError, Json] = value match {
    case Json.Str(text) =>
      ZIO
        .foldLeft(variable.findAllMatchIn(text).toList)((0, new StringBuilder)) { case ((offset, output), matched) =>
          val key = Option(matched.group(1)).getOrElse(matched.group(2))
          val lookup = variables
            .get(key)
            .fold(zio.System.env(key).mapError(_ => YallmoError.InvalidConfig("Cannot read UTCP variable")))(
              ZIO.some(_)
            )
          lookup
            .flatMap(value =>
              ZIO.fromOption(value).orElseFail(YallmoError.InvalidConfig(s"Missing UTCP variable '$key'"))
            )
            .map(value => (matched.end, output.append(text.substring(offset, matched.start)).append(value)))
        }
        .map { case (offset, output) => Json.Str(output.append(text.substring(offset)).toString) }
    case Json.Obj(fields) => ZIO.foreach(fields) { case (key, item) => resolve(item).map(key -> _) }.map(Json.Obj(_))
    case Json.Arr(items)  => ZIO.foreach(items)(resolve).map(Json.Arr(_))
    case other            => ZIO.succeed(other)
  }

  private def authenticate(request: Request, auth: Option[Json.Obj]): IO[YallmoError, Request] = auth match {
    case None         => ZIO.succeed(request)
    case Some(config) =>
      val fields = config.fields.toMap
      def field(name: String) = ZIO.fromEither(
        Wire
          .required[String](fields, name)
          .left
          .map(_ => YallmoError.AuthenticationFailure(s"Missing or invalid auth field '$name'"))
      )
      field("auth_type").flatMap {
        case "api_key" =>
          for {
            key <- field("api_key")
            name <- ZIO.fromEither(
              Wire
                .default(fields, "var_name", "X-Api-Key")
                .left
                .map(_ => YallmoError.AuthenticationFailure("Invalid API key name"))
            )
            location <- ZIO.fromEither(
              Wire
                .default(fields, "location", "header")
                .left
                .map(_ => YallmoError.AuthenticationFailure("Invalid API key location"))
            )
            result <- location match {
              case "header" => ZIO.fromEither(withHeader(request, name, key))
              case "query"  => ZIO.fromEither(withQuery(request, name, key))
              case "cookie" =>
                ZIO.fromEither(checkHeader(name, key)).as(request.addCookie(Cookie.Request(name, encode(key))))
              case _ => ZIO.fail(YallmoError.AuthenticationFailure("Unsupported API key location"))
            }
          } yield result
        case "basic" =>
          for {
            user <- field("username")
            password <- field("password")
            result <- ZIO.fromEither(withHeader(request, "Authorization", basic(user, password)))
          } yield result
        case "oauth2" =>
          for {
            endpoint <- field("token_url")
            id <- field("client_id")
            secret <- field("client_secret")
            scope <- ZIO.fromEither(
              Wire.default(fields, "scope", "").left.map(_ => YallmoError.AuthenticationFailure("Invalid OAuth2 scope"))
            )
            url <- ZIO.fromEither(checkedUrl(endpoint))
            form = Map("grant_type" -> "client_credentials", "client_id" -> id, "client_secret" -> secret) ++
              Option.when(scope.nonEmpty)("scope" -> scope)
            tokenRequest = Request(method = Method.POST, url = url, body = Body.fromString(formBody(form)))
              .addHeader("Content-Type", "application/x-www-form-urlencoded")
            response <- send(tokenRequest)
            token <- ZIO
              .fromEither(for {
                json <- response._2.fromJson[Json.Obj].left.map(_ => "Invalid OAuth2 token response")
                token <- Wire.required[String](json.fields.toMap, "access_token")
                _ <- Either.cond(token.nonEmpty, (), "Empty OAuth2 access token")
              } yield token)
              .mapError(_ => YallmoError.AuthenticationFailure("Invalid OAuth2 token response"))
            result <- ZIO.fromEither(withHeader(request, "Authorization", s"Bearer $token"))
          } yield result
        case _ => ZIO.fail(YallmoError.AuthenticationFailure("Unsupported UTCP authentication type"))
      }
  }

  private def send(request: Request): IO[YallmoError, (String, String)] =
    (for {
      outgoing <- ZIO.fromEither(SandboxProviderHttpClient.configure(client, request.url.encode))
      result <- ZIO
        .scoped {
          for {
            response <- Client.streaming(request)
            _ <- ZIO
              .fail(YallmoError.HttpFailure(s"UTCP endpoint returned HTTP ${response.status.code}"))
              .unless(response.status.isSuccess)
            bytes <- response.body.asStream.take(maxResponseBytes.toLong + 1L).runCollect
            _ <- ZIO
              .fail(YallmoError.HttpFailure("UTCP HTTP response exceeds the byte limit"))
              .when(bytes.size > maxResponseBytes)
          } yield (response.headers.get("Content-Type").getOrElse(""), new String(bytes.toArray, UTF_8))
        }
        .provideEnvironment(ZEnvironment(outgoing))
    } yield result).mapError(transportFailure)
}

object UTPCHttpProviderImp {

  /** Uses the caller's scoped HTTP client; the provider itself owns no persistent connections or token cache. */
  val live: URLayer[Client, UTPCHttpProvider] =
    ZLayer.fromFunction((client: Client) => new UTPCHttpProviderImp(client): UTPCHttpProvider)

  private val tlsFailure = "UTCP TLS handshake failed; check trusted CA certificates and configured proxy"
  private val dnsFailure = "UTCP hostname resolution failed; check DNS and configured proxy"
  private val connectionFailure = "UTCP connection failed; check endpoint access and configured proxy"
  private val networkFailure = "UTCP HTTP transport failed; execution outcome may be unknown"
  private val safeHttpMessages = Set(
    tlsFailure,
    dnsFailure,
    connectionFailure,
    networkFailure,
    "UTCP HTTP request timed out; execution outcome may be unknown",
    "UTCP HTTP response exceeds the byte limit"
  )
  private val httpStatus = "UTCP endpoint returned HTTP ([0-9]{3})".r

  /** Preserve diagnostic categories/statuses, never arbitrary provider messages, URLs, response bodies or causes. */
  private[utpc] def publicFailure(error: YallmoError): YallmoError = error match {
    case YallmoError.HttpFailure(httpStatus(status)) =>
      val hint = if status == "403" then "; check API access and network egress approvals" else ""
      YallmoError.HttpFailure(s"UTCP endpoint returned HTTP $status$hint")
    case YallmoError.HttpFailure(message) if safeHttpMessages.contains(message) => error
    case _: YallmoError.HttpFailure           => YallmoError.HttpFailure(networkFailure)
    case _: YallmoError.AuthenticationFailure =>
      YallmoError.AuthenticationFailure("UTCP authentication failed; check host-side credential configuration")
    case _: YallmoError.InvalidConfig =>
      YallmoError.InvalidConfig("Invalid UTCP HTTP configuration; check the manual, variables and proxy settings")
    case YallmoError.ProtocolFailure("Invalid JSON response") => error
    case _ => YallmoError.ProtocolFailure("UTPC provider execution failed; execution outcome may be unknown")
  }

  private def transportFailure(error: Throwable): YallmoError = error match {
    case known: YallmoError => known
    case _                  =>
      val causes = Iterator.iterate(Option(error))(_.flatMap(value => Option(value.getCause))).take(8).flatten.toList
      val message =
        if causes.exists(_.isInstanceOf[javax.net.ssl.SSLException]) then tlsFailure
        else if causes.exists(_.isInstanceOf[java.net.UnknownHostException]) then dnsFailure
        else if causes.exists(_.isInstanceOf[java.net.ConnectException]) then connectionFailure
        else networkFailure
      YallmoError.HttpFailure(message)
  }

  private val variable = "\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)".r
  private val argument = "(?<!\\$)\\{([A-Za-z_][A-Za-z0-9_]*)\\}".r
  private val headerName = "[!#$%&'*+.^_`|~0-9A-Za-z-]+".r
  private val forbiddenHeaders = Set("host", "content-length", "transfer-encoding", "connection")

  private def isJson(contentType: String): Boolean = {
    val mediaType = contentType.takeWhile(_ != ';').trim.toLowerCase(java.util.Locale.ROOT)
    mediaType == "application/json" || mediaType.endsWith("+json")
  }

  private def encode(value: String): String =
    if value == "." || value == ".." then value.map(_ => "%2E").mkString
    else URLEncoder.encode(value, UTF_8).replace("+", "%20")

  private def scalar(value: Json): Either[YallmoError, String] = value match {
    case Json.Str(value)  => Right(value)
    case value: Json.Num  => Right(value.toJson)
    case Json.Bool(value) => Right(value.toString)
    case _ => Left(YallmoError.ProtocolFailure("URL, query and header arguments must be strings, numbers or booleans"))
  }

  private def checkedUrl(value: String): Either[YallmoError, URL] = {
    val invalid = YallmoError.InvalidConfig(
      "UTCP URL must use HTTPS, or HTTP on a literal loopback host, without userinfo or fragments"
    )
    for {
      uri <- Try(URI.create(value)).toEither.left.map(_ => invalid)
      host = Option(uri.getHost).getOrElse("").toLowerCase(java.util.Locale.ROOT)
      _ <- Either.cond(
        host.nonEmpty && uri.getRawUserInfo == null && uri.getRawFragment == null &&
          (uri.getScheme == "https" || (uri.getScheme == "http" && Set("localhost", "127.0.0.1", "[::1]", "::1")
            .contains(host))),
        (),
        invalid
      )
      url <- URL.decode(value).left.map(_ => invalid)
    } yield url
  }

  private def checkHeader(name: String, value: String): Either[YallmoError, Unit] =
    Either.cond(
      headerName.matches(name) && !forbiddenHeaders.contains(name.toLowerCase(java.util.Locale.ROOT)) &&
        !value.exists(char => char == '\r' || char == '\n' || char == 0),
      (),
      YallmoError.InvalidConfig("Invalid or unsafe UTCP header")
    )

  private def withHeader(request: Request, name: String, value: String): Either[YallmoError, Request] =
    checkHeader(name, value).map(_ => request.removeHeader(name).addHeader(name, value))

  private def withQuery(request: Request, name: String, value: String): Either[YallmoError, Request] =
    Either.cond(
      !request.url.queryParams.map.contains(name),
      request.addQueryParam(name, value),
      YallmoError.InvalidConfig("An argument or credential conflicts with a fixed query parameter")
    )

  private def formBody(fields: Map[String, String]): String =
    fields.toSeq.sortBy(_._1).map { case (key, value) => s"${encode(key)}=${encode(value)}" }.mkString("&")

  private def prepare(transport: UTPCHttpTransport, arguments: Json.Obj): Either[YallmoError, Request] = {
    val fields = arguments.fields.toMap
    val placeholders = argument.findAllMatchIn(transport.url).map(_.group(1)).toSet
    val headerFields = transport.headerFields.getOrElse(Seq.empty).toSet
    val bodyField = transport.bodyField.filter(fields.contains).filterNot(placeholders.contains)
    for {
      _ <- Either.cond(
        fields.size == arguments.fields.size,
        (),
        YallmoError.ProtocolFailure("Duplicate UTCP arguments")
      )
      // Parse a marker URL before binding arguments: arguments may not select the scheme, host or port.
      marker <- checkedUrl(argument.replaceAllIn(transport.url, "UTPCARGUMENT"))
      authority = transport.url
        .drop(transport.url.indexOf("://") + 3)
        .takeWhile(char => char != '/' && char != '?' && char != '#')
      _ <- Either.cond(
        argument.findFirstIn(authority).isEmpty,
        (),
        YallmoError.InvalidConfig("Arguments cannot select an HTTP origin")
      )
      bindings <- placeholders.toSeq.foldLeft[Either[YallmoError, Map[String, String]]](Right(Map.empty)) {
        (result, key) =>
          for {
            current <- result
            value <- fields.get(key).toRight(YallmoError.ProtocolFailure(s"Missing URL argument '$key'"))
            text <- scalar(value)
          } yield current.updated(key, encode(text))
      }
      bound = argument.replaceAllIn(transport.url, matched => Regex.quoteReplacement(bindings(matched.group(1))))
      url <- checkedUrl(bound)
      _ <- Either.cond(
        url.host == marker.host && url.port == marker.port && url.scheme == marker.scheme,
        (),
        YallmoError.InvalidConfig("Arguments cannot select an HTTP origin")
      )
      body <- bodyField match {
        case None                                     => Right(Body.empty)
        case Some(_) if transport.httpMethod == "GET" =>
          Left(YallmoError.InvalidConfig("GET cannot have a configured request body"))
        case Some(key) if isJson(transport.contentType)         => Right(Body.fromString(fields(key).toJson))
        case Some(key) if transport.contentType == "text/plain" => scalar(fields(key)).map(Body.fromString(_))
        case Some(key) if transport.contentType == "application/x-www-form-urlencoded" =>
          fields(key) match {
            case Json.Obj(values) =>
              values
                .foldLeft[Either[YallmoError, Map[String, String]]](Right(Map.empty)) { case (result, (name, value)) =>
                  for { current <- result; text <- scalar(value) } yield current.updated(name, text)
                }
                .map(value => Body.fromString(formBody(value)))
            case _ => Left(YallmoError.ProtocolFailure("Form body must be an object of scalar values"))
          }
        case _ => Left(YallmoError.InvalidConfig("Unsupported UTCP request body content type"))
      }
      initial = Request(method = Method.fromString(transport.httpMethod), url = url, body = body)
      static <- (Map("Accept" -> "application/json", "Content-Type" -> transport.contentType) ++ transport.headers
        .getOrElse(Map.empty))
        .foldLeft[Either[YallmoError, Request]](Right(initial)) { case (result, (name, value)) =>
          result.flatMap(withHeader(_, name, value))
        }
      result <- fields.toSeq
        .filterNot { case (key, _) => placeholders.contains(key) || bodyField.contains(key) }
        .foldLeft[Either[YallmoError, Request]](Right(static)) { case (result, (name, value)) =>
          for {
            current <- result
            text <- scalar(value)
            next <-
              if headerFields.contains(name) then {
                if Set("authorization", "cookie").contains(name.toLowerCase(java.util.Locale.ROOT)) then
                  Left(YallmoError.InvalidConfig("Arguments cannot supply authentication headers"))
                else withHeader(current, name, text)
              } else withQuery(current, name, text)
          } yield next
        }
    } yield result
  }

  private def basic(username: String, password: String): String =
    "Basic " + Base64.getEncoder.encodeToString(s"$username:$password".getBytes(UTF_8))
}
