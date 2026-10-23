package fmgp.yallmo.server.jsonrpc

import zio.json.*
import zio.json.ast.Json
import zio.json.internal.RetractReader

/** Standard JSON-RPC envelopes, using ordinary derived codecs. These are wire DTOs, not a dispatcher.
  *
  * Yallmo clients generate string IDs and send named parameter objects. Json keeps incoming numeric/null IDs
  * representable too. Adapters must validate version, ID/parameter shapes and result/error exclusivity before dispatch;
  * deriving a codec is not JSON-RPC validation, authorization or a connection lifecycle.
  *
  * Encode/decode the concrete envelope type, not a tagged Scala ADT: JSON-RPC has no wrapper discriminator.
  */
object JsonRpc {
  val Version: String = "2.0"

  // Framed messages must consume all input; zio-json decodeJson otherwise permits an unconsumed suffix.
  private val completeDocument = new JsonDecoder[Json] {
    override def unsafeDecode(trace: List[JsonError], in: RetractReader): Json = {
      val result = JsonDecoder[Json].unsafeDecode(trace, in)
      var next = in.read()
      while next == 32 || next == 13 || next == 10 || next == 9 do next = in.read()
      if next != -1 then throw JsonDecoder.UnsafeJson(JsonError.Message("Trailing input") :: trace)
      result
    }
  }

  def parseDocument(input: String): Either[String, Json] = completeDocument.decodeJson(input + " ")

  final case class Request[P](
      @jsonExplicitNull id: Json,
      method: String,
      params: P,
      jsonrpc: String = Version
  ) derives JsonCodec

  /** No ID member: notifications do not receive responses, even on failure. */
  final case class Notification[P](method: String, params: P, jsonrpc: String = Version) derives JsonCodec

  final case class Success[A](
      @jsonExplicitNull id: Json,
      @jsonExplicitNull result: A,
      jsonrpc: String = Version
  ) derives JsonCodec

  final case class Failure(@jsonExplicitNull id: Json, error: Error, jsonrpc: String = Version) derives JsonCodec

  /** Only safe public information belongs in errors/data, never stack traces, credentials or server paths. */
  final case class Error(code: Int, message: String, data: Option[Json] = None) derives JsonCodec

  object Error {
    val ParseError: Error = Error(-32700, "Parse error")
    val InvalidRequest: Error = Error(-32600, "Invalid Request")
    val MethodNotFound: Error = Error(-32601, "Method not found")
    val InvalidParams: Error = Error(-32602, "Invalid params")
    val InternalError: Error = Error(-32603, "Internal error")
  }
}
