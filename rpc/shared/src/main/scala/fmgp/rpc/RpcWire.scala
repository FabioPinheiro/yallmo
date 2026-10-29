package fmgp.rpc

import zio.*
import zio.json.*
import zio.json.ast.Json

/** Private wire envelope, deliberately separate from service codecs and generated contracts. */
private[rpc] object RpcWire {
  final case class Request(id: String, service: String, version: Int, method: String, arguments: Chunk[Json])
      derives JsonCodec
  final case class Response(id: String, outcome: Either[RpcError, RpcResult])

  private[rpc] def document(input: String): Either[RpcError, Json] = {
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
      json <- envelope(input, Set(Set("id", "result"), Set("id", "failure"), Set("id", "error")))
      response <- json match {
        case Json.Obj(fields) =>
          val values = fields.toMap
          values("id") match {
            case Json.Str(id) if validId(id) =>
              values.get("result") match {
                case Some(value) => Right(Response(id, Right(RpcResult.Success(value))))
                case None        =>
                  values.get("failure") match {
                    case Some(value) => Right(Response(id, Right(RpcResult.Failure(value))))
                    case None        =>
                      values("error")
                        .as[RpcError.Code]
                        .left
                        .map(_ => RpcError(RpcError.Code.ProtocolError))
                        .map(code => Response(id, Left(RpcError(code))))
                  }
              }
            case _ => Left(RpcError(RpcError.Code.ProtocolError))
          }
        case _ => Left(RpcError(RpcError.Code.ProtocolError))
      }
    } yield response

  enum Control {
    case Open(request: Request, binary: Boolean)
    case Pull(override val id: String)
    case Cancel(override val id: String)
    def id: String = this match {
      case Open(request, _) => request.id
      case Pull(id)         => id
      case Cancel(id)       => id
    }
  }
  enum StreamReply {
    case Opened, End, Cancelled
    case Chunk(values: zio.Chunk[Json])
    case Failure(value: Json)
    case Error(error: RpcError)
  }
  private def protocol = RpcError(RpcError.Code.ProtocolError)
  def isStream(input: String): Either[RpcError, Boolean] = document(input).map {
    case Json.Obj(fields) => fields.exists(_._1 == "stream")
    case _                => false
  }
  def control(input: String): Either[RpcError, Control] = document(input).flatMap {
    case Json.Obj(fields) =>
      val m = fields.toMap
      (m.get("id"), m.get("stream"), m.get("op")) match {
        case (Some(Json.Str(id)), Some(Json.Num(revision)), Some(Json.Str(op)))
            if validId(id) && revision == java.math.BigDecimal.valueOf(2) =>
          val base = Set("id", "stream", "op")
          if op == "open" && m.keySet == base ++ Set("service", "version", "method", "arguments", "binary") then
            for {
              req <- request(
                Json.Obj(Chunk.fromIterable(fields.filterNot(x => Set("stream", "op", "binary").contains(x._1)))).toJson
              )
              binary <- m("binary") match { case Json.Bool(value) => Right(value); case _ => Left(protocol) }
            } yield Control.Open(req, binary)
          else if m.keySet == base && op == "pull" then Right(Control.Pull(id))
          else if m.keySet == base && op == "cancel" then Right(Control.Cancel(id))
          else Left(protocol)
        case _ => Left(protocol)
      }
    case _ => Left(protocol)
  }
  def streamControl(control: Control): String = {
    val fields: Chunk[(String, Json)] = control match {
      case Control.Open(req, binary) =>
        Chunk(
          "op" -> Json.Str("open"),
          "service" -> Json.Str(req.service),
          "version" -> Json.Num(req.version),
          "method" -> Json.Str(req.method),
          "arguments" -> Json.Arr(req.arguments),
          "binary" -> Json.Bool(binary)
        )
      case Control.Pull(_)   => Chunk("op" -> Json.Str("pull"))
      case Control.Cancel(_) => Chunk("op" -> Json.Str("cancel"))
    }
    Json.Obj(Chunk("id" -> Json.Str(control.id), "stream" -> Json.Num(2)) ++ fields).toJson
  }
  def streamResponse(id: String, reply: StreamReply): String = {
    val (op, extra) = reply match {
      case StreamReply.Opened         => ("opened", Chunk.empty)
      case StreamReply.End            => ("end", Chunk.empty)
      case StreamReply.Cancelled      => ("cancelled", Chunk.empty)
      case StreamReply.Chunk(values)  => ("chunk", Chunk("values" -> Json.Arr(values)))
      case StreamReply.Failure(value) => ("failure", Chunk("failure" -> value))
      case StreamReply.Error(error)   => ("error", Chunk("error" -> error.code.toJsonAST.toOption.get))
    }
    Json.Obj(Chunk("id" -> Json.Str(id), "stream" -> Json.Num(2), "op" -> Json.Str(op)) ++ extra).toJson
  }
  def streamReply(input: String): Either[RpcError, (String, StreamReply)] = document(input).flatMap {
    case Json.Obj(fields) =>
      val m = fields.toMap
      val base = Set("id", "stream", "op")
      (m.get("id"), m.get("stream"), m.get("op")) match {
        case (Some(Json.Str(id)), Some(Json.Num(revision)), Some(Json.Str(op)))
            if validId(id) && revision == java.math.BigDecimal.valueOf(2) =>
          val reply = op match {
            case "opened" if m.keySet == base           => Right(StreamReply.Opened)
            case "end" if m.keySet == base              => Right(StreamReply.End)
            case "cancelled" if m.keySet == base        => Right(StreamReply.Cancelled)
            case "chunk" if m.keySet == base + "values" =>
              m("values") match {
                case Json.Arr(values) => Right(StreamReply.Chunk(values))
                case _                => Left(protocol)
              }
            case "failure" if m.keySet == base + "failure" => Right(StreamReply.Failure(m("failure")))
            case "error" if m.keySet == base + "error"     =>
              m("error").as[RpcError.Code].left.map(_ => protocol).map(code => StreamReply.Error(RpcError(code)))
            case _ => Left(protocol)
          }
          reply.map(id -> _)
        case _ => Left(protocol)
      }
    case _ => Left(protocol)
  }

  // Revision/type magic followed by a positive, big-endian 64-bit stream ID. No payload encoding.
  val binaryHeaderBytes = 12
  def binary(id: String, bytes: Chunk[Byte]): Chunk[Byte] = {
    val value = id.toLong
    Chunk[Byte](82, 80, 2, 66) ++ Chunk.fromIterable((7 to 0 by -1).map(i => (value >>> (i * 8)).toByte)) ++ bytes
  }
  def binaryReply(bytes: Chunk[Byte]): Either[RpcError, (String, Chunk[Byte])] =
    if bytes.size < binaryHeaderBytes || bytes.take(4) != Chunk[Byte](82, 80, 2, 66) then Left(protocol)
    else {
      val id = bytes.slice(4, 12).foldLeft(0L)((acc, byte) => (acc << 8) | (byte.toLong & 255L))
      if id <= 0 then Left(protocol) else Right(id.toString -> bytes.drop(12))
    }

  def success(id: String, result: RpcResult): String = result match {
    case RpcResult.Success(value) => Json.Obj("id" -> Json.Str(id), "result" -> value).toJson
    case RpcResult.Failure(value) => Json.Obj("id" -> Json.Str(id), "failure" -> value).toJson
  }
  def failure(id: String, error: RpcError): String =
    s"""{"id":${id.toJson},"error":${error.code.toJson}}"""
}
