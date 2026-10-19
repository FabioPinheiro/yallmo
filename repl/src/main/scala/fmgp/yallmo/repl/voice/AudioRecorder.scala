package fmgp.yallmo.repl.voice

import fmgp.yallmo.model.{AudioFormat, AudioInput}
import zio.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.{AudioFileFormat, AudioFormat as JavaAudioFormat, AudioInputStream, AudioSystem, DataLine}

/** Records bounded local microphone input as an in-memory 16 kHz mono WAV payload. */
trait AudioRecorder {
  def record(seconds: Int): Task[AudioInput]

  /** Captures until the caller stops or cancels it, with the normal maximum duration as a safety limit.
    *
    * The supplied effect is awaited concurrently with capture. A cancellation deliberately returns no audio, so callers
    * never send a cancelled recording to a transcription provider.
    */
  def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[AudioInput]]
}

/** The local terminal's decision while an interactive recording is active. */
enum VoiceRecordingTermination {
  case Stop
  case Cancel
}

object AudioRecorder {
  val MinDurationSeconds = 1
  val MaxDurationSeconds = 30
  val SampleRateHz = 16000.0f
  private val SampleSizeBits = 16
  private val ReadChunkMilliseconds = 100
  private val OutputFormat = pcmFormat(SampleRateHz, channels = 1)
  private val CandidateCaptureFormats = Seq(
    OutputFormat,
    pcmFormat(24000.0f, channels = 1),
    pcmFormat(44100.0f, channels = 1),
    pcmFormat(48000.0f, channels = 1),
    pcmFormat(44100.0f, channels = 2),
    pcmFormat(48000.0f, channels = 2)
  )

  val live: ULayer[AudioRecorder] = ZLayer.succeed(Live)

  private[voice] def toWav(pcm: Array[Byte], length: Int, captureFormat: JavaAudioFormat): AudioInput = {
    val wav = ByteArrayOutputStream()
    val captured = AudioInputStream(
      ByteArrayInputStream(pcm, 0, length),
      captureFormat,
      length / captureFormat.getFrameSize
    )
    val normalized =
      if captureFormat.matches(OutputFormat) then captured
      else AudioSystem.getAudioInputStream(OutputFormat, captured)
    try AudioSystem.write(normalized, AudioFileFormat.Type.WAVE, wav)
    finally {
      if normalized ne captured then normalized.close()
      captured.close()
    }
    val bytes = Chunk.fromArray(wav.toByteArray)
    AudioInput(
      bytes,
      AudioFormat.Wav,
      bytes.length.toLong,
      Some(length.toDouble / captureFormat.getFrameSize / captureFormat.getFrameRate)
    )
  }

  private object Live extends AudioRecorder {
    override def record(seconds: Int): Task[AudioInput] =
      for {
        _ <- ZIO.fromEither(validateDuration(seconds).left.map(VoiceInputError.InvalidDuration.apply))
        audio <- capture(seconds, () => None)
      } yield audio

    override def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[AudioInput]] =
      ZIO.scoped {
        for {
          terminationRef <- ZIO.succeed(new AtomicReference[Option[VoiceRecordingTermination]](None))
          listener <- termination.flatMap(value => ZIO.succeed(terminationRef.set(Some(value)))).fork
          audio <- capture(MaxDurationSeconds, () => terminationRef.get()).ensuring(listener.interrupt)
        } yield terminationRef.get() match {
          case Some(VoiceRecordingTermination.Cancel) => None
          case _                                      => Some(audio)
        }
      }

    private def capture(
        maximumSeconds: Int,
        termination: () => Option[VoiceRecordingTermination]
    ): Task[AudioInput] =
      ZIO
        .attemptBlocking {
          val format = selectCaptureFormat()
          val line = AudioSystem.getTargetDataLine(format)
          try {
            line.open(format)
            line.start()
            val pcm = Array.ofDim[Byte](maximumBytes(maximumSeconds, format))
            var offset = 0
            while offset < pcm.length && termination().isEmpty do {
              val requested = math.min(readChunkBytes(format), pcm.length - offset)
              val read = line.read(pcm, offset, requested)
              if read <= 0 then throw VoiceInputError.RecordingFailed("Microphone returned no audio data.")
              offset += read
            }

            if offset == 0 && termination().forall(_ == VoiceRecordingTermination.Stop) then
              throw VoiceInputError.RecordingFailed("Microphone returned no audio data.")

            toWav(pcm, offset, format)
          } finally {
            line.stop()
            line.close()
          }
        }
        .mapError {
          case error: VoiceInputError => error
          case error                  => VoiceInputError.RecordingFailed(message(error))
        }

    /** Selects a device-native input format which Java Sound can convert into the provider's compact WAV format. */
    private def selectCaptureFormat(): JavaAudioFormat = {
      val discovered = discoveredCaptureFormats
      (CandidateCaptureFormats ++ discovered).distinct
        .find { candidate =>
          val info = DataLine.Info(classOf[javax.sound.sampled.TargetDataLine], candidate)
          AudioSystem.isLineSupported(info) &&
          (candidate.matches(OutputFormat) || AudioSystem.isConversionSupported(OutputFormat, candidate))
        }
        .getOrElse {
          val formats = CandidateCaptureFormats.map(describe).mkString(", ")
          val deviceFormats =
            if discovered.isEmpty then "none reported" else discovered.map(describe).distinct.mkString(", ")
          throw VoiceInputError.MicrophoneUnavailable(
            s"No microphone supports a convertible capture format. Tried: $formats. Device formats: $deviceFormats."
          )
        }
    }

    /** Some devices expose only their native rate (for example 32 or 96 kHz), so inspect every target line as well as
      * trying common formats.
      */
    private def discoveredCaptureFormats: Seq[JavaAudioFormat] =
      AudioSystem.getMixerInfo.toSeq
        .flatMap { mixerInfo =>
          AudioSystem.getMixer(mixerInfo).getTargetLineInfo.toSeq.flatMap {
            case info: DataLine.Info => info.getFormats.toSeq
            case _                   => Seq.empty
          }
        }
        .filter(format => format.getSampleRate > 0 && format.getChannels > 0)

  }

  private[voice] def validateDuration(seconds: Int): Either[String, Unit] =
    Either.cond(
      seconds >= MinDurationSeconds && seconds <= MaxDurationSeconds,
      (),
      s"Voice duration must be between $MinDurationSeconds and $MaxDurationSeconds seconds."
    )

  private def pcmFormat(sampleRate: Float, channels: Int): JavaAudioFormat =
    JavaAudioFormat(sampleRate, SampleSizeBits, channels, true, false)

  private def maximumBytes(seconds: Int, format: JavaAudioFormat): Int =
    seconds * format.getFrameRate.toInt * format.getFrameSize

  private def readChunkBytes(format: JavaAudioFormat): Int =
    math.max(format.getFrameSize, format.getFrameRate.toInt * format.getFrameSize * ReadChunkMilliseconds / 1000)

  private def describe(format: JavaAudioFormat): String =
    s"${format.getSampleRate.toInt} Hz/${format.getChannels} channel"

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}

sealed trait VoiceInputError extends RuntimeException
object VoiceInputError {
  final case class InvalidDuration(message: String) extends RuntimeException(message) with VoiceInputError
  final case class MicrophoneUnavailable(message: String) extends RuntimeException(message) with VoiceInputError
  final case class RecordingFailed(message: String) extends RuntimeException(message) with VoiceInputError
}
