package fmgp.yallmo.tool.utpc

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** RFC `transport_type: "http"` descriptor, with mappings from the accompanying HTTP protocol documentation.
  *
  * Authentication objects and `$VAR` / `${VAR}` placeholders are preserved, never resolved here. This describes a
  * request; it grants no network access. A concrete provider must enforce destination, credential and size policies.
  * Missing method/content type normalize to GET/application-json on decoding. Other optional fields remain absent.
  * Reserved wire fields cannot be overridden by `extensions`.
  */
final case class UTPCHttpTransport(
    name: String,
    url: String,
    httpMethod: String = "GET",
    contentType: String = "application/json",
    auth: Option[Json.Obj] = None,
    headers: Option[Map[String, String]] = None,
    bodyField: Option[String] = None,
    headerFields: Option[Seq[String]] = None,
    authTools: Option[Json.Obj] = None,
    extensions: Map[String, Json] = Map.empty
)

object UTPCHttpTransport {
  private val keys = Set(
    "transport_type",
    "name",
    "url",
    "http_method",
    "content_type",
    "auth",
    "headers",
    "body_field",
    "header_fields",
    "auth_tools"
  )
  private val methods = Set("GET", "POST", "PUT", "DELETE", "PATCH")

  given JsonCodec[UTPCHttpTransport] = Wire.codec(
    fields =>
      for {
        kind <- Wire.required[String](fields, "transport_type")
        _ <- Either.cond(kind == "http", (), s"unsupported transport_type '$kind'; expected 'http'")
        name <- Wire.nonBlank(fields, "name")
        url <- Wire.nonBlank(fields, "url")
        method <- Wire.default(fields, "http_method", "GET")
        _ <- Either.cond(methods.contains(method), (), s"unsupported http_method '$method'")
        contentType <- Wire.default(fields, "content_type", "application/json")
        auth <- Wire.optional[Json.Obj](fields, "auth")
        headers <- Wire.optional[Map[String, String]](fields, "headers")
        bodyField <- Wire.optional[String](fields, "body_field")
        headerFields <- Wire.optional[Seq[String]](fields, "header_fields")
        authTools <- Wire.optional[Json.Obj](fields, "auth_tools")
      } yield UTPCHttpTransport(
        name,
        url,
        method,
        contentType,
        auth,
        headers,
        bodyField,
        headerFields,
        authTools,
        fields -- keys
      ),
    transport =>
      Wire.obj(
        Map(
          "transport_type" -> Json.Str("http"),
          "name" -> Json.Str(transport.name),
          "url" -> Json.Str(transport.url),
          "http_method" -> Json.Str(transport.httpMethod),
          "content_type" -> Json.Str(transport.contentType)
        ) ++ transport.auth.map(value => "auth" -> value) ++
          transport.headers.map(value => "headers" -> Wire.obj(value.view.mapValues(Json.Str(_)).toMap, Map.empty)) ++
          transport.bodyField.map(value => "body_field" -> Json.Str(value)) ++
          transport.headerFields.map(value =>
            "header_fields" -> Json.Arr(Chunk.fromIterable(value.map(Json.Str(_))))
          ) ++
          transport.authTools.map(value => "auth_tools" -> value),
        transport.extensions -- keys
      )
  )
}
