package fmgp.rpc

import zio.*
import zio.json.ast.Json
import zio.stream.*

/** Runtime support for generated stream contracts. Public for expansion at external call sites. */
object RpcStreams {
  private def sanitize[E, A](
      stream: => ZStream[Any, E, A],
      failure: E => IO[RpcError, Json]
  ): ZStream[Any, RpcStreamError, A] =
    ZStream.suspend(stream).catchAllCause { cause =>
      // Match unary handling: interruption must never be serialized as a domain error.
      if cause.isInterrupted then ZStream.fromZIO(ZIO.interrupt)
      else if cause.defects.nonEmpty then
        ZStream.fail(RpcStreamError.Infrastructure(RpcError(RpcError.Code.ServiceFailure)))
      else
        cause.failureOption match {
          case Some(error) =>
            ZStream.fromZIO(
              failure(error)
                .mapError(RpcStreamError.Infrastructure(_))
                .flatMap(value => ZIO.fail(RpcStreamError.Domain(value)))
            )
          case None => ZStream.fail(RpcStreamError.Infrastructure(RpcError(RpcError.Code.ServiceFailure)))
        }
    }
  def hostBytes[E](
      stream: => ZStream[Any, E, Byte],
      failure: E => IO[RpcError, Json]
  ): ZStream[Any, RpcStreamError, RpcStreamChunk] =
    sanitize(stream, failure).chunks.map(RpcStreamChunk.Bytes(_))
  def hostJson[E, A](
      stream: => ZStream[Any, E, A],
      failure: E => IO[RpcError, Json],
      encode: A => IO[RpcError, Json]
  ): ZStream[Any, RpcStreamError, RpcStreamChunk] =
    sanitize(stream, failure).chunks
      .flatMap(values => ZStream.fromChunk(values).rechunk(64).chunks)
      .mapZIO(values =>
        ZIO.foreach(values)(encode).map(RpcStreamChunk.Values(_)).mapError(RpcStreamError.Infrastructure(_))
      )
  def clientError[E](error: RpcStreamError, mapper: RpcErrorMapper[E], decode: Json => IO[RpcError, E]): UIO[E] =
    error match {
      case RpcStreamError.Infrastructure(error) => ZIO.succeed(mapper(error))
      case RpcStreamError.Domain(value)         => decode(value).fold(mapper.apply, identity)
    }
  def clientBytes[E](
      stream: ZStream[Any, RpcStreamError, RpcStreamChunk],
      mapper: RpcErrorMapper[E],
      decode: Json => IO[RpcError, E]
  ): ZStream[Any, E, Byte] =
    stream
      .flatMap {
        case RpcStreamChunk.Bytes(values) => ZStream.fromChunk(values)
        case _ => ZStream.fail(RpcStreamError.Infrastructure(RpcError(RpcError.Code.ProtocolError)))
      }
      .mapErrorZIO(clientError(_, mapper, decode))
  def clientJson[E, A](
      stream: ZStream[Any, RpcStreamError, RpcStreamChunk],
      mapper: RpcErrorMapper[E],
      errorDecode: Json => IO[RpcError, E],
      decode: Json => IO[RpcError, A]
  ): ZStream[Any, E, A] =
    stream
      .flatMap {
        case RpcStreamChunk.Values(values) =>
          ZStream.fromChunk(values).mapZIO(value => decode(value).mapError(RpcStreamError.Infrastructure(_)))
        case _ => ZStream.fail(RpcStreamError.Infrastructure(RpcError(RpcError.Code.ProtocolError)))
      }
      .mapErrorZIO(clientError(_, mapper, errorDecode))
}
