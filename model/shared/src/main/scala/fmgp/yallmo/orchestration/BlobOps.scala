package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import zio.*

/** Completed blob access and bounded, caller-identified uploads. Partial bytes are never readable. */
trait BlobOps {

  /** Zero returns missing immediately. Positive finite timeouts wait only for an already active upload and abandon only
    * the waiter. Negative and infinite timeouts are invalid.
    */
  def readBlob(blobId: BlobId, awaitTimeout: Duration = Duration.Zero): IO[YallmoError, Blob]

  def beginBlobUpload(blobId: BlobId, totalBytes: Long): IO[YallmoError, BlobUploader]
}

/** Capability for one upload, not for any later upload reusing its blob ID. */
trait BlobUploader {
  def blobId: BlobId
  def append(offset: Long, bytes: Chunk[Byte]): IO[YallmoError, Long]
  def complete: IO[YallmoError, BlobId]
  def abort: IO[YallmoError, Unit]
}
