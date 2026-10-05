package fmgp.yallmo.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private[model] object Utils {

  /** Returns the lowercase hexadecimal SHA-256 digest of UTF-8 text. */
  def sha256(value: String): String =
    sha256(value.getBytes(StandardCharsets.UTF_8))

  /** Returns the lowercase hexadecimal SHA-256 digest of bytes. */
  def sha256(value: Array[Byte]): String =
    sha256Bytes(value).map(byte => f"${byte & 0xff}%02x").mkString

  /** Returns the SHA-256 digest bytes. */
  def sha256Bytes(value: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(value)
}
