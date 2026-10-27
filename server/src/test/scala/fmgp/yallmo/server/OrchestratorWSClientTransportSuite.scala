package fmgp.yallmo.server

import fmgp.yallmo.model.AgentId
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.io.{EOFException, InputStream}
import java.net.{InetAddress, ServerSocket, Socket, URI}
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.security.{KeyStore, MessageDigest}
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLHandshakeException}
import java.util.Base64
import munit.FunSuite
import zio.*
import zio.http.{ChannelEvent, ClientSSLConfig, Handler, Status}
import zio.json.*
import zio.json.ast.Json

/** A wire peer is necessary: zio-http Text(String) cannot emit an initial frame ending inside a UTF-8 code point. */
final class OrchestratorWSClientTransportSuite extends FunSuite {
  private val initialization = Initialize.Result(
    Version,
    "wire-peer",
    Identity(AgentId("peer"), Role.Viewer),
    Nil,
    Nil,
    MaxBlobChunkBytes
  )

  test("wire handshake omits Origin and initial-frame UTF-8 splits survive an interleaved payload ping") {
    run(ZIO.scoped {
      for {
        headers <- Promise.make[Nothing, String]
        pong <- Promise.make[Nothing, Frame]
        port <- peer({ wire =>
          for {
            requestHeaders <- wire.handshake()
            _ <- headers.succeed(requestHeaders)
            _ <- initialize(wire)
            request <- wire.request
            output = JsonRpc.Success(request.id, Chunk(AgentId("日🙂\ufffd"))).toJson.getBytes(UTF_8)
            split = output.indexOf(0xe6.toByte) + 1
            _ <- wire.send(1, output.take(split), last = false)
            _ <- wire.send(9, "payload".getBytes(UTF_8))
            frame <- wire.read
            _ <- pong.succeed(frame)
            _ <- wire.send(0, output.drop(split))
            _ <- wire.awaitClose
          } yield ()
        })
        client <- OrchestratorWSClient.connect(endpoint(port))
        result <- client.listAgentIds
        request <- headers.await
        frame <- pong.await
      } yield {
        assert(!request.toLowerCase.contains("\r\norigin:"))
        assertEquals(result, Chunk(AgentId("日🙂\ufffd")))
        assertEquals(frame.opcode, 10)
        assertEquals(new String(frame.bytes, UTF_8), "payload")
      }
    })
  }

  test("a genuine replacement character in a nonfinal initial frame is not rejected") {
    run(ZIO.scoped {
      for {
        port <- peer({ wire =>
          for {
            _ <- wire.handshake() *> initialize(wire)
            request <- wire.request
            output = JsonRpc.Success(request.id, Chunk(AgentId("\ufffd🙂"))).toJson
            split = output.indexOf("🙂") // first surrogate: the preceding replacement character is complete
            _ <- wire.send(1, output.take(split).getBytes(UTF_8), last = false)
            _ <- wire.send(0, output.drop(split).getBytes(UTF_8))
            _ <- wire.awaitClose
          } yield ()
        })
        client <- OrchestratorWSClient.connect(endpoint(port))
        result <- client.listAgentIds
      } yield assertEquals(result, Chunk(AgentId("\ufffd🙂")))
    })
  }

  test("invalid UTF-8 and a single oversized frame fail every pending wait and close the transport") {
    run(ZIO.scoped {
      ZIO.foreachDiscard(List(false, true)) { oversized =>
        for {
          sent <- Promise.make[Nothing, Unit]
          port <- peer({ wire =>
            for {
              _ <- wire.handshake() *> initialize(wire)
              _ <- wire.request *> wire.request
              _ <- wire.send(
                1,
                if oversized then Array.fill[Byte](RpcWire.MaxMessageBytes + 1)(32) else Array[Byte](-1)
              )
              _ <- sent.succeed(())
              _ <- wire.awaitClose
            } yield ()
          })
          client <- OrchestratorWSClient.connect(endpoint(port))
          first <- client.listSessions.either.fork
          second <- client.listAgentIds.either.fork
          _ <- sent.await
          results <- first.join.zip(second.join)
          after <- client.listMessages.either
        } yield {
          assertEquals(
            results,
            (Left(OrchestratorClient.Error.InvalidResponse), Left(OrchestratorClient.Error.InvalidResponse))
          )
          assertEquals(after, Left(OrchestratorClient.Error.Disconnected))
        }
      }
    })
  }

  test("UTF-8 message byte limit is inclusive and cumulative across raw fragments") {
    run(ZIO.scoped {
      ZIO.foreachDiscard(List(0, 1)) { excess =>
        for {
          port <- peer({ wire =>
            for {
              _ <- wire.handshake() *> initialize(wire)
              request <- wire.request
              overhead = JsonRpc.Success(request.id, Chunk(AgentId(""))).toJson.getBytes(UTF_8).length
              budget = RpcWire.MaxMessageBytes - overhead + excess
              label = "é" * (budget / 2) + (if budget % 2 == 1 then "x" else "")
              output = JsonRpc.Success(request.id, Chunk(AgentId(label))).toJson.getBytes(UTF_8)
              _ <- wire.send(1, output.take(500000), last = false)
              _ <- wire.send(0, output.drop(500000))
              _ <- wire.awaitClose
            } yield ()
          })
          client <- OrchestratorWSClient.connect(endpoint(port))
          result <- client.listAgentIds.either
        } yield {
          if excess == 0 then assert(result.isRight)
          else assertEquals(result, Left(OrchestratorClient.Error.InvalidResponse))
        }
      }
    })
  }

  test("a nonfinal binary frame is rejected immediately, without waiting for continuations") {
    run(ZIO.scoped {
      for {
        port <- peer({ wire =>
          wire.handshake() *> initialize(wire) *> wire.request *>
            wire.send(2, Array[Byte](1), last = false) *> wire.awaitClose
        })
        client <- OrchestratorWSClient.connect(endpoint(port))
        result <- client.listAgentIds.either
      } yield assertEquals(result, Left(OrchestratorClient.Error.InvalidResponse))
    })
  }

  test("a peer close midway through a fragmented reply fails all waits") {
    run(ZIO.scoped {
      for {
        port <- peer({ wire =>
          wire.handshake() *> initialize(wire) *> wire.request *> wire.request *>
            wire.send(1, "{\"jsonrpc\":".getBytes(UTF_8), last = false) *>
            wire.send(8, Array[Byte](3, -24)) *> wire.awaitClose
        })
        client <- OrchestratorWSClient.connect(endpoint(port))
        result <- client.listAgentIds.either.zipPar(client.listSessions.either)
      } yield assertEquals(
        result,
        (Left(OrchestratorClient.Error.Disconnected), Left(OrchestratorClient.Error.Disconnected))
      )
    })
  }

  test("scope exit fails an already-pending wait and closes the peer socket") {
    run(ZIO.scoped {
      for {
        requested <- Promise.make[Nothing, Unit]
        closed <- Promise.make[Nothing, Unit]
        port <- peer({ wire =>
          wire.handshake() *> initialize(wire) *> wire.request *> requested.succeed(()) *>
            wire.awaitClose *> closed.succeed(()).unit
        })
        owner <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        client <- OrchestratorWSClient.connect(endpoint(port)).provideEnvironment(ZEnvironment(owner))
        wait <- client.listAgentIds.either.fork
        _ <- requested.await
        _ <- owner.close(Exit.unit)
        result <- wait.join
        _ <- closed.await
        _ <- client.close *> client.close
      } yield assertEquals(result, Left(OrchestratorClient.Error.Disconnected))
    })
  }

  test("HTTP rejection and invalid upgrade responses release their connection before the caller scope exits") {
    run(ZIO.scoped {
      ZIO.foreachDiscard(List(false, true)) { invalidUpgrade =>
        for {
          closed <- Promise.make[Nothing, Unit]
          port <- peer({ wire =>
            for {
              _ <- wire.headers
              response =
                if invalidUpgrade then
                  "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: wrong\r\n\r\n"
                else "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
              _ <- wire.write(response.getBytes(US_ASCII))
              _ <- wire.awaitClose *> closed.succeed(())
            } yield ()
          })
          result <- OrchestratorWSClient.connect(endpoint(port), 2.seconds).either
          _ <- closed.await
        } yield assert(result.isLeft)
      }
    })
  }

  test("handshake and initialization timeouts close their sockets without waiting for the caller scope") {
    run(ZIO.scoped {
      ZIO.foreachDiscard(List(false, true)) { initializeStalls =>
        for {
          closed <- Promise.make[Nothing, Unit]
          port <- peer({ wire =>
            (if initializeStalls then wire.handshake() *> wire.request else wire.headers) *>
              wire.awaitClose *> closed.succeed(()).unit
          })
          result <- OrchestratorWSClient.connect(endpoint(port), 400.millis).either
          _ <- closed.await
        } yield assertEquals(result, Left(OrchestratorClient.Error.RequestTimedOut))
      }
    })
  }

  test("interrupting connection setup closes sockets during handshake and initialization") {
    run(ZIO.scoped {
      ZIO.foreachDiscard(List(false, true)) { initializing =>
        for {
          reached <- Promise.make[Nothing, Unit]
          closed <- Promise.make[Nothing, Unit]
          port <- peer({ wire =>
            (if initializing then wire.handshake() *> wire.request else wire.headers) *>
              reached.succeed(()) *> wire.awaitClose *> closed.succeed(()).unit
          })
          connection <- OrchestratorWSClient.connect(endpoint(port)).fork
          _ <- reached.await
          result <- connection.interrupt
          _ <- closed.await
        } yield assert(result.isInterrupted)
      }
    })
  }

  test("a configured setup deadline above ten seconds is not shortened by Netty's handshake default") {
    run(ZIO.scoped {
      for {
        port <- peer(wire => wire.handshake(10500.millis) *> initialize(wire) *> wire.awaitClose)
        client <- OrchestratorWSClient.connect(endpoint(port), 13.seconds)
        capabilities <- client.capabilities
      } yield assertEquals(capabilities, Some(initialization))
    })
  }

  test("wss rejects an untrusted certificate with JVM-default trust") {
    run(ZIO.scoped {
      for {
        contexts <- tlsContexts
        port <- peer(wire => wire.handshake().unit.catchAll(_ => ZIO.unit), Some(contexts._1))
        result <- OrchestratorWSClient.connect(s"wss://localhost:$port/rpc", 3.seconds).either
      } yield {
        val cause = result.left.toOption.collect { case OrchestratorClient.Error.Transport(error) => error }
        assert(cause.exists(hasHandshakeFailure), result.toString)
      }
    })
  }

  test("wss accepts a trusted matching host but rejects a trusted certificate for the wrong host") {
    run(ZIO.scoped {
      for {
        contexts <- tlsContexts
        _ <- ZIO.foreachDiscard(List("localhost", "127.0.0.1")) { host =>
          ZIO.scoped {
            for {
              port <- peer(wire => (wire.handshake() *> wire.awaitClose).catchAll(_ => ZIO.unit), Some(contexts._1))
              http <- OrchestratorWSClientTransport.withTLS(contexts._2).build.map(_.get[zio.http.Client])
              ready <- Promise.make[Throwable, Unit]
              socket = Handler.webSocket { channel =>
                channel
                  .receiveAll {
                    case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                      ready.succeed(()).unit
                    case ChannelEvent.ExceptionCaught(error) => ready.fail(error).unit
                    case _                                   => ZIO.unit
                  }
                  .ensuring(channel.shutdown)
              }
              result <- (http
                .uri(URI.create(s"wss://$host:$port/rpc"))
                .socket(socket)
                .flatMap { response =>
                  ready.await.as(response.status)
                })
                .timeoutFail(new AssertionError("TLS test timed out"))(3.seconds)
                .either
            } yield {
              if host == "localhost" then assertEquals(result, Right(Status.SwitchingProtocols))
              else assert(result.left.toOption.exists(hasHandshakeFailure), result.toString)
            }
          }
        }
      } yield ()
    })
  }

  test("TLS follows the configured setup deadline rather than Netty's independent ten-second timeout") {
    run(ZIO.scoped {
      for {
        contexts <- tlsContexts
        port <- peer(
          wire => ZIO.sleep(10500.millis) *> wire.handshake() *> wire.awaitClose,
          Some(contexts._1)
        )
        http <- OrchestratorWSClientTransport.withTLS(contexts._2).build.map(_.get[zio.http.Client])
        socket = Handler
          .webSocket(channel => channel.awaitShutdown.ensuring(channel.shutdown))
          .withConfig(RpcWire.webSocketConfig.handshakeTimeout(13.seconds))
        response <- http
          .uri(URI.create(s"wss://localhost:$port/rpc"))
          .socket(socket)
          .timeoutFail(new AssertionError("TLS setup timed out"))(13.seconds)
      } yield assertEquals(response.status, Status.SwitchingProtocols)
    })
  }

  private def hasHandshakeFailure(error: Throwable): Boolean =
    error.isInstanceOf[SSLHandshakeException] || Option(error.getCause).exists(hasHandshakeFailure)

  private def tlsContexts: Task[(SSLContext, ClientSSLConfig)] = ZIO.attempt {
    // Deliberately public test-only key, self-signed for DNS localhost (not the IP address).
    val store = KeyStore.getInstance("PKCS12")
    val input = getClass.getResourceAsStream("/websocket/localhost.p12")
    try store.load(input, "changeit".toCharArray)
    finally input.close()
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keys.init(store, "changeit".toCharArray)
    val server = SSLContext.getInstance("TLS")
    server.init(keys.getKeyManagers, null, null)
    val client = ClientSSLConfig
      .FromJavaxNetSsl()
      .trustManagerKeyStoreType("PKCS12")
      .trustManagerResource("websocket/localhost.p12")
      .trustManagerPassword("changeit")
      .build()
    (server, client)
  }

  private final case class Frame(opcode: Int, bytes: Array[Byte])

  private final class Wire(socket: Socket) {
    private def blocking[A](body: => A): Task[A] =
      ZIO.attemptBlockingCancelable(body)(ZIO.attempt(socket.close()).ignore)

    private val input = socket.getInputStream
    private val output = socket.getOutputStream

    def headers: Task[String] = blocking {
      val bytes = new StringBuilder
      while !bytes.endsWith("\r\n\r\n") && bytes.length < 8192 do bytes.append(byte(input).toChar)
      bytes.toString
    }

    def handshake(delay: Duration = Duration.Zero): Task[String] = for {
      request <- headers
      _ <- ZIO.sleep(delay)
      key = request.split("\r\n").find(_.toLowerCase.startsWith("sec-websocket-key:")).get.split(":", 2)(1).trim
      accept = Base64.getEncoder.encodeToString(
        MessageDigest
          .getInstance("SHA-1")
          .digest(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(US_ASCII)
          )
      )
      _ <- write(
        s"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
          .getBytes(US_ASCII)
      )
    } yield request

    def write(bytes: Array[Byte]): Task[Unit] = blocking { output.write(bytes); output.flush() }

    def send(opcode: Int, bytes: Array[Byte], last: Boolean = true): Task[Unit] = blocking {
      output.write(opcode | (if last then 128 else 0))
      if bytes.length < 126 then output.write(bytes.length)
      else if bytes.length <= 65535 then {
        output.write(126); output.write(bytes.length >>> 8); output.write(bytes.length)
      } else {
        output.write(127)
        (7 to 0 by -1).foreach(shift => output.write((bytes.length.toLong >>> (shift * 8)).toInt))
      }
      output.write(bytes)
      output.flush()
    }

    def read: Task[Frame] = blocking {
      val opcode = byte(input) & 15
      val second = byte(input)
      val length = (second & 127) match {
        case 126 => (byte(input) << 8) | byte(input)
        case 127 => (0 until 8).foldLeft(0L)((n, _) => (n << 8) | byte(input)).toInt
        case n   => n
      }
      require(length >= 0 && length <= RpcWire.MaxMessageBytes)
      val mask = if (second & 128) != 0 then input.readNBytes(4) else Array.emptyByteArray
      val bytes = input.readNBytes(length)
      if bytes.length != length then throw new EOFException
      if mask.nonEmpty then bytes.indices.foreach(i => bytes(i) = (bytes(i) ^ mask(i % 4)).toByte)
      Frame(opcode, bytes)
    }

    def request: Task[JsonRpc.Request[Json.Obj]] =
      read.flatMap(frame =>
        ZIO.fromEither(new String(frame.bytes, UTF_8).fromJson[JsonRpc.Request[Json.Obj]]).mapError(new Exception(_))
      )

    def awaitClose: Task[Unit] = read.flatMap(frame => if frame.opcode == 8 then ZIO.unit else awaitClose).catchSome {
      case _: EOFException => ZIO.unit
    }
  }

  private def byte(input: InputStream): Int = {
    val value = input.read()
    if value < 0 then throw new EOFException
    value
  }

  private def initialize(wire: Wire): Task[Unit] =
    wire.request.flatMap { request =>
      ZIO.succeed(assertEquals(request.method, Initialize.method)) *>
        wire.send(1, JsonRpc.Success(request.id, initialization).toJson.getBytes(UTF_8))
    }

  private def peer(serve: Wire => Task[Unit], tls: Option[SSLContext] = None): ZIO[Scope, Throwable, Int] =
    for {
      server <- ZIO.acquireRelease(
        ZIO.attempt(
          tls.fold(new ServerSocket(0, 1, InetAddress.getLoopbackAddress))(
            _.getServerSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress)
          )
        )
      )(s => ZIO.attempt(s.close()).ignore)
      _ <- ZIO.scoped {
        for {
          socket <- ZIO.acquireRelease(
            ZIO.attemptBlockingCancelable(server.accept())(ZIO.attempt(server.close()).ignore)
          )(s => ZIO.attempt(s.close()).ignore)
          _ <- ZIO.attempt(socket.setSoTimeout(5000))
          _ <- serve(new Wire(socket))
        } yield ()
      }.forkScoped
    } yield server.getLocalPort

  private def endpoint(port: Int): String = s"ws://127.0.0.1:$port/rpc"

  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new AssertionError("Wire test timed out"))(15.seconds))
      .getOrThrowFiberFailure()
  }
}
