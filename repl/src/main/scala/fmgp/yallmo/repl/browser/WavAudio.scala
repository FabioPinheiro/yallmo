package fmgp.yallmo.repl.browser

import fmgp.yallmo.model.{AudioFormat, AudioInput}
import zio.Chunk

/** Accept only the page's canonical 44-byte PCM header; MIME labels are not format validation. */
private[browser] object WavAudio {
  def validate(bytes: Chunk[Byte], maximumSeconds: Int): Either[BrowserAudioError, AudioInput] = {
    def u16(offset: Int): Int = (bytes(offset) & 255) | ((bytes(offset + 1) & 255) << 8)
    def u32(offset: Int): Long = u16(offset).toLong | (u16(offset + 2).toLong << 16)
    def tag(offset: Int, value: String): Boolean = value.indices.forall(i => bytes(offset + i) == value(i).toByte)
    val valid = bytes.length > 44 && bytes.length <= BrowserProtocol.MaxUploadBytes &&
      bytes.length <= 44 + maximumSeconds * 32000 && (bytes.length - 44) % 2 == 0 &&
      tag(0, "RIFF") && u32(4) == bytes.length - 8 && tag(8, "WAVE") &&
      tag(12, "fmt ") && u32(16) == 16 && u16(20) == 1 && u16(22) == 1 &&
      u32(24) == 16000 && u32(28) == 32000 && u16(32) == 2 && u16(34) == 16 &&
      tag(36, "data") && u32(40) == bytes.length - 44
    Either.cond(
      valid,
      AudioInput(bytes, AudioFormat.Wav, bytes.length.toLong, Some((bytes.length - 44).toDouble / 32000)),
      BrowserProtocol.failure("invalid_wav")
    )
  }
}
