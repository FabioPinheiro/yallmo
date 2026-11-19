package fmgp.rpc

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.{ArrayBuffer, Int8Array}
import zio.*

/** Native complete-message API. It cannot bound allocation before native message delivery or inspect fragmentation. */
private[rpc] trait RpcWebSocketClientPlatform {
  protected def open(
      endpoint: String,
      token: RpcToken,
      limits: RpcWebSocketClient.Limits
  ): ZIO[Scope, RpcError, RpcSocket] =
    for {
      _ <- ZIO
        .attempt {
          val process = js.Dynamic.global.selectDynamic("process")
          if js.isUndefined(process) || process.release.name.asInstanceOf[String] != "node" then
            throw new IllegalStateException("RPC native transport requires Node >=22.4")
          val version = process.versions.node.asInstanceOf[String].split("\\.").take(2).map(_.toInt)
          if version(0) < 22 || (version(0) == 22 && version(1) < 4) ||
            js.typeOf(js.Dynamic.global.selectDynamic("WebSocket")) != "function"
          then throw new IllegalStateException("RPC native transport requires Node >=22.4 with native WebSocket")
        }
        .mapError(_ => RpcError(RpcError.Code.Disconnected))
      bridge <- ZIO.succeed(new NativeEventBridge)
      socket <- ZIO.acquireRelease(
        ZIO
          .attempt {
            val socket =
              new NativeWebSocket(endpoint, js.Array(RpcHandshake.Protocol, "yallmo.ticket." + token.exportSecret))
            socket.binaryType = "arraybuffer"
            socket.onopen = (_: js.Dynamic) => {
              if socket.protocol != RpcHandshake.Protocol then {
                bridge.fail(RpcError(RpcError.Code.ProtocolError)); socket.close()
              } else bridge.opened()
            }
            socket.onmessage = (event: js.Dynamic) => {
              val data = event.data
              if js.typeOf(data) == "string" then {
                val text = data.asInstanceOf[String]
                // UTF-8 byte count is checked before JSON parsing; native allocation has already occurred.
                if text.length > limits.maxFrameBytes || text
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length > limits.maxFrameBytes
                then bridge.fail(RpcError(RpcError.Code.LimitExceeded))
                else bridge.push(RpcSocket.Message.Text(text))
              } else if data.isInstanceOf[ArrayBuffer] then {
                val buffer = data.asInstanceOf[ArrayBuffer]
                if buffer.byteLength > limits.maxFrameBytes then bridge.fail(RpcError(RpcError.Code.LimitExceeded))
                else {
                  val bytes = new Int8Array(buffer)
                  bridge.push(RpcSocket.Message.Binary(Chunk.fromArray(Array.tabulate(bytes.length)(bytes(_)))))
                }
              } else bridge.fail(RpcError(RpcError.Code.ProtocolError))
              if bridge.failed then socket.close()
            }
            socket.onerror = (_: js.Dynamic) => {
              // Node 22.4 can synchronously emit another error when closing a failed upgrade.
              socket.onerror = null
              bridge.fail(RpcError(RpcError.Code.Disconnected)); socket.close()
            }
            socket.onclose = (event: js.Dynamic) => bridge.end(RpcSocket.closeError(event.code.asInstanceOf[Int]))
            socket
          }
          .mapError(_ => RpcError(RpcError.Code.Disconnected))
      )(socket =>
        ZIO.succeed {
          bridge.fail(RpcError(RpcError.Code.Disconnected))
          socket.onopen = null; socket.onmessage = null; socket.onerror = null; socket.onclose = null
          socket.close(); ()
        }
      )
      _ <- bridge.awaitOpen
    } yield new RpcSocket {
      def sendText(text: String): IO[RpcError, Unit] = ZIO
        .attempt {
          val bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
          if socket.readyState != 1 || bridge.failed then throw RpcError(RpcError.Code.Disconnected)
          if bytes > limits.maxFrameBytes || socket.bufferedAmount + bytes > limits.maxFrameBytes.toDouble then {
            bridge.fail(RpcError(RpcError.Code.LimitExceeded)); socket.close()
            throw RpcError(RpcError.Code.LimitExceeded)
          }
          socket.send(text)
        }
        .mapError {
          case error: RpcError => error
          case _               => RpcError(RpcError.Code.Disconnected)
        }
      def receive: IO[RpcError, RpcSocket.Message] = bridge.take
      def close: UIO[Unit] = ZIO.succeed { bridge.fail(RpcError(RpcError.Code.Disconnected)); socket.close(); () }
    }
}

/** Synchronous native callbacks never fork fibers. At most 32 bounded messages and one receiver wait in memory. */
private[rpc] final class NativeEventBridge {
  private val messages = mutable.Queue.empty[RpcSocket.Message]
  private var error: Option[RpcError] = None
  private var receiver: Option[ZIO[Any, RpcError, RpcSocket.Message] => Unit] = None
  private var opening: Option[ZIO[Any, RpcError, Unit] => Unit] = None
  private var isOpen = false
  def failed: Boolean = error.nonEmpty
  def opened(): Unit = {
    isOpen = true
    opening.foreach(_(ZIO.unit)); opening = None
  }
  // Remote termination follows accepted messages. Local aborts and invalid/overflowing input discard them.
  def end(value: RpcError): Unit = if error.isEmpty then {
    error = Some(value)
    receiver.foreach(_(ZIO.fail(value))); receiver = None
    opening.foreach(_(ZIO.fail(value))); opening = None
  }
  def fail(value: RpcError): Unit = {
    messages.clear()
    end(value)
  }
  def push(message: RpcSocket.Message): Unit = if !failed then
    receiver match {
      case Some(callback) => receiver = None; callback(ZIO.succeed(message))
      case None           =>
        if messages.size >= 32 then fail(RpcError(RpcError.Code.LimitExceeded))
        else messages.enqueue(message)
    }
  def awaitOpen: IO[RpcError, Unit] = ZIO.asyncInterrupt { callback =>
    error match {
      case Some(value)    => callback(ZIO.fail(value))
      case None if isOpen => callback(ZIO.unit)
      case None           => opening = Some(callback)
    }
    Left(ZIO.succeed { opening = None })
  }
  def take: IO[RpcError, RpcSocket.Message] = ZIO.asyncInterrupt { callback =>
    if messages.nonEmpty then callback(ZIO.succeed(messages.dequeue()))
    else
      error match {
        case Some(value) => callback(ZIO.fail(value))
        case None        => receiver = Some(callback)
      }
    Left(ZIO.succeed { receiver = None })
  }
}

@scala.annotation.nowarn("msg=unused explicit parameter")
@js.native
@JSGlobal("WebSocket")
private class NativeWebSocket(url: String, protocols: js.Array[String]) extends js.Object {
  var binaryType: String = js.native
  val protocol: String = js.native
  val readyState: Int = js.native
  val bufferedAmount: Double = js.native
  var onopen: js.Function1[js.Dynamic, Unit] = js.native
  var onmessage: js.Function1[js.Dynamic, Unit] = js.native
  var onerror: js.Function1[js.Dynamic, Unit] = js.native
  var onclose: js.Function1[js.Dynamic, Unit] = js.native
  def send(text: String): Unit = js.native
  def close(): Unit = js.native
}
