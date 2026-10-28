package fmgp.yallmo.view.web

import fmgp.yallmo.model.*
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** One bounded upload plus a normal agent invocation. Uses separate RPC documents, never a sequential batch or a
  * conversation session. Cancelling discards the local result and best-effort aborts unfinished upload, not execution.
  */
private[web] final class VoiceTransfer(rpc: VoiceTransfer.Rpc)(using ExecutionContext) {
  import VoiceTransfer.*

  def start(author: AgentId, bytes: Array[Byte], uploaded: () => Unit): Job = new Job(author, bytes, uploaded)

  final class Job private[VoiceTransfer] (author: AgentId, bytes: Array[Byte], uploaded: () => Unit) {
    private val terminal = Promise[Message]()
    val result: Future[Message] = terminal.future
    private val version = rpc.version
    private val blobId = BlobId.fresh()
    private val input = Message(
      MessageData(author, None, Instant.now(), MessagePayload.AudioAttachment(blobId, AudioFormat.Wav))
    )
    private var uploadStarted = false
    private var discarded = false
    private var abortSent = false
    private var uploadComplete = false
    private var response = Option.empty[Message]

    private def current: Boolean = !discarded && !terminal.isCompleted && rpc.version == version
    private def check(): Future[Unit] =
      if current then Future.successful(()) else Future.failed(new Discarded)

    private def abort(): Unit =
      if uploadStarted && !uploadComplete && !abortSent && rpc.version == version then {
        abortSent = true
        val _ =
          rpc.upload(BlobsUpload.Params.Abort(blobId)).recover { case NonFatal(_) =>
            BlobsUpload.Result.Aborted(blobId)
          }
      }

    def discard(): Unit = {
      discarded = true
      abort()
      terminal.tryFailure(new Discarded)
      ()
    }

    private def fail(error: Throwable): Unit = {
      abort()
      terminal.tryFailure(error)
      ()
    }

    private def finish(): Unit =
      if current && uploadComplete then
        response.foreach { message =>
          terminal.trySuccess(message)
          ()
        }

    private def append(offset: Int): Future[Unit] = check().flatMap { _ =>
      if offset == bytes.length then
        rpc.upload(BlobsUpload.Params.Complete(blobId)).flatMap {
          case BlobsUpload.Result.Completed(`blobId`) => Future.successful(())
          case _ => Future.failed(new RuntimeException("Invalid upload completion"))
        }
      else {
        val end = (offset + rpc.maxChunkBytes.max(1).min(MaxBlobChunkBytes)).min(bytes.length)
        val binary = bytes.slice(offset, end).iterator.map(byte => (byte & 0xff).toChar).mkString
        val encoded = js.Dynamic.global.btoa(binary).asInstanceOf[String]
        rpc.upload(BlobsUpload.Params.Append(blobId, offset.toLong, encoded)).flatMap {
          case BlobsUpload.Result.Appended(`blobId`, next) if next == end.toLong => append(end)
          case _ => Future.failed(new RuntimeException("Invalid upload acknowledgement"))
        }
      }
    }

    private def begin(): Unit = {
      if bytes.length < 46 || bytes.length > MaxWavBytes then fail(new RuntimeException("Invalid recording size"))
      else
        rpc.upload(BlobsUpload.Params.Begin(blobId, bytes.length.toLong)).onComplete {
          case Success(BlobsUpload.Result.Begun(`blobId`)) =>
            uploadStarted = true
            if !current then { abort(); terminal.tryFailure(new Discarded); () }
            else {
              // Admit the normal message while its blob is still arriving. The server awaits Complete before execution.
              rpc.invoke(AgentsInvoke.Params(Transcriber, input)).onComplete {
                case Success(message) if current =>
                  message.payload match {
                    case MessagePayload.Assistant(text)
                        if message.agentId == Transcriber &&
                          message.parentId.contains(input.id) && text.trim.nonEmpty =>
                      response = Some(message)
                      finish()
                    case _ => fail(new RuntimeException("Invalid transcription result"))
                  }
                case Failure(error)                       => fail(error)
                case Success(_) if rpc.version != version => fail(new Discarded)
                case _                                    => ()
              }
              append(0).onComplete {
                case Success(_) if current =>
                  uploadComplete = true
                  uploaded()
                  finish()
                case Failure(error)                       => fail(error)
                case Success(_) if rpc.version != version => fail(new Discarded)
                case _                                    => ()
              }
            }
          case Success(_)     => fail(new RuntimeException("Invalid upload acknowledgement"))
          case Failure(error) => fail(error)
        }
    }

    begin()
  }
}

private[web] object VoiceTransfer {
  val Transcriber: AgentId = AgentId("Transcriber")
  val MaxWavBytes: Int = 960044
  final class Discarded extends RuntimeException("Dictation discarded")

  trait Rpc {
    def version: Long
    def maxChunkBytes: Int = MaxBlobChunkBytes
    def upload(command: BlobsUpload.Params): Future[BlobsUpload.Result]
    def invoke(params: AgentsInvoke.Params): Future[Message]
  }

  def browser(client: OrchestratorWSClient)(using ExecutionContext): VoiceTransfer = new VoiceTransfer(new Rpc {
    def version: Long = client.connectionVersion
    override def maxChunkBytes: Int = client.blobChunkBytes
    def upload(command: BlobsUpload.Params): Future[BlobsUpload.Result] = client.uploadBlob(command)
    def invoke(params: AgentsInvoke.Params): Future[Message] = client.invokeAgent(params)
  })
}
