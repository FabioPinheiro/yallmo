package fmgp.rpc

import munit.FunSuite
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
class RpcStreamSuite extends FunSuite {
  import RpcStreamFixtures.*
  private def run[A](effect: UIO[A]): A = Unsafe.unsafe { _ ?=>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }
  test("derived byte, DTO, Unit and nullable streams coexist with unary calls") {
    val client = rpc.client(loopback(rpc.bind(new Service)))
    assertEquals(
      run(client.readBytes("file").runCollect.either),
      Right(Chunk.fromIterable((-128 to 127).map(_.toByte)))
    )
    assertEquals(run(client.entries().runCollect.either), Right(Chunk(Entry("hello", 1), Entry("🙂", 2))))
    assertEquals(run(client.units().runCollect.either), Right(Chunk((), ())))
    assertEquals(run(client.nullable().runCollect.either), Right(Chunk(None, Some("hello"))))
    assertEquals(run(client.ping("pong").either), Right("pong"))
    assertEquals(rpc.descriptor.streamMethods("readBytes"), true)
    assertEquals(rpc.descriptor.streamMethods("entries"), false)
  }
  test("domain failures before and after output and sanitized construction/defects") {
    val client = rpc.client(loopback(rpc.bind(new Service)))
    assertEquals(run(client.fail().runCollect.either), Left(FsError.Missing("first")))
    val seen = run(for {
      values <- Ref.make(List.empty[Int])
      result <- client.partial().tap(n => values.update(_ :+ n)).runDrain.either
      output <- values.get
    } yield (output, result))
    assertEquals(seen, (List(1, 2), Left(FsError.Missing("later"))))
    assertEquals(
      run(client.bad().runDrain.either).left.map(_.asInstanceOf[RpcError].code),
      Left(RpcError.Code.ServiceFailure)
    )
    assertEquals(
      run(client.thrown().runDrain.either).left.map(_.asInstanceOf[RpcError].code),
      Left(RpcError.Code.ServiceFailure)
    )
  }
  test("mixed domain failure and interruption keeps interruption precedence after partial JSON and Byte output") {
    List(false, true).foreach { includeDefect =>
      val mixed = Cause.fail(FsError.Missing("later")) ++ Cause.interrupt(FiberId.None)
      val cause = if includeDefect then mixed ++ Cause.die(new Exception("secret")) else mixed
      assert(cause.isInterrupted, cause.toString)
      val client = rpc.client(loopback(rpc.bind(new Service {
        override def partial(): ZStream[Any, FsError, Int] =
          ZStream.fromChannel(ZChannel.write(Chunk(1, 2)) *> ZChannel.failCause(cause))
        override def readBytes(path: String): ZStream[Any, FsError, Byte] =
          ZStream.fromChannel(ZChannel.write(Chunk[Byte](3)) *> ZChannel.failCause(cause))
      })))
      val result = run {
        for {
          seenJson <- Ref.make(List.empty[Int])
          json <- client.partial().tap(n => seenJson.update(_ :+ n)).runDrain.fork.flatMap(_.await)
          jsonValues <- seenJson.get
          seenBytes <- Ref.make(List.empty[Byte])
          bytes <- client.readBytes("mixed").tap(n => seenBytes.update(_ :+ n)).runDrain.fork.flatMap(_.await)
          byteValues <- seenBytes.get
        } yield (json, jsonValues, bytes, byteValues)
      }
      assert(result._1.isInterrupted, s"includeDefect=$includeDefect exit=${result._1}")
      assertEquals(result._1.causeOption.flatMap(_.failureOption), None)
      assertEquals(result._2, List(1, 2))
      assert(result._3.isInterrupted, s"includeDefect=$includeDefect exit=${result._3}")
      assertEquals(result._3.causeOption.flatMap(_.failureOption), None)
      assertEquals(result._4, List[Byte](3))
    }
  }
  test("stream construction and take zero are lazy; repeat materialization invokes again") {
    var invocations = 0
    val client = rpc.client(loopback(rpc.bind(new Service {
      override def entries(): ZStream[Any, FsError, Entry] = {
        invocations += 1
        super.entries()
      }
    })))
    val stream = client.entries()
    assertEquals(invocations, 0)
    run(stream.take(0).runDrain.orDieWith(e => new Exception(e.toString)))
    assertEquals(invocations, 0)
    run(stream.runDrain.either)
    run(stream.runDrain.either)
    assertEquals(invocations, 2)
  }
  test("method kind and encoding are checked before invoking service code") {
    val dispatcher = rpc.bind(new Service)
    assertEquals(
      run(dispatcher.dispatch("test.stream.files", 1, "entries", Chunk.empty).either).left.map(_.code),
      Left(RpcError.Code.UnknownMethod)
    )
    assertEquals(
      run(dispatcher.dispatchStream("test.stream.files", 1, "readBytes", Chunk(Json.Str("x")), false).runDrain.either),
      Left(RpcStreamError.Infrastructure(RpcError(RpcError.Code.ProtocolError)))
    )
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
