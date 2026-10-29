import fmgp.rpc.*
import zio.*
import zio.http.Server
import zio.json.JsonCodec

case class AAA(n1: Int, n2: Int) derives JsonCodec
// Shared contract, available to both server and client.
trait Calculator {
  def add(left: Int, right: Int): Task[Int]
  def minus(left: Int, right: Int): Task[Int]
  def addAAA(aaa: AAA): Task[Int]
}

object Calculator {
  val rpc: Rpc[Calculator] =
    Rpc.derived[Calculator]("demo.calculator", 1)
}

object CalculatorLive extends Calculator {
  def add(left: Int, right: Int): Task[Int] =
    ZIO.succeed(left + right)

  def minus(left: Int, right: Int): Task[Int] =
    ZIO.succeed(left - right)

  def addAAA(aaa: AAA): Task[Int] =
    ZIO.succeed(aaa.n1 + aaa.n2)
}

/** CLI logging configuration shared by the three demo applications. Defaults to INFO. */
object DemoLogging {
  private val usage = "Usage: [--log-level trace|debug|info|warning|error|fatal|off]"

  private def parse(args: Chunk[String]): Either[IllegalArgumentException, LogLevel] = {
    val name = args.toList match {
      case Nil                                               => Right("info")
      case List("--log-level", value)                        => Right(value)
      case List(option) if option.startsWith("--log-level=") => Right(option.stripPrefix("--log-level="))
      case _                                                 => Left(new IllegalArgumentException(usage))
    }
    name.flatMap { value =>
      value.toLowerCase(java.util.Locale.ROOT) match {
        case "trace"            => Right(LogLevel.Trace)
        case "debug"            => Right(LogLevel.Debug)
        case "info"             => Right(LogLevel.Info)
        case "warning" | "warn" => Right(LogLevel.Warning)
        case "error"            => Right(LogLevel.Error)
        case "fatal"            => Right(LogLevel.Fatal)
        case "off"              => Right(LogLevel.None)
        case _                  => Left(new IllegalArgumentException(usage))
      }
    }
  }

  def configure: ZIO[Scope & ZIOAppArgs, IllegalArgumentException, Unit] =
    for {
      args <- ZIOAppArgs.getArgs
      minimum <- ZIO.fromEither(parse(args))
      _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(
        ZLogger.default
          .map(line => scala.Console.println(line))
          .filterLogLevel(level => minimum != LogLevel.None && level >= minimum)
      )).build
    } yield ()
}

// Both ends in one JVM, using a real WebSocket.
object Demo extends ZIOAppDefault {
  def run = ZIO.scoped {
    for {
      _ <- DemoLogging.configure
      rpc <- RpcWebSocket.route()
      http <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
      port <- Server.install(rpc.routes).provideEnvironment(http)
      token <- rpc.register(Calculator.rpc.bind(CalculatorLive))
      connection <- RpcWebSocket.connect(port, token)
      client = Calculator.rpc.client(connection)
      result <- client.add(20, 22)
      _ <- Console.printLine(s"Result1: $result")
      result2 <- client.minus(20, 1)
      _ <- Console.printLine(s"Result2: $result2")
    } yield ()
  }
}

// Keep this JVM alive until Ctrl+C; scope exit releases the listener.
// sbt 'rpc/Test/runMain DemoServer --log-level trace'
object DemoServer extends ZIOAppDefault {
  def run = ZIO.scoped {
    for {
      _ <- DemoLogging.configure
      rpc <- RpcWebSocket.route()
      http <- Server.defaultWith(_.binding("127.0.0.1", 9000).gracefulShutdownTimeout(1.second)).build
      port <- Server.install(rpc.routes).provideEnvironment(http)
      token <- rpc.register(Calculator.rpc.bind(CalculatorLive))
      path <- System.env("RPC_TICKET_FILE").someOrFail(new Exception("Set RPC_TICKET_FILE to a private bootstrap file"))
      _ <- ZIO.attempt {
        val file = java.nio.file.Files.createFile(
          java.nio.file.Path.of(path),
          java.nio.file.attribute.PosixFilePermissions
            .asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        )
        java.nio.file.Files.writeString(file, token.exportSecret)
      }
      _ <- Console.printLine(
        s"Calculator server listening at ws://127.0.0.1:$port/rpc; ticket written to private file (Ctrl+C to stop)"
      )
      _ <- ZIO.never
    } yield ()
  }
}

// Run in another JVM while DemoServer is listening.
// sbt 'rpc/Test/runMain DemoClient  --log-level trace''
object DemoClient extends ZIOAppDefault {
  def run = ZIO.scoped {
    for {
      _ <- DemoLogging.configure
      path <- System
        .env("RPC_TICKET_FILE")
        .someOrFail(new Exception("Set RPC_TICKET_FILE to the private bootstrap file"))
      secret <- ZIO
        .attempt(java.nio.file.Files.readString(java.nio.file.Path.of(path)))
        .mapError(_ => new Exception("Cannot read bootstrap file"))
      token <- ZIO.fromEither(RpcToken.parse(secret))
      connection <- RpcWebSocket.connect("ws://127.0.0.1:9000/rpc", token)
      client = Calculator.rpc.client(connection)
      result <- client.add(20, 22)
      _ <- Console.printLine(s"Result: $result")
      result2 <- client.minus(20, 1)
      _ <- Console.printLine(s"Result2: $result2")
    } yield ()
  }
}
