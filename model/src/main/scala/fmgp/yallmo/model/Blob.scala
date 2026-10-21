package fmgp.yallmo.model

import java.util.UUID
import scala.util.Try
import zio.{Chunk, IO, ZIO}
import zio.json.JsonCodec

/** Generated identity, independent of content and safe as a filesystem path segment. */
opaque type BlobId = String
object BlobId {
  def from(value: String): Either[String, BlobId] =
    Try(UUID.fromString(value)).toEither.left.map(_ => "blob id must be a canonical UUID").flatMap { uuid =>
      Either.cond(uuid.toString == value, value, "blob id must be a canonical lowercase UUID")
    }

  /** Allocates identity only, without writing content or contacting Storage. */
  def fresh(): BlobId = UUID.randomUUID().toString
  extension (id: BlobId) def value: String = id
  given JsonCodec[BlobId] = JsonCodec.string.transformOrFail(from, _.value)
}

/** Stored bytes and their identity; not part of a MessagePayload. No lifecycle or integrity metadata is stored. */
final case class Blob(id: BlobId, bytes: Chunk[Byte]) {
  override def toString: String = s"Blob(id=$id)"
}

object Blob {

  /** Default read function for direct adapter calls without a Storage binding. */
  val unavailable: BlobId => IO[YallmoError, Blob] = blobId => ZIO.fail(YallmoError.BlobMissing(blobId))
}
