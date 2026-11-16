package fmgp.rpc

import munit.ZSuite
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

class RpcTypedErrorSuite extends ZSuite {
  import RpcTypedErrorFixtures.*
  testZ("IO String and ADT failures retain their exact type through either and catchAll") {
    ZIO.suspendSucceed {
      val client = files.client(loopback(files.bind(fileService)))
      val textClient = text.client(loopback(text.bind(new Text {
        def fail(): IO[String, Int] = ZIO.fail("domain string")
      })))
      for {
        failure: Either[FsError, String] <- client.read("missing").either
        recovered <- client.read("missing").catchAll {
          case FsError.NotFound(path)       => ZIO.succeed(path)
          case FsError.Infrastructure(code) => ZIO.succeed(code.toString)
        }
        success <- client.read("exists").either
        textFailure <- textClient.fail().either
      } yield {
        assertEquals(failure, Left(FsError.NotFound("missing")))
        assertEquals(recovered, "missing")
        assertEquals(success, Right("contents"))
        assertEquals(textFailure, Left("domain string"))
      }
    }
  }
  testZ("null service failure is present, distinct from Unit success and infrastructure error") {
    ZIO.suspendSucceed {
      val dispatcher = nullable.bind(new Nullable {
        def fail(): IO[Option[String], Unit] = ZIO.fail(None)
      })
      for {
        result <- dispatcher.dispatch(nullable.descriptor.serviceId, 1, "fail", Chunk.empty).either
        failure <- nullable.client(loopback(dispatcher)).fail().either
      } yield {
        assertEquals(result, Right(RpcResult.Failure(Json.Null)))
        assertEquals(failure, Left(None))
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
    }
  }
  testZ("typed infrastructure mapping covers every RpcError code and malformed success/domain payloads") {
    ZIO.suspendSucceed {
      val dispatcher = files.bind(fileService)
      val wrongVersion = files.client(connection((id, _, method, args) => dispatcher.dispatch(id, 1, method, args)))
      val missing = files.client(loopback(RpcFixtures.numbers.bind(RpcFixtures.numberService)))
      for {
        _ <- ZIO.foreachDiscard(RpcError.Code.values) { code =>
          val client = files.client(connection((_, _, _, _) => ZIO.fail(RpcError(code))))
          client.read("x").either.map(result => assertEquals(result, Left(FsError.Infrastructure(code))))
        }
        _ <- ZIO.foreachDiscard(List(RpcResult.Success(Json.Num(1)), RpcResult.Failure(Json.Str("not an FsError")))) {
          result =>
            val client = files.client(connection((_, _, _, _) => ZIO.succeed(result)))
            client
              .read("x")
              .either
              .map(error => assertEquals(error, Left(FsError.Infrastructure(RpcError.Code.ProtocolError))))
        }
        versionFailure <- wrongVersion.read("x").either
        missingFailure <- missing.read("x").either
      } yield {
        assertEquals(versionFailure, Left(FsError.Infrastructure(RpcError.Code.VersionMismatch)))
        assertEquals(missingFailure, Left(FsError.Infrastructure(RpcError.Code.UnknownService)))
      }
    }
  }
  testZ("typed defects and construction throws are sanitized; interruption is not a domain failure") {
    ZIO.suspendSucceed {
      val client = files.client(loopback(files.bind(fileService)))
      for {
        broken <- client.broken().either
        thrown <- client.thrown().either
        interrupted <- client.waitFor().fork.flatMap(_.interrupt)
        success <- client.read("exists").either
      } yield {
        assertEquals(broken, Left(FsError.Infrastructure(RpcError.Code.ServiceFailure)))
        assertEquals(thrown, Left(FsError.Infrastructure(RpcError.Code.ServiceFailure)))
        assert(interrupted.isInterrupted)
        assertEquals(success, Right("contents"))
      }
    }
  }
  testZ("encoder Left/throws and decoder Left/throws map infrastructure, never valid domain values") {
    ZIO.suspendSucceed {
      val client = codecs.client(loopback(codecs.bind(codecService)))
      ZIO.foreachDiscard(List("left", "throw")) { mode =>
        for {
          success <- client.success(mode).either
          failure <- client.failure(mode).either
          argument <- client.argument(CodecValue(mode)).either
          dispatched <- codecs
            .bind(codecService)
            .dispatch(codecs.descriptor.serviceId, 1, "argument", Chunk(Json.Str(mode)))
            .either
          _ <- ZIO.succeed {
            assertEquals(success, Left(CodecValue("ProtocolError")))
            assertEquals(failure, Left(CodecValue("ProtocolError")))
            assertEquals(argument, Left(CodecValue("ProtocolError")))
            assertEquals(dispatched, Left(RpcError(RpcError.Code.InvalidArguments)))
          }
          _ <- ZIO.foreachDiscard(List(RpcResult.Success(Json.Str(mode)), RpcResult.Failure(Json.Str(mode)))) {
            result =>
              val peer = codecs.client(connection((_, _, _, _) => ZIO.succeed(result)))
              peer.success("ok").either.map(error => assertEquals(error, Left(CodecValue("ProtocolError"))))
          }
        } yield ()
      }
    }
  }
  testZ("Task typed failures, defects and construction throws stay fixed sanitized ServiceFailure") {
    ZIO.suspendSucceed {
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
      val unexpectedDomain = rpc.client(connection((_, _, _, _) => ZIO.succeed(RpcResult.Failure(Json.Null))))
      for {
        _ <- ZIO.foreachDiscard(List("throw", "defect", "rpc", "failure")) { mode =>
          client.call(mode).either.map { result =>
            val error = result.left.toOption.get
            assertEquals(error, RpcError(RpcError.Code.ServiceFailure))
            assertEquals(error.getMessage, "ServiceFailure")
            assertEquals(error.getCause, null)
          }
        }
        unexpected <- unexpectedDomain.call("x").either
      } yield assertEquals(unexpected, Left(RpcError(RpcError.Code.ProtocolError)))
    }
  }
  testZ("mapper throws are programmer defects, not fabricated domain failures") {
    ZIO.suspendSucceed {
      trait BadPolicy { def call(): IO[String, Int] }
      given RpcErrorMapper[String] = RpcErrorMapper(_ => throw new Exception("bad policy"))
      val rpc = Rpc.derived[BadPolicy]
      val client = rpc.client(connection((_, _, _, _) => ZIO.fail(RpcError(RpcError.Code.Disconnected))))
      client.call().exit.map(result => assert(result.causeOption.exists(_.defects.nonEmpty)))
    }
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
