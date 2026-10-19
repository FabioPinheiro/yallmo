package fmgp.yallmo.repl.browser

import zio.*
import java.util.UUID
import fmgp.yallmo.model.AudioInput
import fmgp.yallmo.repl.voice.{AudioRecorder, VoiceRecordingTermination}

private[browser] final class BrowserAudioSession private (state: Ref.Synchronized[BrowserAudioSession.State]) {
  import BrowserAudioSession.*
  import BrowserProtocol.*

  val recorder: AudioRecorder = new AudioRecorder {
    def record(seconds: Int): Task[AudioInput] =
      if seconds < 1 || seconds > 30 then ZIO.fail(failure("duration"))
      else recording(seconds, ZIO.never).flatMap(value => ZIO.fromOption(value).orElseFail(failure("cancelled")))

    def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[AudioInput]] =
      recording(30, termination)
  }

  private def recording(
      seconds: Int,
      termination: UIO[VoiceRecordingTermination]
  ): IO[BrowserAudioError, Option[AudioInput]] =
    ZIO.acquireReleaseWith(begin("record", seconds, None))(op => cancel(op.id)) { op =>
      op.result.await.raceFirst(termination.flatMap {
        case VoiceRecordingTermination.Cancel => cancel(op.id).as(None)
        case VoiceRecordingTermination.Stop   =>
          // A concurrent final upload may have removed the operation; its result remains authoritative.
          stop(op.id).ignore *> op.result.await
      })
    }

  def speak(text: String): IO[BrowserAudioError, Unit] =
    if text.trim.isEmpty || text.length > MaxTextCharacters then ZIO.fail(failure("invalid_text"))
    else ZIO.acquireReleaseWith(begin("speak", 0, Some(text)))(op => cancel(op.id))(_.result.await.unit)

  def status: UIO[BrowserAudioStatus] = state.get.map(s =>
    BrowserAudioStatus(s.client.nonEmpty && !s.closed, s.operation.exists(op => op.kind == "record" && op.started))
  )

  def connect(clientId: String): IO[BrowserAudioError, Connected] =
    if !clientId.matches("[A-Za-z0-9_-]{16,80}") then ZIO.fail(failure("invalid_client"))
    else
      modify { (s, now) =>
        if s.closed then ZIO.fail(failure("closed"))
        else if s.client.exists(_.id != clientId) then ZIO.fail(failure("client_conflict"))
        else
          (failPending(s, "disconnected") *>
            ZIO.logInfo(if s.client.isDefined then "Browser audio client reconnected"
            else "Browser audio client connected"))
            .as((Connected(), s.copy(client = Some(Client(clientId, now)), operation = None)))
      }

  def poll(clientId: String): IO[BrowserAudioError, Poll] = withClient(clientId) { (s, _) =>
    ZIO.succeed((Poll(s.operation.map(_.view)), s))
  }

  def disconnect(clientId: String): IO[BrowserAudioError, Unit] = withClient(clientId) { (s, _) =>
    (failPending(s, "disconnected") *> ZIO.logInfo("Browser audio client disconnected reason=explicit"))
      .as(((), s.copy(client = None, operation = None)))
  }

  def event(clientId: String, event: Event): IO[BrowserAudioError, Unit] = withClient(clientId) { (s, now) =>
    current(s, event.operationId).flatMap { op =>
      event.event match {
        case "started" if op.phase == "start" =>
          val timeout = if op.kind == "record" then op.seconds * 1000 else SpeechTimeoutMs
          val next = op.copy(
            phase = if op.kind == "record" then "recording" else "speaking",
            started = true,
            deadline = now + timeout.millis.toNanos
          )
          ZIO.succeed(((), s.copy(operation = Some(next))))
        case "ended" if op.kind == "speak" && op.phase == "speaking" =>
          op.result.succeed(None).as(((), s.copy(operation = None)))
        case "error" =>
          op.result.fail(failure("browser_error")).as(((), s.copy(operation = None)))
        case _ => ZIO.fail(failure("stale_operation"))
      }
    }
  }

  /** Rechecked after the bounded HTTP body read, so cancellation during upload cannot publish bytes. */
  def upload(clientId: String, operationId: String, bytes: Chunk[Byte]): IO[BrowserAudioError, Unit] =
    withClient(clientId) { (s, _) =>
      uploadOperation(s, operationId).flatMap { op =>
        WavAudio.validate(bytes, op.seconds) match {
          case Left(error)  => op.result.fail(error).as((Left(error), s.copy(operation = None)))
          case Right(audio) => op.result.succeed(Some(audio)).as((Right(()), s.copy(operation = None)))
        }
      }
    }.flatMap(ZIO.fromEither(_))

  def checkUpload(clientId: String, operationId: String): IO[BrowserAudioError, Unit] =
    withClient(clientId)((s, _) => uploadOperation(s, operationId).as(((), s)))

  private def uploadOperation(s: State, id: String): IO[BrowserAudioError, Pending] =
    current(s, id).flatMap(op =>
      if op.kind == "record" && op.started then ZIO.succeed(op) else ZIO.fail(failure("stale_operation"))
    )

  private def begin(kind: String, seconds: Int, text: Option[String]): IO[BrowserAudioError, Pending] =
    for {
      result <- Promise.make[BrowserAudioError, Option[AudioInput]]
      id <- ZIO.succeed(UUID.randomUUID().toString)
      op <- modify { (s, now) =>
        if s.closed then ZIO.fail(failure("closed"))
        else if s.client.isEmpty then ZIO.fail(failure("not_connected"))
        else if s.operation.exists(op => kind != "speak" || op.kind != "speak") then ZIO.fail(failure("busy"))
        else {
          val op = Pending(id, kind, "start", seconds, text, false, now + StartTimeoutMs.millis.toNanos, result)
          failPending(s, "replaced").as((op, s.copy(operation = Some(op))))
        }
      }
    } yield op

  private def stop(id: String): IO[BrowserAudioError, Unit] = modify { (s, now) =>
    current(s, id).flatMap { op =>
      if !op.started then op.result.fail(failure("not_started")).as(((), s.copy(operation = None)))
      else
        ZIO.succeed(
          ((), s.copy(operation = Some(op.copy(phase = "stop", deadline = now + UploadTimeoutMs.millis.toNanos))))
        )
    }
  }

  private def cancel(id: String): UIO[Unit] = state.modifyZIO { s =>
    s.operation.filter(_.id == id) match {
      case Some(op) => op.result.succeed(None).as(((), s.copy(operation = None)))
      case None     => ZIO.succeed(((), s))
    }
  }

  private def current(s: State, id: String): IO[BrowserAudioError, Pending] =
    ZIO.fromOption(s.operation.filter(_.id == id)).orElseFail(failure("stale_operation"))

  private def withClient[A](
      id: String
  )(f: (State, Long) => IO[BrowserAudioError, (A, State)]): IO[BrowserAudioError, A] =
    modify { (s, now) =>
      if s.closed then ZIO.fail(failure("closed"))
      else if !s.client.exists(_.id == id) then ZIO.fail(failure("wrong_client"))
      else f(s.copy(client = Some(Client(id, now))), now)
    }

  private def modify[A](f: (State, Long) => IO[BrowserAudioError, (A, State)]): IO[BrowserAudioError, A] =
    Clock.nanoTime.flatMap { now =>
      state
        .modifyZIO { s =>
          expire(s, now).flatMap { expired =>
            f(expired, now).either.map {
              case Right((value, next)) => (Right(value), next)
              case Left(error)          => (Left(error), expired)
            }
          }
        }
        .flatMap(ZIO.fromEither(_))
    }

  private def expire(s: State, now: Long): UIO[State] =
    if s.client.exists(c => now - c.lastSeen >= DisconnectMs.millis.toNanos) then
      (failPending(s, "disconnected") *> ZIO.logInfo("Browser audio client disconnected reason=heartbeat_timeout"))
        .as(s.copy(client = None, operation = None))
    else
      s.operation match {
        case Some(op) if now >= op.deadline =>
          if op.kind == "record" && op.phase == "recording" then
            ZIO.succeed(
              s.copy(operation = Some(op.copy(phase = "stop", deadline = now + UploadTimeoutMs.millis.toNanos)))
            )
          else {
            val code = if op.phase == "start" then "start_timeout" else "timeout"
            op.result.fail(failure(code)).as(s.copy(operation = None))
          }
        case _ => ZIO.succeed(s)
      }

  private def failPending(s: State, code: String): UIO[Unit] =
    ZIO.foreachDiscard(s.operation)(_.result.fail(failure(code)))

  private[browser] def tick: UIO[Unit] = Clock.nanoTime.flatMap(now => state.updateZIO(expire(_, now)))

  def close: UIO[Unit] = state.modifyZIO { s =>
    (failPending(s, "closed") *>
      ZIO.when(s.client.nonEmpty)(ZIO.logInfo("Browser audio client disconnected reason=service_shutdown")))
      .as(((), State(None, None, closed = true)))
  }
}

private[browser] object BrowserAudioSession {
  private final case class Client(id: String, lastSeen: Long)
  private final case class Pending(
      id: String,
      kind: String,
      phase: String,
      seconds: Int,
      text: Option[String],
      started: Boolean,
      deadline: Long,
      result: Promise[BrowserAudioError, Option[AudioInput]]
  ) {
    def view: BrowserProtocol.Operation = BrowserProtocol.Operation(id, kind, phase, seconds * 1000, text)
  }
  private final case class State(client: Option[Client], operation: Option[Pending], closed: Boolean = false)

  def make: ZIO[Scope, Nothing, BrowserAudioSession] = for {
    state <- Ref.Synchronized.make(State(None, None))
    session = new BrowserAudioSession(state)
    _ <- ZIO.addFinalizer(session.close)
    _ <- (session.tick *> ZIO.sleep(250.millis)).forever.forkScoped
  } yield session
}
