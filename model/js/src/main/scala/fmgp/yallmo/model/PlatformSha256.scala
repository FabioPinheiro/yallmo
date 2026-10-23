package fmgp.yallmo.model

import pt.kcry.sha.Sha2_256

/** Synchronous SHA-256 for content addressing in browsers and Node.js. */
private[model] object PlatformSha256 {
  def digest(value: Array[Byte]): Array[Byte] =
    Sha2_256.hash(value)
}
