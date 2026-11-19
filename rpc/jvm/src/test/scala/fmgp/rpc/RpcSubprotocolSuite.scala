package fmgp.rpc

import java.net.Socket
import java.nio.charset.StandardCharsets
import munit.FunSuite
import zio.*
import zio.http.*

/** Gate for the native-client handshake: the credential must never be echoed. */
class RpcSubprotocolSuite extends FunSuite {
  test("zio-http 3.11.6 negotiates only the configured stable protocol from two offered protocols") {
    val effect = ZIO.scoped {
      for {
        app = Handler
          .webSocket(channel => channel.receiveAll(_ => ZIO.unit))
          .withConfig(WebSocketConfig.default.subProtocol(Some("yallmo.rpc.v2")))
        http <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
        port <- Server.install(Routes(Method.GET / "probe" -> handler(app.toResponse))).provideEnvironment(http)
        headers <- ZIO
          .acquireRelease(ZIO.attemptBlocking(new Socket("127.0.0.1", port)))(socket => ZIO.succeed(socket.close()))
          .flatMap { socket =>
            ZIO.attemptBlocking {
              socket.setSoTimeout(3000)
              val request = "GET /probe HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n" +
                "Sec-WebSocket-Protocol: yallmo.rpc.v2, yallmo.ticket.synthetic\r\n\r\n"
              socket.getOutputStream.write(request.getBytes(StandardCharsets.US_ASCII))
              socket.getOutputStream.flush()
              val reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII)
              )
              Iterator.continually(reader.readLine()).takeWhile(line => line != null && line.nonEmpty).toList
            }
          }
      } yield headers
    }
    val headers = Unsafe.unsafe { _ ?=>
      Runtime.default.unsafe.run(effect.timeoutFail(new Exception("deadline"))(15.seconds)).getOrThrowFiberFailure()
    }
    assert(headers.head.contains("101"))
    assertEquals(
      headers.filter(_.toLowerCase.startsWith("sec-websocket-protocol:")).map(_.split(":", 2)(1).trim),
      List("yallmo.rpc.v2")
    )
  }
}
