package fmgp.yallmo.repl.browser

import zio.json.*

/** Browser failures are local input errors, never an agent request. Messages contain no browser-supplied data. */
final case class BrowserAudioError(code: String, override val getMessage: String) extends RuntimeException(getMessage)

/** `recordingStarted` is true only after the browser acknowledges actual capture, not command delivery. */
final case class BrowserAudioStatus(connected: Boolean, recordingStarted: Boolean)

private[browser] object BrowserProtocol {
  val MaxUploadBytes = 1024 * 1024
  val HeartbeatMs = 1000
  val DisconnectMs = 5000
  val StartTimeoutMs = 5000
  val UploadTimeoutMs = 5000
  val SpeechTimeoutMs = 120000
  val MaxTextCharacters = 16000

  final case class Connect(clientId: String) derives JsonCodec
  final case class Connected(
      heartbeatMs: Int = HeartbeatMs,
      disconnectMs: Int = DisconnectMs,
      maxRecordingMs: Int = 30000,
      maxUploadBytes: Int = MaxUploadBytes
  ) derives JsonEncoder
  final case class Event(operationId: String, event: String) derives JsonCodec
  final case class Operation(
      id: String,
      kind: String,
      phase: String,
      maxRecordingMs: Int,
      text: Option[String]
  ) derives JsonEncoder
  final case class Poll(operation: Option[Operation], heartbeatMs: Int = HeartbeatMs) derives JsonEncoder
  final case class Error(error: String) derives JsonEncoder

  def failure(code: String): BrowserAudioError = BrowserAudioError(
    code,
    code match {
      case "not_connected" => "Open the browser audio setup URL and enable audio first."
      case "busy"          => "Browser audio is already busy."
      case "disconnected"  => "Browser audio disconnected; enable audio again."
      case "start_timeout" => "Browser audio did not start; focus the page and enable audio again."
      case "not_started"   => "Browser recording has not started."
      case "timeout"       => "Browser audio operation timed out."
      case "browser_error" => "Browser audio failed; focus the page and enable audio again."
      case "invalid_wav"   => "Browser audio must be a nonempty, bounded 16 kHz mono PCM WAV."
      case "invalid_text"  => "Browser speech needs between 1 and 16000 characters."
      case "duration"      => "Browser recording duration must be between 1 and 30 seconds."
      case "startup"       => "Unable to start the local browser audio server. Text and JVM voice remain available."
      case "closed"        => "Browser audio server is closed."
      case _               => "Browser audio operation was cancelled or replaced."
    }
  )
}
