package fmgp.yallmo.repl.browser

import fmgp.yallmo.repl.voice.VoiceRecordingTermination
import munit.FunSuite
import zio.*

import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.atomic.AtomicLong

final class BrowserAudioSessionSuite extends FunSuite {
  import BrowserProtocol.*
  import BrowserAudioTestSupport.*

  test("disconnected recorder and speech fail locally; durations and text are bounded") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        recording <- session.recorder.record(1).either
        speech <- session.speak("hello").either
        duration <- session.recorder.record(31).either
        text <- session.speak("x" * 16001).either
      } yield {
        assertEquals(code(recording), "not_connected")
        assertEquals(code(speech), "not_connected")
        assertEquals(code(duration), "duration")
        assertEquals(code(text), "invalid_text")
      }
    })
  }

  test("single client, actual start readiness, stop, WAV and duplicate upload boundaries") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        conflict <- session.connect(OtherClient).either
        stop <- Promise.make[Nothing, VoiceRecordingTermination]
        recording <- session.recorder.recordUntil(stop.await).fork
        op <- operation(session)
        queued <- session.status
        wrong <- session.checkUpload(OtherClient, op.id).either
        premature <- session.checkUpload(Client, op.id).either
        _ <- session.event(Client, Event(op.id, "started"))
        ready <- session.status
        duplicateStart <- session.event(Client, Event(op.id, "started")).either
        _ <- stop.succeed(VoiceRecordingTermination.Stop)
        _ <- until(session.poll(Client))(_.operation.exists(_.phase == "stop"))
        _ <- session.upload(Client, op.id, wav(320))
        result <- recording.join
        duplicate <- session.upload(Client, op.id, wav(320)).either
        idle <- session.poll(Client)
      } yield {
        assertEquals(code(conflict), "client_conflict")
        assert(!queued.recordingStarted)
        assertEquals(code(wrong), "wrong_client")
        assertEquals(code(premature), "stale_operation")
        assert(ready.recordingStarted)
        assertEquals(code(duplicateStart), "stale_operation")
        assertEquals(result.map(_.bytes), Some(wav(320)))
        assertEquals(code(duplicate), "stale_operation")
        assert(idle.operation.isEmpty)
      }
    })
  }

  test("cancel before readiness removes start and late bytes cannot reach a subsequent recording") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        cancel <- Promise.make[Nothing, VoiceRecordingTermination]
        first <- session.recorder.recordUntil(cancel.await).fork
        old <- operation(session)
        _ <- cancel.succeed(VoiceRecordingTermination.Cancel)
        cancelled <- first.join
        idle <- session.poll(Client)
        second <- session.recorder.record(1).fork
        next <- operation(session)
        lateStart <- session.event(Client, Event(old.id, "started")).either
        lateBytes <- session.upload(Client, old.id, wav(32)).either
        _ <- session.event(Client, Event(next.id, "started"))
        _ <- session.upload(Client, next.id, wav(32))
        audio <- second.join
      } yield {
        assertEquals(cancelled, None)
        assert(idle.operation.isEmpty)
        assertNotEquals(old.id, next.id)
        assertEquals(code(lateStart), "stale_operation")
        assertEquals(code(lateBytes), "stale_operation")
        assertEquals(audio.bytes, wav(32))
      }
    })
  }

  test("cancel after actual capture readiness discards bytes and releases the operation slot") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        cancel <- Promise.make[Nothing, VoiceRecordingTermination]
        recording <- session.recorder.recordUntil(cancel.await).fork
        op <- operation(session)
        _ <- session.event(Client, Event(op.id, "started"))
        busy <- session.speak("must not replace capture").either
        _ <- cancel.succeed(VoiceRecordingTermination.Cancel)
        result <- recording.join
        late <- session.upload(Client, op.id, wav(32)).either
        status <- session.status
        idle <- session.poll(Client)
      } yield {
        assertEquals(code(busy), "busy")
        assertEquals(result, None)
        assertEquals(code(late), "stale_operation")
        assert(!status.recordingStarted)
        assert(idle.operation.isEmpty)
      }
    })
  }

  test("explicit reconnect never replays an old recording start, even within the heartbeat window") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        recording <- session.recorder.record(1).either.fork
        old <- operation(session)
        _ <- session.connect(Client)
        result <- recording.join
        idle <- session.poll(Client)
        late <- session.event(Client, Event(old.id, "started")).either
      } yield {
        assertEquals(code(result), "disconnected")
        assert(idle.operation.isEmpty)
        assertEquals(code(late), "stale_operation")
      }
    })
  }

  test("stop before started fails rather than pretending queued capture has started") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        stop <- Promise.make[Nothing, VoiceRecordingTermination]
        fiber <- session.recorder.recordUntil(stop.await).either.fork
        op <- operation(session)
        _ <- stop.succeed(VoiceRecordingTermination.Stop)
        result <- fiber.join
        late <- session.event(Client, Event(op.id, "started")).either
        poll <- session.poll(Client)
      } yield {
        assertEquals(code(result), "not_started")
        assertEquals(code(late), "stale_operation")
        assert(poll.operation.isEmpty)
      }
    })
  }

  test("interruption, explicit disconnect and browser failure resolve waits and discard active state") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        interrupted <- session.recorder.record(1).fork
        _ <- operation(session)
        _ <- interrupted.interrupt
        idle <- session.poll(Client)
        failed <- session.recorder.record(1).either.fork
        failOp <- operation(session)
        _ <- session.event(Client, Event(failOp.id, "error"))
        failureResult <- failed.join
        disconnected <- session.recorder.record(1).either.fork
        _ <- operation(session)
        _ <- session.disconnect(Client)
        disconnectedResult <- disconnected.join
        status <- session.status
      } yield {
        assert(idle.operation.isEmpty)
        assertEquals(code(failureResult), "browser_error")
        assertEquals(code(disconnectedResult), "disconnected")
        assert(!status.connected)
      }
    })
  }

  test("invalid WAV fails pending recording and clears it; valid maximum audio fits the limit") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        fiber <- session.recorder.record(1).either.fork
        op <- operation(session)
        _ <- session.event(Client, Event(op.id, "started"))
        rejected <- session.upload(Client, op.id, Chunk.fromArray("not a WAV".getBytes)).either
        result <- fiber.join
        idle <- session.poll(Client)
      } yield {
        assertEquals(code(rejected), "invalid_wav")
        assertEquals(code(result), "invalid_wav")
        assert(idle.operation.isEmpty)
        assert(WavAudio.validate(wav(960000), 30).isRight)
        assert(WavAudio.validate(wav(32002), 1).isLeft)
        assert(WavAudio.validate(wav(0), 30).isLeft)
        assert(WavAudio.validate(wav(960002), 30).isLeft)
        assert(WavAudio.validate(Chunk.fill(MaxUploadBytes + 1)(0.toByte), 30).isLeft)
        val canonical = wav(320).toArray
        Seq(0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40).foreach { offset =>
          val malformed = canonical.clone()
          malformed(offset) = (malformed(offset) ^ 1).toByte
          assert(WavAudio.validate(Chunk.fromArray(malformed), 30).isLeft, s"offset $offset")
        }
      }
    })
  }

  test("speech requires actual started and ended events; replacement and interruption remove old text") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        first <- session.speak("first").either.fork
        old <- operation(session)
        premature <- session.event(Client, Event(old.id, "ended")).either
        second <- session.speak("second").fork
        next <- until(session.poll(Client))(_.operation.exists(_.id != old.id)).map(_.operation.get)
        replaced <- first.join
        _ <- session.event(Client, Event(next.id, "started"))
        _ <- session.event(Client, Event(next.id, "ended"))
        _ <- second.join
        third <- session.speak("third").fork
        _ <- operation(session)
        _ <- third.interrupt
        idle <- session.poll(Client)
      } yield {
        assertEquals(code(premature), "stale_operation")
        assertEquals(code(replaced), "replaced")
        assertEquals(next.text, Some("second"))
        assert(idle.operation.isEmpty)
      }
    })
  }

  test("heartbeat loss fails pending waits and allows a fresh page without replaying an old start") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        fiber <- session.recorder.record(1).either.fork
        old <- operation(session)
        _ <- ZIO.sleep(5250.millis)
        result <- fiber.join
        _ <- session.connect(OtherClient)
        idle <- session.poll(OtherClient)
        stale <- session.event(OtherClient, Event(old.id, "started")).either
      } yield {
        assertEquals(code(result), "disconnected")
        assert(idle.operation.isEmpty)
        assertEquals(code(stale), "stale_operation")
      }
    })
  }

  test("start deadline expires even while heartbeats are healthy") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        fiber <- session.recorder.record(1).either.fork
        _ <- operation(session)
        _ <- (session.poll(Client) *> ZIO.sleep(500.millis)).repeatN(10)
        result <- fiber.join
        poll <- session.poll(Client)
      } yield {
        assertEquals(code(result), "start_timeout")
        assert(poll.operation.isEmpty)
      }
    })
  }

  test("recording duration sends stop, then upload deadline fails instead of waiting forever") {
    run(ZIO.scoped {
      for {
        session <- BrowserAudioSession.make
        _ <- session.connect(Client)
        fiber <- session.recorder.record(1).either.fork
        op <- operation(session)
        _ <- session.event(Client, Event(op.id, "started"))
        stopped <- until(session.poll(Client))(_.operation.exists(_.phase == "stop"))
        _ <- (session.poll(Client) *> ZIO.sleep(500.millis)).repeatN(10)
        result <- fiber.join
      } yield {
        assert(stopped.operation.nonEmpty)
        assertEquals(code(result), "timeout")
      }
    })
  }

  test("started speech has a bounded completion deadline even with continuous heartbeats") {
    val offset = new AtomicLong(0L)
    val clock = new Clock {
      export Clock.ClockLive.{currentDateTime, currentTime, instant, javaClock, localDateTime, scheduler, sleep}
      def nanoTime(using Trace): UIO[Long] = Clock.ClockLive.nanoTime.map(_ + offset.get())
    }
    run(
      ZIO
        .scoped {
          for {
            session <- BrowserAudioSession.make
            _ <- session.connect(Client)
            speech <- session.speak("bounded speech").either.fork
            op <- operation(session)
            _ <- session.event(Client, Event(op.id, "started"))
            _ <- (ZIO.succeed(offset.addAndGet(1.second.toNanos)) *> session.poll(Client)).repeatN(120)
            result <- speech.join
            poll <- session.poll(Client)
          } yield {
            assertEquals(code(result), "timeout")
            assert(poll.operation.isEmpty)
          }
        }
        .withClock(clock)
    )
  }

  test("scope close fails pending waits and exposes closed status") {
    run {
      for {
        scope <- Scope.make
        session <- BrowserAudioSession.make.provideEnvironment(ZEnvironment[Scope](scope))
        _ <- session.connect(Client)
        fiber <- session.speak("closing").either.fork
        _ <- operation(session)
        _ <- scope.close(Exit.unit)
        result <- fiber.join
        status <- session.status
      } yield {
        assertEquals(code(result), "closed")
        assert(!status.connected)
      }
    }
  }
}

private[browser] object BrowserAudioTestSupport {
  val Client = "test-client-00000001"
  val OtherClient = "test-client-00000002"

  def run[A](effect: Task[A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new RuntimeException("test deadline"))(20.seconds))
      .getOrThrowFiberFailure()
  }

  def code[A](result: Either[Throwable, A]): String = result match {
    case Left(error: BrowserAudioError) => error.code
    case other                          => throw new AssertionError(s"Expected browser error, got $other")
  }

  def until[A](effect: Task[A])(predicate: A => Boolean): Task[A] =
    effect.flatMap(value =>
      if predicate(value) then ZIO.succeed(value) else ZIO.sleep(10.millis) *> until(effect)(predicate)
    )

  def operation(session: BrowserAudioSession): Task[BrowserProtocol.Operation] =
    until(session.poll(Client))(_.operation.nonEmpty).map(_.operation.get)

  def wav(dataBytes: Int): Chunk[Byte] = {
    val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer
      .put("RIFF".getBytes)
      .putInt(36 + dataBytes)
      .put("WAVEfmt ".getBytes)
      .putInt(16)
      .putShort(1.toShort)
      .putShort(1.toShort)
      .putInt(16000)
      .putInt(32000)
      .putShort(2.toShort)
      .putShort(16.toShort)
      .put("data".getBytes)
      .putInt(dataBytes)
    Chunk.fromArray(buffer.array())
  }
}
