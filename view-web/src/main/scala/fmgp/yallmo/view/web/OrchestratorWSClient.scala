package fmgp.yallmo.view.web

import fmgp.yallmo.model.{AgentId, BlobId, Message}
import fmgp.yallmo.orchestration.{SessionId, SessionSummary}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.server.protocol.YallmoProtocol.given
import java.nio.charset.StandardCharsets.UTF_8
import org.scalajs.dom
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js.timers.{SetTimeoutHandle, clearTimeout, setTimeout}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import zio.json.*
import zio.json.ast.Json

/** Browser adapter for the same JSON-RPC contract as the JVM client. One instance belongs to the application root:
  * connect on mount, close on unmount, and explicitly reconnect on Refresh. Requests are never replayed on reconnect.
  */
final class OrchestratorWSClient private[web] (
    endpoint: String,
    openSocket: (String, OrchestratorWSClient.Events) => OrchestratorWSClient.Socket =
      OrchestratorWSClient.browserSocket,
    timeoutMillis: Int = 15000,
    maxDownloadBytes: Long = 64L * 1024 * 1024,
    onDisconnect: Throwable => Unit = _ => ()
)(using ExecutionContext) {
  import OrchestratorWSClient.*

  private final case class Pending(result: Promise[Json], timer: Option[SetTimeoutHandle])
  private val pending = mutable.Map.empty[String, Pending]
  private val objectUrls = mutable.Map.empty[String, SetTimeoutHandle]
  private var socket = Option.empty[Socket]
  private var ready = Promise[Initialize.Result]()
  private var generation = 0L
  private var nextId = 0L
  private var chunkBytes = MaxBlobChunkBytes
  private var openingTimer = Option.empty[SetTimeoutHandle]

  /** Completes after initialization. Calling this again while connected reuses the same connection. */
  def connect(userId: Option[AgentId] = None): Future[Initialize.Result] = {
    if socket.isEmpty then {
      if ready.isCompleted then ready = Promise[Initialize.Result]()
      generation += 1
      val current = generation
      val handshake = ready
      val events = Events(
        opened = () => {
          if current == generation then {
            openingTimer.foreach(clearTimeout)
            openingTimer = None
            send[Initialize.Params, Initialize.Result](
              Initialize.method,
              Initialize.Params(Version, "view-web", userId)
            )
              .onComplete {
                case Success(info) if current == generation =>
                  if info.protocolVersion != Version || info.maxBlobChunkBytes <= 0 || info.maxBlobChunkBytes > MaxBlobChunkBytes
                  then terminate(new RuntimeException("Incompatible server protocol"))
                  else {
                    chunkBytes = info.maxBlobChunkBytes
                    handshake.trySuccess(info)
                  }
                case Failure(error) if current == generation => terminate(error)
                case _                                       => ()
              }
          }
        },
        message = text => if current == generation then receive(text),
        closed = () =>
          if current == generation then
            terminate(new RuntimeException("Connection closed. Click Refresh to reconnect."))
      )
      openingTimer = Some(setTimeout(timeoutMillis) {
        if current == generation then terminate(new RuntimeException("Connection timed out"))
      })
      try socket = Some(openSocket(endpoint, events))
      catch { case NonFatal(error) => terminate(error) }
    }
    ready.future
  }

  def sessions(): Future[List[SessionSummary]] =
    request[Empty, SessionsList.Result](SessionsList.method, Empty()).map(_.toList)
  def session(id: String): Future[SessionSnapshot] =
    Future
      .fromTry(Try(SessionId.from(id).fold(reason => throw new IllegalArgumentException(reason), identity)))
      .flatMap(id => request(SessionsGet.method, SessionsGet.Params(id)))
  def createSession(name: Option[SessionId] = None): Future[SessionSnapshot] =
    request(SessionsCreate.method, SessionsCreate.Params(name))
  def submitTurn(params: TurnSubmit.Params): Future[TurnOutcome] = request(TurnSubmit.method, params)
  def invokeAgent(params: AgentsInvoke.Params): Future[Message] = request(AgentsInvoke.method, params)
  def uploadBlob(params: BlobsUpload.Params): Future[BlobsUpload.Result] = request(BlobsUpload.method, params)
  private[web] def connectionVersion: Long = generation
  private[web] def blobChunkBytes: Int = chunkBytes

  def diagnostic(id: SessionId): Future[DiagnosticsRequest.Result] =
    request(DiagnosticsRequest.method, DiagnosticsRequest.Params(id))

  def messages(): Future[List[MessageSnapshot]] =
    request[Empty, MessagesList.Result](MessagesList.method, Empty()).map(_.toList)
  def agents(): Future[List[String]] =
    request[Empty, AgentsList.Result](AgentsList.method, Empty()).map(_.map(AgentId.value).toList)
  def blobs(): Future[List[BlobId]] = request[Empty, BlobsList.Result](BlobsList.method, Empty()).map(_.toList)

  def close(): Unit = {
    terminate(new RuntimeException("Connection closed"))
    objectUrls.foreach { case (url, timer) =>
      clearTimeout(timer)
      dom.URL.revokeObjectURL(url)
    }
    objectUrls.clear()
  }

  /** Bounded sequential chunk requests leave the shared connection free to serve other UI queries. */
  private[web] def readDownload(id: BlobId): Future[Array[Byte]] = ready.future.flatMap { _ =>
    val transferGeneration = generation
    val bytes = mutable.ArrayBuilder.make[Byte]
    def next(offset: Long, expectedTotal: Option[Long]): Future[Array[Byte]] =
      if transferGeneration != generation then Future.failed(new RuntimeException("Attachment transfer interrupted"))
      else
        request[BlobsRead.Params, BlobsRead.Result](BlobsRead.method, BlobsRead.Params(id, offset, chunkBytes))
          .flatMap { chunk =>
            Future
              .fromTry(Try {
                if transferGeneration != generation then throw new RuntimeException("Attachment transfer interrupted")
                val decoded = decodeBase64(chunk.dataBase64)
                val end = offset + decoded.length
                if chunk.blobId != id || chunk.offset != offset || chunk.totalBytes < 0 || chunk.totalBytes > maxDownloadBytes ||
                  expectedTotal.exists(
                    _ != chunk.totalBytes
                  ) || decoded.length > chunkBytes || end > chunk.totalBytes ||
                  chunk.eof != (end == chunk.totalBytes) || (!chunk.eof && decoded.isEmpty)
                then throw new RuntimeException("Invalid or oversized attachment transfer")
                bytes ++= decoded
                (end, chunk.eof)
              })
              .flatMap { case (end, eof) =>
                if eof then Future.successful(bytes.result()) else next(end, Some(chunk.totalBytes))
              }
          }
    next(0L, None)
  }

  def download(id: BlobId): Future[Unit] = ready.future.flatMap { _ =>
    val transferGeneration = generation
    readDownload(id).map { bytes =>
      if transferGeneration != generation then throw new RuntimeException("Attachment transfer interrupted")
      val data = new Uint8Array(bytes.length)
      bytes.indices.foreach(index => data(index) = (bytes(index) & 0xff).toShort)
      val blob = new dom.Blob(js.Array(data.buffer), new dom.BlobPropertyBag { `type` = "application/octet-stream" })
      val url = dom.URL.createObjectURL(blob)
      val anchor = dom.document.createElement("a").asInstanceOf[dom.HTMLAnchorElement]
      anchor.href = url
      anchor.download = id.value
      dom.document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      objectUrls(url) = setTimeout(1000) {
        dom.URL.revokeObjectURL(url)
        objectUrls -= url
      }
      ()
    }
  }

  private def request[P: JsonCodec, A: JsonDecoder](method: String, params: P): Future[A] = {
    val handshake = ready
    handshake.future.flatMap { _ =>
      if handshake ne ready then Future.failed(new RuntimeException("Connection changed"))
      else send[P, A](method, params)
    }
  }

  private def send[P: JsonCodec, A: JsonDecoder](method: String, params: P): Future[A] = socket match {
    case None => Future.failed(new RuntimeException("Connection closed. Click Refresh to reconnect."))
    case Some(_) if pending.size >= 128 => Future.failed(new RuntimeException("Too many pending requests"))
    case Some(connection)               =>
      nextId += 1
      val id = nextId.toString
      val result = Promise[Json]()
      // Execution is terminal-response only and may legitimately outlast a read deadline.
      val timer = Option.when(method != TurnSubmit.method && method != AgentsInvoke.method)(setTimeout(timeoutMillis) {
        pending.remove(id).foreach(_.result.tryFailure(new RuntimeException("Request timed out")))
      })
      pending(id) = Pending(result, timer)
      try connection.send(JsonRpc.Request(Json.Str(id), method, params).toJson)
      catch { case NonFatal(error) => terminate(error) }
      result.future.flatMap(value =>
        Future.fromTry(Try(value.as[A].fold(_ => throw new RuntimeException("Invalid server result"), identity)))
      )
  }

  private def receive(text: String): Unit = {
    val decoded = for {
      _ <- Either.cond(text.getBytes(UTF_8).length <= 1024 * 1024, (), "Server message too large")
      json <- JsonRpc.parseDocument(text)
      fields <- json match {
        case Json.Obj(values) if values.size == values.toMap.size => Right(values.toMap)
        case _                                                    => Left("Invalid server response")
      }
      _ <- Either.cond(
        fields.get("jsonrpc").contains(Json.Str(JsonRpc.Version)) && !fields.contains("method"),
        (),
        "Invalid server response"
      )
      id <- fields.get("id").collect { case Json.Str(value) => value }.toRight("Invalid response ID")
      outcome <- (fields.get("result"), fields.get("error")) match {
        case (Some(value), None) => Right(Right(value))
        case (None, Some(error)) => error.as[JsonRpc.Error].map(value => Left(new RpcException(value)))
        case _                   => Left("Invalid result/error response")
      }
    } yield (id, outcome)
    decoded match {
      case Left(error)          => terminate(new RuntimeException(error))
      case Right((id, outcome)) =>
        pending.remove(id).foreach { entry =>
          entry.timer.foreach(clearTimeout)
          entry.result.tryComplete(outcome.toTry)
        }
    }
  }

  private def terminate(error: Throwable): Unit = {
    generation += 1
    openingTimer.foreach(clearTimeout)
    openingTimer = None
    val previous = socket
    socket = None
    ready.tryFailure(error)
    pending.values.foreach { entry =>
      entry.timer.foreach(clearTimeout)
      entry.result.tryFailure(error)
    }
    pending.clear()
    previous.foreach(_.close())
    onDisconnect(error)
  }
}

object OrchestratorWSClient {
  final class RpcException(val error: JsonRpc.Error) extends RuntimeException(error.message)

  def canChat(info: Initialize.Result): Boolean =
    List(SessionsCreate.method, TurnSubmit.method).forall(info.methods.contains)

  def claimedUser(name: String): Option[AgentId] =
    Option.when(name.trim.nonEmpty)(AgentId("user-" + name.trim))

  private[web] trait Socket {
    def send(text: String): Unit
    def close(): Unit
  }
  private[web] final case class Events(opened: () => Unit, message: String => Unit, closed: () => Unit)

  private[web] def browserSocket(endpoint: String, events: Events): Socket = {
    val socket = new dom.WebSocket(endpoint)
    socket.onopen = _ => events.opened()
    socket.onmessage = event =>
      if js.typeOf(event.data.asInstanceOf[js.Any]) == "string" then events.message(event.data.asInstanceOf[String])
      else events.closed()
    socket.onclose = _ => events.closed()
    socket.onerror = _ => events.closed()
    new Socket {
      def send(text: String): Unit = socket.send(text)
      def close(): Unit = socket.close()
    }
  }

  def defaultEndpoint: String =
    s"${if dom.window.location.protocol == "https:" then "wss" else "ws"}://${dom.window.location.host}/rpc"

  private def decodeBase64(value: String): Array[Byte] = {
    val binary = js.Dynamic.global.atob(value).asInstanceOf[String]
    Array.tabulate(binary.length)(index => binary.charAt(index).toByte)
  }
}
