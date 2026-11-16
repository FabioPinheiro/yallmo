package fmgp.rpc

import munit.ZSuite
import zio.*
import zio.json.*
import zio.stream.*

object RpcStreamCodecFixtures {
  import RpcTypedErrorFixtures.CodecValue
  trait Codecs {
    def value(mode: String): ZStream[Any, CodecValue, CodecValue]
    def failure(mode: String): ZStream[Any, CodecValue, CodecValue]
    def nullable(): ZStream[Any, Option[String], Unit]
    def throwable(): ZStream[Any, Throwable, String]
  }
  given RpcErrorMapper[Option[String]] = RpcErrorMapper(error => Some(error.code.toString))
  val rpc = Rpc.derived[Codecs]
  val service: Codecs = new Codecs {
    def value(mode: String): ZStream[Any, CodecValue, CodecValue] = ZStream(CodecValue("first"), CodecValue(mode))
    def failure(mode: String): ZStream[Any, CodecValue, CodecValue] = ZStream.fail(CodecValue(mode))
    def nullable(): ZStream[Any, Option[String], Unit] = ZStream(()) ++ ZStream.fail(None)
    def throwable(): ZStream[Any, Throwable, String] = ZStream("first") ++ ZStream.fail(new Exception("secret"))
  }
}
class RpcStreamCodecSuite extends ZSuite {
  import RpcStreamCodecFixtures.*
  import RpcTypedErrorFixtures.CodecValue
  testZ("stream element and error encoder failures sanitize; null domain error is retained") {
    ZIO.suspendSucceed {
      val client = rpc.client(RpcStreamFixtures.loopback(rpc.bind(service)))
      for {
        _ <- ZIO.foreachDiscard(List("left", "throw")) { mode =>
          for {
            value <- client.value(mode).runDrain.either
            failure <- client.failure(mode).runDrain.either
          } yield {
            assertEquals(value, Left(CodecValue("ProtocolError")))
            assertEquals(failure, Left(CodecValue("ProtocolError")))
          }
        }
        nullable <- client.nullable().runDrain.either
        throwable <- client.throwable().runDrain.either
      } yield {
        assertEquals(nullable, Left(None))
        assertEquals(
          throwable.left.toOption.collect { case e: RpcError => e.code },
          Some(RpcError.Code.ServiceFailure)
        )
      }
    }
  }
  testZ("malformed element decoding fails explicitly and closes the upstream scope") {
    ZIO.suspendSucceed {
      for {
        released <- Ref.make(false)
        connection = new RpcConnection {
          def call(
              service: String,
              version: Int,
              method: String,
              args: Chunk[zio.json.ast.Json]
          ): IO[RpcError, RpcResult] = ZIO.fail(RpcError(RpcError.Code.ProtocolError))
          override def stream(
              service: String,
              version: Int,
              method: String,
              args: Chunk[zio.json.ast.Json],
              binary: Boolean
          ): ZStream[Any, RpcStreamError, RpcStreamChunk] =
            ZStream
              .succeed(RpcStreamChunk.Values(Chunk(zio.json.ast.Json.Str("throw"))))
              .ensuring(released.set(true))
          def close: UIO[Unit] = ZIO.unit
        }
        outcome <- rpc.client(connection).value("x").runDrain.either
        closed <- released.get
      } yield assertEquals((outcome, closed), (Left(CodecValue("ProtocolError")), true))
    }
  }
}
