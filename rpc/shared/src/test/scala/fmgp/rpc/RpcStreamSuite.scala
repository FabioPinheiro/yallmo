package fmgp.rpc

import munit.ZSuite
import scala.compiletime.testing.typeCheckErrors
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

object RpcStreamFixtures {
  enum FsError derives JsonCodec {
    case Missing(path: String)
    case Infra(code: RpcError.Code)
  }
  object FsError {
    given RpcErrorMapper[FsError] = RpcErrorMapper(error => FsError.Infra(error.code))
  }
  final case class Entry(name: String, number: Int) derives JsonCodec
  trait Files {
    def readBytes(path: String): ZStream[Any, FsError, Byte]
    def entries(): ZStream[Any, FsError, Entry]
    def units(): ZStream[Any, FsError, Unit]
    def nullable(): ZStream[Any, FsError, Option[String]]
    def partial(): ZStream[Any, FsError, Int]
    def fail(): ZStream[Any, FsError, Int]
    def bad(): ZStream[Any, Throwable, String]
    def thrown(): ZStream[Any, Throwable, String]
    def ping(value: String): IO[FsError, String]
  }
  val rpc = Rpc.derived[Files]("test.stream.files", 1)
  open class Service extends Files {
    def readBytes(path: String): ZStream[Any, FsError, Byte] =
      ZStream.fromChunk(Chunk.fromIterable((-128 to 127).map(_.toByte)))
    def entries(): ZStream[Any, FsError, Entry] = ZStream(Entry("hello", 1), Entry("🙂", 2))
    def units(): ZStream[Any, FsError, Unit] = ZStream((), ())
    def nullable(): ZStream[Any, FsError, Option[String]] = ZStream(None, Some("hello"))
    def partial(): ZStream[Any, FsError, Int] = ZStream(1, 2) ++ ZStream.fail(FsError.Missing("later"))
    def fail(): ZStream[Any, FsError, Int] = ZStream.fail(FsError.Missing("first"))
    def bad(): ZStream[Any, Throwable, String] = ZStream.dieMessage("secret")
    def thrown(): ZStream[Any, Throwable, String] = throw new Exception("secret")
    def ping(value: String): IO[FsError, String] = ZIO.succeed(value)
  }
  def loopback(dispatcher: RpcDispatcher): RpcConnection = new RpcConnection {
    def call(service: String, version: Int, method: String, args: Chunk[Json]): IO[RpcError, RpcResult] =
      dispatcher.dispatch(service, version, method, args)
    override def stream(
        service: String,
        version: Int,
        method: String,
        args: Chunk[Json],
        binary: Boolean
    ): ZStream[Any, RpcStreamError, RpcStreamChunk] = dispatcher.dispatchStream(service, version, method, args, binary)
    def close: UIO[Unit] = ZIO.unit
  }
}
class RpcStreamSuite extends ZSuite {
  import RpcStreamFixtures.*
  testZ("derived byte, DTO, Unit and nullable streams coexist with unary calls") {
    ZIO.suspendSucceed {
      val client = rpc.client(loopback(rpc.bind(new Service)))
      for {
        bytes <- client.readBytes("file").runCollect.either
        entries <- client.entries().runCollect.either
        units <- client.units().runCollect.either
        nullable <- client.nullable().runCollect.either
        pong <- client.ping("pong").either
      } yield {
        assertEquals(bytes, Right(Chunk.fromIterable((-128 to 127).map(_.toByte))))
        assertEquals(entries, Right(Chunk(Entry("hello", 1), Entry("🙂", 2))))
        assertEquals(units, Right(Chunk((), ())))
        assertEquals(nullable, Right(Chunk(None, Some("hello"))))
        assertEquals(pong, Right("pong"))
        assertEquals(rpc.descriptor.streamMethods("readBytes"), true)
        assertEquals(rpc.descriptor.streamMethods("entries"), false)
      }
    }
  }
  testZ("domain failures before and after output and sanitized construction/defects") {
    ZIO.suspendSucceed {
      val client = rpc.client(loopback(rpc.bind(new Service)))
      for {
        failure <- client.fail().runCollect.either
        values <- Ref.make(List.empty[Int])
        partial <- client.partial().tap(n => values.update(_ :+ n)).runDrain.either
        output <- values.get
        bad <- client.bad().runDrain.either
        thrown <- client.thrown().runDrain.either
      } yield {
        assertEquals(failure, Left(FsError.Missing("first")))
        assertEquals((output, partial), (List(1, 2), Left(FsError.Missing("later"))))
        assertEquals(bad.left.map(_.asInstanceOf[RpcError].code), Left(RpcError.Code.ServiceFailure))
        assertEquals(thrown.left.map(_.asInstanceOf[RpcError].code), Left(RpcError.Code.ServiceFailure))
      }
    }
  }
  testZ("mixed domain failure and interruption keeps interruption precedence after partial JSON and Byte output") {
    ZIO.suspendSucceed {
      ZIO.foreachDiscard(List(false, true)) { includeDefect =>
        val mixed = Cause.fail(FsError.Missing("later")) ++ Cause.interrupt(FiberId.None)
        val cause = if includeDefect then mixed ++ Cause.die(new Exception("secret")) else mixed
        assert(cause.isInterrupted, cause.toString)
        val client = rpc.client(loopback(rpc.bind(new Service {
          override def partial(): ZStream[Any, FsError, Int] =
            ZStream.fromChannel(ZChannel.write(Chunk(1, 2)) *> ZChannel.failCause(cause))
          override def readBytes(path: String): ZStream[Any, FsError, Byte] =
            ZStream.fromChannel(ZChannel.write(Chunk[Byte](3)) *> ZChannel.failCause(cause))
        })))
        for {
          seenJson <- Ref.make(List.empty[Int])
          json <- client.partial().tap(n => seenJson.update(_ :+ n)).runDrain.fork.flatMap(_.await)
          jsonValues <- seenJson.get
          seenBytes <- Ref.make(List.empty[Byte])
          bytes <- client.readBytes("mixed").tap(n => seenBytes.update(_ :+ n)).runDrain.fork.flatMap(_.await)
          byteValues <- seenBytes.get
        } yield {
          assert(json.isInterrupted, s"includeDefect=$includeDefect exit=$json")
          assertEquals(json.causeOption.flatMap(_.failureOption), None)
          assertEquals(jsonValues, List(1, 2))
          assert(bytes.isInterrupted, s"includeDefect=$includeDefect exit=$bytes")
          assertEquals(bytes.causeOption.flatMap(_.failureOption), None)
          assertEquals(byteValues, List[Byte](3))
        }
      }
    }
  }
  testZ("stream construction and take zero are lazy; repeat materialization invokes again") {
    ZIO.suspendSucceed {
      var invocations = 0
      val client = rpc.client(loopback(rpc.bind(new Service {
        override def entries(): ZStream[Any, FsError, Entry] = {
          invocations += 1
          super.entries()
        }
      })))
      val stream = client.entries()
      assertEquals(invocations, 0)
      for {
        _ <- stream.take(0).runDrain.orDieWith(e => new Exception(e.toString))
        _ <- ZIO.succeed(assertEquals(invocations, 0))
        _ <- stream.runDrain.either
        _ <- stream.runDrain.either
      } yield assertEquals(invocations, 2)
    }
  }
  testZ("method kind and encoding are checked before invoking service code") {
    ZIO.suspendSucceed {
      val dispatcher = rpc.bind(new Service)
      for {
        unary <- dispatcher.dispatch("test.stream.files", 1, "entries", Chunk.empty).either
        stream <- dispatcher
          .dispatchStream("test.stream.files", 1, "readBytes", Chunk(Json.Str("x")), false)
          .runDrain
          .either
      } yield {
        assertEquals(unary.left.map(_.code), Left(RpcError.Code.UnknownMethod))
        assertEquals(stream, Left(RpcStreamError.Infrastructure(RpcError(RpcError.Code.ProtocolError))))
      }
    }
  }
  test("macro rejects environments, Nothing, missing element/error codecs, nested stream shapes and missing mapper") {
    val environment =
      typeCheckErrors("""trait Bad { def a: zio.stream.ZStream[String, Throwable, Int] }; fmgp.rpc.Rpc.derived[Bad]""")
    val nothing =
      typeCheckErrors("""trait Bad { def a: zio.stream.ZStream[Any, Nothing, Int] }; fmgp.rpc.Rpc.derived[Bad]""")
    val element = typeCheckErrors(
      """class Value; trait Bad { def a: zio.stream.ZStream[Any, Throwable, Value] }; fmgp.rpc.Rpc.derived[Bad]"""
    )
    val error = typeCheckErrors(
      """class Err; trait Bad { def a: zio.stream.ZStream[Any, Err, Int] }; fmgp.rpc.Rpc.derived[Bad]"""
    )
    val nested = typeCheckErrors(
      """trait Bad { def a: zio.Task[zio.stream.ZStream[Any, Throwable, Int]] }; fmgp.rpc.Rpc.derived[Bad]"""
    )
    val mapper =
      typeCheckErrors("""trait Bad { def a: zio.stream.ZStream[Any, String, Int] }; fmgp.rpc.Rpc.derived[Bad]""")
    assert(environment.exists(_.message.contains("no environment")))
    assert(nothing.exists(_.message.contains("Nothing")))
    assert(element.exists(_.message.contains("JSON")))
    assert(error.exists(_.message.contains("JSON")))
    assert(nested.nonEmpty)
    assert(mapper.exists(_.message.contains("RpcErrorMapper")))
  }
  test("stream control and binary protocol strictly validate schema and IDs") {
    val valid = RpcWire.streamControl(RpcWire.Control.Open(RpcWire.Request("1", "test", 1, "bytes", Chunk.empty), true))
    assert(RpcWire.control(valid).isRight)
    assert(RpcWire.control(valid.dropRight(1) + ",\"id\":\"2\"}").isLeft)
    assert(RpcWire.control("""{"id":"01","stream":2,"op":"pull"}""").isLeft)
    assert(RpcWire.streamReply("""{"id":"1","stream":2,"op":"chunk","values":null}""").isLeft)
    val bytes = Chunk[Byte](-128, -1, 0, 127)
    assertEquals(
      RpcWire.binaryReply(RpcWire.binary("9223372036854775806", bytes)),
      Right("9223372036854775806" -> bytes)
    )
    assert(RpcWire.binaryReply(Chunk.empty).isLeft)
    assert(RpcWire.binaryReply(RpcWire.binary("1", bytes).updated(2, 1.toByte)).isLeft)
  }
}
