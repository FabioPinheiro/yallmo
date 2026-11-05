package fmgp.yallmo.server

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import zio.*
import zio.http.{WebSocketConfig, WebSocketFrame}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import zio.json.*
import zio.json.ast.Json

/** Boundary checks only; envelope and application values still use the standard derived zio-json codecs. */
private[server] object RpcWire {
  val MaxMessageBytes: Int = 1024 * 1024
  val webSocketConfig: WebSocketConfig = WebSocketConfig.default
    .decoderConfig(zio.http.SocketDecoder.default.maxFramePayloadLength(MaxMessageBytes))
    .forwardCloseFrames(true)
    .forceCloseTimeout(1.second)

  def parse(input: String): Either[String, Json] = JsonRpc.parseDocument(input)

  /** Never log binary base64 or OAuth material, even in batches, responses, or malformed requests. */
  def tracePayload(input: String): String = {
    def redact(value: Json): Json = value match {
      case Json.Obj(fields) =>
        val login = fields.exists {
          case ("method", Json.Str(method)) => method.startsWith("providers.login.")
          case _                            => false
        }
        Json.Obj(fields.map { case (key, item) =>
          val sensitive = Set(
            "dataBase64",
            "redirectUrl",
            "authorizationUrl",
            "attemptId",
            "accessToken",
            "refreshToken",
            "codeVerifier"
          ).contains(key) ||
            (login && key == "params")
          (key, if sensitive then Json.Str("<redacted>") else redact(item))
        })
      case Json.Arr(values) => Json.Arr(values.map(redact))
      case other            => other
    }
    parse(input).fold(_ => "<invalid JSON omitted>", value => redact(value).toJson).toJson
  }
  def size(input: String): Int = input.getBytes(StandardCharsets.UTF_8).length
}

/** Assemble frames before JSON parsing, with a total byte bound and strict continuation UTF-8 decoding. */
private[server] final class WebSocketText private (partial: Ref[Option[Chunk[Byte]]]) {
  import WebSocketText.Failure

  def accept(frame: WebSocketFrame): IO[Failure, Option[String]] =
    frame match {
      case text: WebSocketFrame.Text =>
        for {
          previous <- partial.get
          _ <- ZIO.fail(Failure(1002, "Unexpected text frame")).when(previous.isDefined)
          // ZIO HTTP already decodes the initial text frame, losing incomplete UTF-8 bytes. Reject that case
          // rather than silently corrupting a message. Continuations are raw bytes and decoded together below.
          _ <- ZIO
            .fail(Failure(1007, "Incomplete initial UTF-8 fragment"))
            .when(!text.isFinal && text.text.contains('\ufffd'))
          bytes = Chunk.fromArray(text.text.getBytes(StandardCharsets.UTF_8))
          result <- accumulate(bytes, text.isFinal)
        } yield result
      case continuation: WebSocketFrame.Continuation =>
        for {
          previous <- partial.get.someOrFail(Failure(1002, "Unexpected continuation"))
          _ <- ZIO
            .fail(Failure(1009, "Message too large"))
            .when(previous.size.toLong + continuation.buffer.size > RpcWire.MaxMessageBytes)
          result <- accumulate(previous ++ continuation.buffer, continuation.isFinal)
        } yield result
      case _ => ZIO.fail(Failure(1003, "Only text messages are supported"))
    }

  private def accumulate(bytes: Chunk[Byte], isFinal: Boolean): IO[Failure, Option[String]] =
    if bytes.size > RpcWire.MaxMessageBytes then ZIO.fail(Failure(1009, "Message too large"))
    else if !isFinal then partial.set(Some(bytes)).as(None)
    else
      for {
        _ <- partial.set(None)
        text <- ZIO
          .attempt {
            StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes.toArray))
              .toString
          }
          .mapError(_ => Failure(1007, "Invalid UTF-8"))
      } yield Some(text)
}

private[server] object WebSocketText {
  final case class Failure(code: Int, reason: String)
  def make: UIO[WebSocketText] = Ref.make(Option.empty[Chunk[Byte]]).map(new WebSocketText(_))
}
