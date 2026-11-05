package fmgp.yallmo.view.web

import fmgp.yallmo.clientharness.{DictationKeyboard, KeyInput}
import fmgp.yallmo.model.*
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout
import java.time.Instant
import zio.Chunk

final class VoiceSuite extends FunSuite {
  private val human = AgentId("web-user-human")
  private val info = Initialize.Result(
    "test",
    Identity(UserId("human"), Role.Operator, Chunk(UserId.chatAgent(UserId("human")), human)),
    List(BlobsUpload.method, AgentsInvoke.method),
    Nil,
    MaxBlobChunkBytes,
    Some(Chunk(VoiceTransfer.Transcriber))
  )

  private final class Rpc extends VoiceTransfer.Rpc {
    var version = 1L
    val uploads = mutable.ArrayBuffer.empty[BlobsUpload.Params]
    val invocations = mutable.ArrayBuffer.empty[(AgentsInvoke.Params, Promise[Message])]
    var begin = Option.empty[Promise[BlobsUpload.Result]]
    var complete = Option.empty[Promise[BlobsUpload.Result]]
    var badOffset = false
    var chunkSize = MaxBlobChunkBytes
    override def maxChunkBytes: Int = chunkSize
    def upload(command: BlobsUpload.Params): Future[BlobsUpload.Result] = {
      uploads += command
      command match {
        case BlobsUpload.Params.Begin(id, _) =>
          begin.fold(Future.successful[BlobsUpload.Result](BlobsUpload.Result.Begun(id)))(_.future)
        case BlobsUpload.Params.Append(id, offset, encoded) =>
          val size = java.util.Base64.getDecoder.decode(encoded).length
          Future.successful(BlobsUpload.Result.Appended(id, offset + size + (if badOffset then 1 else 0)))
        case BlobsUpload.Params.Complete(id) =>
          complete.fold(Future.successful[BlobsUpload.Result](BlobsUpload.Result.Completed(id)))(_.future)
        case BlobsUpload.Params.Abort(id) => Future.successful(BlobsUpload.Result.Aborted(id))
      }
    }
    def invoke(params: AgentsInvoke.Params): Future[Message] = {
      val result = Promise[Message]()
      invocations += ((params, result))
      result.future
    }
    def reply(text: String = "transcript"): Unit = {
      val (params, result) = invocations.last
      result.success(
        Message(MessageData(params.agentId, Some(params.input.id), Instant.EPOCH, Message.Assistant(text)))
      )
      ()
    }
    def blobId: BlobId = uploads.collectFirst { case BlobsUpload.Params.Begin(id, _) => id }.get
    def completed: Boolean = uploads.exists(_.isInstanceOf[BlobsUpload.Params.Complete])
    def aborted: Boolean = uploads.exists(_.isInstanceOf[BlobsUpload.Params.Abort])
  }

  private final class Recorder extends VoiceRecorder {
    var available = true
    var sessions = Vector.empty[Capture]
    final class Capture(
        val started: () => Unit,
        val completed: (Array[Byte], Boolean) => Unit,
        val failed: String => Unit
    ) extends VoiceRecorder.Handle {
      var cancelled = false
      var stopped = false
      def stop(): Unit = stopped = true
      def cancel(): Unit = cancelled = true
    }
    def start(
        started: () => Unit,
        completed: (Array[Byte], Boolean) => Unit,
        failed: String => Unit
    ): VoiceRecorder.Handle = {
      val capture = new Capture(started, completed, failed)
      sessions :+= capture
      capture
    }
  }

  private def chatModel(): ChatModel = new ChatModel(new OrchestratorWSClient("ws://unused"))
  private def model(chat: ChatModel, rpc: Rpc, recorder: Recorder, notices: mutable.ArrayBuffer[UiNotice]): VoiceModel =
    new VoiceModel(chat, recorder, new VoiceTransfer(rpc), notice => { notices += notice; () })
  private def waitFor(condition: => Boolean): Future[Unit] = {
    val result = Promise[Unit]()
    def loop(left: Int): Unit =
      if condition then { result.trySuccess(()); () }
      else if left == 0 then { result.tryFailure(new AssertionError("Voice transition timed out")); () }
      else { setTimeout(2)(loop(left - 1)); () }
    loop(500)
    result.future
  }

  test("recording is gated by operator methods and configured Transcriber ID, not chat-agent selection") {
    val chat = chatModel()
    val recorder = new Recorder
    val voice = model(chat, new Rpc, recorder, mutable.ArrayBuffer.empty)
    val unavailable = List(
      info.copy(identity = Identity(UserId("human"), Role.Viewer, Chunk(UserId.chatAgent(UserId("human")), human))),
      info.copy(methods = List(AgentsInvoke.method)),
      info.copy(methods = List(BlobsUpload.method)),
      info.copy(configuredAgentIds = None),
      info.copy(configuredAgentIds = Some(Chunk(AgentId("transcriber"))))
    )
    unavailable.foreach { value =>
      voice.configure(value); assert(!voice.state.now().available); voice.start(chat.voiceTarget(None))
    }
    assert(recorder.sessions.isEmpty)
    voice.configure(info)
    assert(voice.state.now().available)
    assertEquals(chat.state.now().current.agent, None)
    voice.start(chat.voiceTarget(None))
    assertEquals(recorder.sessions.size, 1)
    assert(chat.state.now().voiceBusy)
    voice.disable()
    assert(recorder.sessions.last.cancelled)
    assert(!chat.state.now().voiceBusy)
  }

  test(
    "normal invocation is sent while bounded chunks upload; returned message must wait for complete acknowledgement"
  ) {
    val rpc = new Rpc
    val completion = Promise[BlobsUpload.Result]()
    rpc.complete = Some(completion)
    var uploaded = 0
    val job = new VoiceTransfer(rpc).start(human, Array.fill[Byte](VoiceTransfer.MaxWavBytes)(42), () => uploaded += 1)
    for {
      _ <- waitFor(rpc.completed)
      _ = assertEquals(rpc.invocations.size, 1)
      params = rpc.invocations.head._1
      _ = assertEquals(params.agentId, VoiceTransfer.Transcriber)
      _ = assertEquals(params.input.agentId, human)
      _ = assertEquals(params.input.id, MessageId.from(params.input.data))
      _ = assertEquals(params.input.parentId, None)
      _ = rpc.reply()
      _ <- waitFor(rpc.invocations.head._2.isCompleted)
      _ = assert(!job.result.isCompleted)
      blob = params.input.payload.blobIds.head
      _ = completion.success(BlobsUpload.Result.Completed(blob))
      result <- job.result
    } yield {
      val chunks = rpc.uploads.collect { case value: BlobsUpload.Params.Append => value }
      assertEquals(chunks.size, 15)
      assert(chunks.forall(_.blobId == blob))
      assert(rpc.uploads.contains(BlobsUpload.Params.Complete(blob)))
      assert(chunks.forall(chunk => java.util.Base64.getDecoder.decode(chunk.dataBase64).length <= MaxBlobChunkBytes))
      assertEquals(
        chunks.map(chunk => java.util.Base64.getDecoder.decode(chunk.dataBase64).length).sum,
        VoiceTransfer.MaxWavBytes
      )
      assertEquals(uploaded, 1)
      assertEquals(result.payload, Message.Assistant("transcript"))
      assertEquals(result.parentId, Some(params.input.id))
      assert(!rpc.aborted)
    }
  }

  test("discard during Begin cleans up the acknowledged blob without invoking or uploading chunks") {
    val rpc = new Rpc
    val begin = Promise[BlobsUpload.Result]()
    rpc.begin = Some(begin)
    val job = new VoiceTransfer(rpc).start(human, Array.fill[Byte](46)(0), () => ())
    job.discard()
    begin.success(BlobsUpload.Result.Begun(rpc.blobId))
    for {
      _ <- job.result.failed
      _ <- waitFor(rpc.aborted)
    } yield {
      assert(rpc.invocations.isEmpty)
      assertEquals(rpc.uploads.size, 2)
      assertEquals(rpc.uploads.last, BlobsUpload.Params.Abort(rpc.blobId))
    }
  }

  test("Begin must acknowledge the requested BlobId, never switch to an unrelated upload") {
    val rpc = new Rpc
    val begin = Promise[BlobsUpload.Result]()
    rpc.begin = Some(begin)
    val job = new VoiceTransfer(rpc).start(human, Array.fill[Byte](46)(0), () => ())
    begin.success(BlobsUpload.Result.Begun(BlobId.fresh()))
    job.result.failed.map { _ =>
      assert(rpc.invocations.isEmpty)
      assertEquals(rpc.uploads.size, 1)
      assert(!rpc.aborted)
    }
  }

  test("generation changes never send old uploads or invocations on the replacement connection") {
    val rpc = new Rpc
    val begin = Promise[BlobsUpload.Result]()
    rpc.begin = Some(begin)
    val job = new VoiceTransfer(rpc).start(human, Array.fill[Byte](46)(0), () => ())
    rpc.version += 1
    begin.success(BlobsUpload.Result.Begun(rpc.blobId))
    job.result.failed.map { _ =>
      assert(rpc.invocations.isEmpty)
      assertEquals(rpc.uploads.size, 1)
    }
  }

  test("bad acknowledgements and agent errors abort unfinished uploads without replay") {
    val rpc = new Rpc
    rpc.badOffset = true
    val job = new VoiceTransfer(rpc).start(human, Array.fill[Byte](46)(0), () => ())
    job.result.failed.map { _ =>
      assert(rpc.aborted)
      assertEquals(rpc.invocations.size, 1)
      assertEquals(rpc.uploads.count(_.isInstanceOf[BlobsUpload.Params.Append]), 1)
    }
  }

  test("smaller negotiated chunks are respected and oversized recordings never begin an upload") {
    val rpc = new Rpc
    rpc.chunkSize = 16
    val job = new VoiceTransfer(rpc).start(human, Array.fill[Byte](46)(0), () => ())
    for {
      _ <- waitFor(rpc.completed)
      _ = rpc.reply()
      _ <- job.result
      rejected = new VoiceTransfer(rpc).start(human, Array.fill[Byte](VoiceTransfer.MaxWavBytes + 1)(0), () => ())
      _ <- rejected.result.failed
    } yield {
      assertEquals(
        rpc.uploads.collect { case value: BlobsUpload.Params.Append =>
          java.util.Base64.getDecoder.decode(value.dataBase64).length
        }.toList,
        List(16, 16, 14)
      )
      assertEquals(rpc.uploads.count(_.isInstanceOf[BlobsUpload.Params.Begin]), 1)
    }
  }

  test("voice work blocks Send and creation but not draft editing") {
    val chat = chatModel()
    chat.state.update(_.copy(enabled = true))
    chat.draft("editable")
    chat.setVoiceBusy(true)
    chat.send()
    chat.newConversation("")
    chat.draft("still editable")
    assertEquals(chat.state.now().pending, None)
    assertEquals(chat.state.now().current.draft, "still editable")
    assertEquals(chat.state.now().sendBlockedReason, Some("Finish or discard dictation before sending."))
  }

  test("invalid agent output never becomes a transcript") {
    val rpc = new Rpc
    val job = new VoiceTransfer(rpc).start(human, Array.fill[Byte](46)(0), () => ())
    for {
      _ <- waitFor(rpc.completed)
      _ = rpc.invocations.head._2
        .success(Message(MessageData(human, None, Instant.EPOCH, Message.User("not a transcript"))))
      _ <- job.result.failed
    } yield assertEquals(rpc.invocations.size, 1)
  }

  test("stop, limit notification and transcript insertion do not send or create a conversation") {
    val chat = chatModel()
    chat.draft("hello world")
    val rpc = new Rpc
    val recorder = new Recorder
    val notices = mutable.ArrayBuffer.empty[UiNotice]
    val voice = model(chat, rpc, recorder, notices)
    voice.configure(info)
    voice.start(chat.voiceTarget(Some((6, 6))))
    recorder.sessions.last.started()
    voice.stop()
    assert(recorder.sessions.last.stopped)
    recorder.sessions.last.completed(Array.fill[Byte](46)(0), true)
    for {
      _ <- waitFor(rpc.completed)
      _ = rpc.reply("new ")
      _ <- waitFor(!voice.state.now().busy)
    } yield {
      assertEquals(chat.state.now().current.draft, "hello new world")
      assertEquals(chat.state.now().active, "")
      assertEquals(chat.state.now().sessions, Nil)
      assertEquals(chat.state.now().pending, None)
      assert(notices.exists(_.message.contains("30-second limit")))
      assert(!chat.state.now().voiceBusy)
    }
  }

  test("Space stops once and Escape discards the recording without uploads or draft changes") {
    val chat = chatModel()
    chat.draft("keep this")
    val rpc = new Rpc
    val recorder = new Recorder
    val voice = model(chat, rpc, recorder, mutable.ArrayBuffer.empty)
    val keys = new DictationKeyboard(() => voice.keyboardMode, () => voice.stop(), () => voice.cancel())
    voice.configure(info)
    voice.start(chat.voiceTarget(None))
    val capture = recorder.sessions.last
    capture.started()
    assert(keys.keyDown(KeyInput(" ")))
    assert(capture.stopped)
    assertEquals(voice.state.now().phase, VoicePhase.Stopping)
    assert(keys.keyDown(KeyInput(" ", repeat = true)))
    assert(keys.keyUp(" "))
    assert(keys.keyDown(KeyInput("Escape")))
    assert(capture.cancelled)
    capture.completed(Array.fill[Byte](46)(0), false)
    assert(rpc.uploads.isEmpty)
    assert(rpc.invocations.isEmpty)
    assertEquals(chat.state.now().current.draft, "keep this")
    assertEquals(voice.state.now().phase, VoicePhase.Idle)
    assert(!chat.state.now().voiceBusy)
  }

  test("capture and transfer callbacks after discard, disconnect or reconfigure cannot change any draft") {
    val chat = chatModel()
    val rpc = new Rpc
    val recorder = new Recorder
    val voice = model(chat, rpc, recorder, mutable.ArrayBuffer.empty)
    voice.configure(info)
    voice.start(chat.voiceTarget(None))
    val first = recorder.sessions.last
    voice.cancel(false)
    first.started(); first.completed(Array.fill[Byte](46)(0), false); first.failed("private")
    assert(rpc.uploads.isEmpty)
    voice.start(chat.voiceTarget(None))
    recorder.sessions.last.started()
    recorder.sessions.last.completed(Array.fill[Byte](46)(0), false)
    for {
      _ <- waitFor(rpc.completed)
      _ = voice.disable()
      _ = rpc.reply("must not insert")
      _ <- waitFor(rpc.invocations.head._2.isCompleted)
    } yield {
      assertEquals(chat.state.now().current.draft, "")
      assert(!voice.state.now().available)
      assert(!voice.state.now().busy)
    }
  }

  test("cursor insertion replaces a valid selection; edits invalidate the captured range and append instead") {
    val chat = chatModel()
    chat.draft("hello selected world")
    val target = chat.voiceTarget(Some((6, 14)))
    chat.insertTranscript(target, "new")
    assertEquals(chat.state.now().current.draft, "hello new world")
    val stale = chat.voiceTarget(Some((0, 5)))
    chat.draft("changed")
    chat.draft(stale.draft)
    chat.insertTranscript(stale, "next")
    assertEquals(chat.state.now().current.draft, "hello new world next")
    chat.insertTranscript(chat.voiceTarget(None), "again")
    assertEquals(chat.state.now().current.draft, "hello new world next again")
  }

  test("insertion targets the original draft, not a newly selected conversation") {
    val chat = chatModel()
    chat.draft("original")
    val target = chat.voiceTarget(None)
    chat.state.update(s =>
      s.copy(active = "other", conversations = s.conversations.updated("other", ChatConversation(draft = "untouched")))
    )
    chat.insertTranscript(target, "transcript")
    assertEquals(chat.state.now().current.draft, "untouched")
    assertEquals(chat.state.now().conversations("").draft, "original transcript")
  }
}
