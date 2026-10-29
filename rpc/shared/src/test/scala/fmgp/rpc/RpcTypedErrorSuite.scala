package fmgp.rpc

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import zio.*
import zio.json.*
import zio.json.ast.Json

object RpcTypedErrorFixtures {
  enum FsError derives JsonCodec {
    case NotFound(path: String)
    case Infrastructure(code: RpcError.Code)
  }
  object FsError {
    given RpcErrorMapper[FsError] = RpcErrorMapper(error => FsError.Infrastructure(error.code))
  }
  trait Files {
    def read(path: String): IO[FsError, String]
    def broken(): IO[FsError, String]
    def thrown(): IO[FsError, String]
    def waitFor(): IO[FsError, String]
  }
  val files: Rpc[Files] = Rpc.derived[Files]("test.files", 2)
  val fileService: Files = new Files {
    def read(path: String): IO[FsError, String] =
      if path == "exists" then ZIO.succeed("contents") else ZIO.fail(FsError.NotFound(path))
    def broken(): IO[FsError, String] = ZIO.dieMessage("secret defect")
    def thrown(): IO[FsError, String] = throw new Exception("secret construction")
    def waitFor(): IO[FsError, String] = ZIO.never
  }
  trait Text {
    def fail(): IO[String, Int]
  }
  given RpcErrorMapper[String] = RpcErrorMapper(error => s"RPC: ${error.code}")
  val text: Rpc[Text] = Rpc.derived[Text]
  trait Nullable {
    def fail(): IO[Option[String], Unit]
  }
  given RpcErrorMapper[Option[String]] = RpcErrorMapper(error => Some(error.code.toString))
  val nullable: Rpc[Nullable] = Rpc.derived[Nullable]

  final case class CodecValue(mode: String)
  object CodecValue {
    given JsonEncoder[CodecValue] = new JsonEncoder[CodecValue] {
      def unsafeEncode(value: CodecValue, indent: Option[Int], out: zio.json.internal.Write): Unit =
        JsonEncoder.string.unsafeEncode(value.mode, indent, out)
      override def toJsonAST(value: CodecValue): Either[String, Json] = value.mode match {
        case "throw" => throw new Exception("encoder secret")
        case "left"  => Left("encoder secret")
        case mode    => Right(Json.Str(mode))
      }
    }
    given JsonDecoder[CodecValue] = JsonDecoder.string.mapOrFail {
      case "throw" => throw new Exception("decoder secret")
      case "left"  => Left("decoder secret")
      case mode    => Right(CodecValue(mode))
    }
    given RpcErrorMapper[CodecValue] = RpcErrorMapper(error => CodecValue(error.code.toString))
  }
  trait Codecs {
    def success(mode: String): IO[CodecValue, CodecValue]
    def failure(mode: String): IO[CodecValue, CodecValue]
    def argument(value: CodecValue): IO[CodecValue, CodecValue]
  }
  val codecs: Rpc[Codecs] = Rpc.derived[Codecs]
  val codecService: Codecs = new Codecs {
    def success(mode: String): IO[CodecValue, CodecValue] = ZIO.succeed(CodecValue(mode))
    def failure(mode: String): IO[CodecValue, CodecValue] = ZIO.fail(CodecValue(mode))
    def argument(value: CodecValue): IO[CodecValue, CodecValue] = ZIO.succeed(value)
  }
  def connection(effect: (String, Int, String, Chunk[Json]) => IO[RpcError, RpcResult]): RpcConnection =
    new RpcConnection {
      def call(service: String, version: Int, method: String, args: Chunk[Json]): IO[RpcError, RpcResult] =
        effect(service, version, method, args)
      def close: UIO[Unit] = ZIO.unit
    }
  def loopback(dispatcher: RpcDispatcher): RpcConnection = connection(dispatcher.dispatch)
}

class RpcTypedErrorSuite extends FunSuite {
  import RpcTypedErrorFixtures.*
  private def run[A](effect: UIO[A]): A = Unsafe.unsafe { _ ?=>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }
  test("IO String and ADT failures retain their exact type through either and catchAll") {
    val client = files.client(loopback(files.bind(fileService)))
    val failure: Either[FsError, String] = run(client.read("missing").either)
    assertEquals(failure, Left(FsError.NotFound("missing")))
    assertEquals(
      run(client.read("missing").catchAll {
        case FsError.NotFound(path)       => ZIO.succeed(path)
        case FsError.Infrastructure(code) => ZIO.succeed(code.toString)
      }),
      "missing"
    )
    assertEquals(run(client.read("exists").either), Right("contents"))
    val textClient = text.client(loopback(text.bind(new Text {
      def fail(): IO[String, Int] = ZIO.fail("domain string")
    })))
    assertEquals(run(textClient.fail().either), Left("domain string"))
  }
  test("null service failure is present, distinct from Unit success and infrastructure error") {
    val dispatcher = nullable.bind(new Nullable {
      def fail(): IO[Option[String], Unit] = ZIO.fail(None)
    })
    val result = run(dispatcher.dispatch(nullable.descriptor.serviceId, 1, "fail", Chunk.empty).either)
    assertEquals(result, Right(RpcResult.Failure(Json.Null)))
    assertEquals(run(nullable.client(loopback(dispatcher)).fail().either), Left(None))
    assertEquals(
      RpcWire.response(RpcWire.success("1", RpcResult.Failure(Json.Null))).map(_.outcome),
      Right(Right(RpcResult.Failure(Json.Null)))
    )
    assertEquals(
      RpcWire.response(RpcWire.success("1", RpcResult.Success(Json.Null))).map(_.outcome),
      Right(Right(RpcResult.Success(Json.Null)))
    )
    List(
      """{"id":"1","failure":null,"result":null}""",
      """{"id":"1","failure":null,"error":"ServiceFailure"}""",
      """{"id":"1","failure":1,"failure":2}""",
      """{"id":"1","failure":{"nested":1,"nested":2}}""",
      """{"id":"1","error":"UnknownCode"}""",
      """{"id":"1","error":null}""",
      """{"id":"1"}"""
    ).foreach(input => assert(RpcWire.response(input).isLeft, input))
    assert(RpcWire.response("""{"id":"1","failure":""" + "[" * 33 + "]" * 33 + "}").isLeft)
  }
  test("typed infrastructure mapping covers every RpcError code and malformed success/domain payloads") {
    RpcError.Code.values.foreach { code =>
      val client = files.client(connection((_, _, _, _) => ZIO.fail(RpcError(code))))
      assertEquals(run(client.read("x").either), Left(FsError.Infrastructure(code)))
    }
    List(RpcResult.Success(Json.Num(1)), RpcResult.Failure(Json.Str("not an FsError"))).foreach { result =>
      val client = files.client(connection((_, _, _, _) => ZIO.succeed(result)))
      assertEquals(run(client.read("x").either), Left(FsError.Infrastructure(RpcError.Code.ProtocolError)))
    }
    val dispatcher = files.bind(fileService)
    val wrongVersion = files.client(connection((id, _, method, args) => dispatcher.dispatch(id, 1, method, args)))
    assertEquals(run(wrongVersion.read("x").either), Left(FsError.Infrastructure(RpcError.Code.VersionMismatch)))
    val missing = files.client(loopback(RpcFixtures.numbers.bind(RpcFixtures.numberService)))
    assertEquals(run(missing.read("x").either), Left(FsError.Infrastructure(RpcError.Code.UnknownService)))
  }
  test("typed defects and construction throws are sanitized; interruption is not a domain failure") {
    val client = files.client(loopback(files.bind(fileService)))
    assertEquals(run(client.broken().either), Left(FsError.Infrastructure(RpcError.Code.ServiceFailure)))
    assertEquals(run(client.thrown().either), Left(FsError.Infrastructure(RpcError.Code.ServiceFailure)))
    assert(run(client.waitFor().fork.flatMap(_.interrupt)).isInterrupted)
    assertEquals(run(client.read("exists").either), Right("contents"))
  }
  test("encoder Left/throws and decoder Left/throws map infrastructure, never valid domain values") {
    val client = codecs.client(loopback(codecs.bind(codecService)))
    List("left", "throw").foreach { mode =>
      assertEquals(run(client.success(mode).either), Left(CodecValue("ProtocolError")))
      assertEquals(run(client.failure(mode).either), Left(CodecValue("ProtocolError")))
      assertEquals(run(client.argument(CodecValue(mode)).either), Left(CodecValue("ProtocolError")))
      assertEquals(
        run(
          codecs.bind(codecService).dispatch(codecs.descriptor.serviceId, 1, "argument", Chunk(Json.Str(mode))).either
        ),
        Left(RpcError(RpcError.Code.InvalidArguments))
      )
      List(RpcResult.Success(Json.Str(mode)), RpcResult.Failure(Json.Str(mode))).foreach { result =>
        val peer = codecs.client(connection((_, _, _, _) => ZIO.succeed(result)))
        assertEquals(run(peer.success("ok").either), Left(CodecValue("ProtocolError")))
      }
    }
  }
  test("Task typed failures, defects and construction throws stay fixed sanitized ServiceFailure") {
    trait Tasks { def call(mode: String): Task[Int] }
    val rpc = Rpc.derived[Tasks]
    val host = new Tasks {
      def call(mode: String): Task[Int] = mode match {
        case "throw"  => throw new IllegalStateException("construction secret")
        case "defect" => ZIO.dieMessage("defect secret")
        case "rpc"    => ZIO.fail(RpcError(RpcError.Code.UnknownMethod))
        case _        => ZIO.fail(new IllegalArgumentException("failure secret"))
      }
    }
    val client = rpc.client(loopback(rpc.bind(host)))
    List("throw", "defect", "rpc", "failure").foreach { mode =>
      val error = run(client.call(mode).either).left.toOption.get
      assertEquals(error, RpcError(RpcError.Code.ServiceFailure))
      assertEquals(error.getMessage, "ServiceFailure")
      assertEquals(error.getCause, null)
    }
    val unexpectedDomain = rpc.client(connection((_, _, _, _) => ZIO.succeed(RpcResult.Failure(Json.Null))))
    assertEquals(run(unexpectedDomain.call("x").either), Left(RpcError(RpcError.Code.ProtocolError)))
  }
  test("mapper throws are programmer defects, not fabricated domain failures") {
    trait BadPolicy { def call(): IO[String, Int] }
    given RpcErrorMapper[String] = RpcErrorMapper(_ => throw new Exception("bad policy"))
    val rpc = Rpc.derived[BadPolicy]
    val client = rpc.client(connection((_, _, _, _) => ZIO.fail(RpcError(RpcError.Code.Disconnected))))
    assert(run(client.call().exit).causeOption.exists(_.defects.nonEmpty))
  }
  test("derivation rejects missing error encoder, decoder, mapper, Nothing and Throwable subtype") {
    val encoder = typeCheckErrors("""
      import zio.json.*
      class Domain
      given JsonDecoder[Domain] = JsonDecoder.string.map(_ => new Domain)
      given fmgp.rpc.RpcErrorMapper[Domain] = fmgp.rpc.RpcErrorMapper(_ => new Domain)
      trait Bad { def call(): zio.IO[Domain, Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val decoder = typeCheckErrors("""
      import zio.json.*
      class Domain
      given JsonEncoder[Domain] = JsonEncoder.string.contramap(_ => "domain")
      given fmgp.rpc.RpcErrorMapper[Domain] = fmgp.rpc.RpcErrorMapper(_ => new Domain)
      trait Bad { def call(): zio.IO[Domain, Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val mapper = typeCheckErrors("""
      trait Bad { def call(): zio.IO[String, Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val nothing = typeCheckErrors("""
      trait Bad { def call(): zio.UIO[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val subtype = typeCheckErrors("""
      trait Bad { def call(): zio.IO[IllegalArgumentException, Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    List(encoder, decoder, subtype).foreach(errors =>
      assert(
        errors.exists(e => e.message.contains("call error") && e.message.contains("JSON encoder and decoder")),
        errors.toString
      )
    )
    assert(encoder.exists(_.message.contains("missing JsonEncoder")), encoder.toString)
    assert(decoder.exists(_.message.contains("missing JsonDecoder")), decoder.toString)
    assert(mapper.exists(_.message.contains("call: requires RpcErrorMapper")), mapper.toString)
    assert(nothing.exists(_.message.contains("call: Nothing")), nothing.toString)
  }
}
