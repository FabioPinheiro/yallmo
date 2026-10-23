package fmgp.yallmo.model

import java.security.MessageDigest

/** JVM implementation of the shared content-addressing hash. */
private[model] object PlatformSha256 {
  def digest(value: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(value)
}
