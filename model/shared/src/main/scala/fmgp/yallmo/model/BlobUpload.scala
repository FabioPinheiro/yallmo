package fmgp.yallmo.model

import zio.*

/** Fixed upload bounds. Declared bytes are reserved at Begin; empty uploads also count toward MaxUploads.
  *
  * This is only on 'model' becuase both 'core' and 'protocol' use it
  */
object BlobUploadLimits {
  val MaxUploads: Int = 16
  val MaxUploadBytes: Int = 4 * 1024 * 1024
  val MaxReservedBytes: Long = 16L * 1024 * 1024
  val MaxChunkBytes: Int = 65536
  val IdleTimeout: Duration = 10.seconds
  val MaximumLifetime: Duration = 60.seconds
  val AwaitTimeout: Duration = 2.minutes
}

enum BlobUploadFailure(val message: String) {
  case InvalidSize extends BlobUploadFailure("Invalid upload size")
  case Capacity extends BlobUploadFailure("Upload capacity exhausted")
  case Unknown extends BlobUploadFailure("Upload not found for this connection")
  case InvalidChunk extends BlobUploadFailure("Invalid upload chunk or offset")
  case Incomplete extends BlobUploadFailure("Upload is incomplete")
  case Aborted extends BlobUploadFailure("Upload aborted")
  case Expired extends BlobUploadFailure("Upload expired")
  case Disconnected extends BlobUploadFailure("Upload connection closed")
  case AwaitTimedOut extends BlobUploadFailure("Blob wait timed out")
  case Storage extends BlobUploadFailure("Upload storage failed")
}
