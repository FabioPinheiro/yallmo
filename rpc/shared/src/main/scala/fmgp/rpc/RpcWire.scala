package fmgp.rpc

import zio.*
import zio.json.*
import zio.json.ast.Json

/** Private wire envelope, deliberately separate from service codecs and generated contracts. */
private[rpc] object RpcWire {
  final case class Request(id: String, service: String, version: Int, method: String, arguments: Chunk[Json])
      derives JsonCodec
  final case class Response(id: String, result: Option[Json], error: Option[RpcError.Code]) derives JsonCodec

  private def document(input: String): Either[RpcError, Json] = {
    // Bound parser recursion before zio-json sees the document. Brackets inside strings do not count.
    var depth = 0
    var quoted = false
    var escaped = false
    val shallow = input.forall { char =>
      if escaped then escaped = false
      else if quoted && char == '\\' then escaped = true
      else if char == '"' then quoted = !quoted
      else if !quoted && (char == '{' || char == '[') then depth += 1
      else if !quoted && (char == '}' || char == ']') then depth -= 1
      depth <= 32 && depth >= 0
    }
    def unique(json: Json): Boolean = json match {
      case Json.Obj(fields) => fields.size == fields.map(_._1).toSet.size && fields.forall(x => unique(x._2))
      case Json.Arr(values) => values.forall(unique)
      case _                => true
    }
    if !shallow then Left(RpcError(RpcError.Code.ProtocolError))
    else
      input.fromJson[Json].left.map(_ => RpcError(RpcError.Code.ProtocolError)).flatMap { json =>
        Either.cond(unique(json), json, RpcError(RpcError.Code.ProtocolError))
      }
  }
  private def envelope(input: String, alternatives: Set[Set[String]]): Either[RpcError, Json] =
    document(input).flatMap {
      case obj @ Json.Obj(fields) if alternatives.contains(fields.map(_._1).toSet) => Right(obj)
      case _ => Left(RpcError(RpcError.Code.ProtocolError))
    }
  private def validId(id: String): Boolean =
    id.length <= 19 && id.toLongOption.exists(value => value > 0 && value.toString == id)

  def request(input: String): Either[RpcError, Request] =
    for {
      json <- envelope(input, Set(Set("id", "service", "version", "method", "arguments")))
      request <- json.as[Request].left.map(_ => RpcError(RpcError.Code.ProtocolError))
      _ <- Either.cond(
        validId(request.id) && request.service.length <= 160 && request.method.length <= 160 &&
          request.version > 0 && request.arguments.size <= 64,
        (),
        RpcError(RpcError.Code.ProtocolError)
      )
    } yield request

  def response(input: String): Either[RpcError, Response] =
    for {
      json <- envelope(input, Set(Set("id", "result"), Set("id", "error")))
      response <- json.as[Response].left.map(_ => RpcError(RpcError.Code.ProtocolError))
      // A null result represents Unit and must remain Some(Json.Null), not a missing optional field.
      result = json match {
        case Json.Obj(fields) => fields.find(_._1 == "result").map(_._2)
        case _                => None
      }
      _ <- Either.cond(
        validId(response.id) && (result.isDefined != response.error.isDefined),
        (),
        RpcError(RpcError.Code.ProtocolError)
      )
    } yield response.copy(result = result)

  def success(id: String, result: Json): String =
    Json.Obj("id" -> Json.Str(id), "result" -> result).toJson
  def failure(id: String, error: RpcError): String = Response(id, None, Some(error.code)).toJson
}
