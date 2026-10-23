package fmgp.yallmo.server

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import zio.*
import zio.http.WebSocketFrame

final class WebSocketSupportSuite extends FunSuite {
  test("continuation bytes are decoded together even when UTF-8 is split across continuations") {
    run {
      for {
        text <- WebSocketText.make
        start <- text.accept(WebSocketFrame.Text("prefix ", false))
        bytes = Chunk.fromArray("🙂".getBytes(UTF_8))
        middle <- text.accept(WebSocketFrame.Continuation(bytes.take(2), false))
        end <- text.accept(WebSocketFrame.Continuation(bytes.drop(2), true))
        next <- text.accept(WebSocketFrame.Text("next"))
      } yield {
        assertEquals(start, None)
        assertEquals(middle, None)
        assertEquals(end, Some("prefix 🙂"))
        assertEquals(next, Some("next"))
      }
    }
  }

  test("total message limits cover all fragments, not just each frame") {
    run {
      for {
        text <- WebSocketText.make
        _ <- text.accept(WebSocketFrame.Text("x" * (RpcWire.MaxMessageBytes - 1), false))
        exceeded <- text.accept(WebSocketFrame.Continuation(Chunk[Byte](1, 2), true)).either
      } yield assertEquals(exceeded.left.toOption.map(_.code), Some(1009))
    }
  }

  test("unexpected continuation/binary/new text frames and invalid UTF-8 are rejected") {
    run {
      for {
        text <- WebSocketText.make
        unexpected <- text.accept(WebSocketFrame.Continuation(Chunk[Byte](1))).either
        binary <- text.accept(WebSocketFrame.Binary(Chunk[Byte](1))).either
        _ <- text.accept(WebSocketFrame.Text("prefix", false))
        newText <- text.accept(WebSocketFrame.Text("another message")).either
        invalid <- text.accept(WebSocketFrame.Continuation(Chunk[Byte](-1))).either
      } yield {
        assertEquals(unexpected.left.toOption.map(_.code), Some(1002))
        assertEquals(binary.left.toOption.map(_.code), Some(1003))
        assertEquals(newText.left.toOption.map(_.code), Some(1002))
        assertEquals(invalid.left.toOption.map(_.code), Some(1007))
      }
    }
  }

  test("initial-fragment replacement characters are rejected instead of silently losing UTF-8 bytes") {
    run {
      for {
        text <- WebSocketText.make
        partial <- text.accept(WebSocketFrame.Text("\ufffd", false)).either
        complete <- text.accept(WebSocketFrame.Text("\ufffd", true))
      } yield {
        assertEquals(partial.left.toOption.map(_.code), Some(1007))
        assertEquals(complete, Some("\ufffd"))
      }
    }
  }

  private def run[A](effect: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
