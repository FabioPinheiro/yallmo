package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import zio.*

/** Stateless lifecycle helpers. Each storage owns its registry; a retained terminal handle owns no staging buffer.
  * Registry transitions serialize publication but never wait for a completion promise while holding the permit.
  */
private[orchestration] object BlobOpsUtils {
  type Registry = Ref.Synchronized[Map[BlobId, Upload]]
  def registry: UIO[Registry] = Ref.Synchronized.make(Map.empty[BlobId, Upload])
  private def error(reason: BlobUploadFailure) = YallmoError.BlobUploadFailed(reason)
  private def fail(reason: BlobUploadFailure) = ZIO.fail(error(reason))

  def read(storage: Storage, registry: Registry, id: BlobId, timeout: Duration): IO[YallmoError, Blob] =
    if timeout.isNegative || timeout == Duration.Infinity then
      ZIO.fail(YallmoError.InvalidConfig("Blob await timeout must be finite and nonnegative"))
    else
      // Capture under the same registry permit as publication/removal, then release it before waiting.
      registry.modifyZIO { current =>
        storage
          .getBlob(id)
          .foldZIO(
            {
              case _: YallmoError.BlobMissing if timeout > Duration.Zero =>
                ZIO.succeed(
                  (
                    current
                      .get(id)
                      .fold[IO[YallmoError, Blob]](ZIO.fail(YallmoError.BlobMissing(id)))(upload =>
                        (upload.result.await *> storage.getBlob(id))
                          .timeoutFail(error(BlobUploadFailure.AwaitTimedOut))(timeout)
                      ),
                    current
                  )
                )
              case missing => ZIO.fail(missing)
            },
            blob => ZIO.succeed((ZIO.succeed(blob), current))
          )
      }.flatten

  def begin(
      storage: Storage,
      registry: Registry,
      id: BlobId,
      total: Long,
      nanoTime: UIO[Long] = Clock.nanoTime
  ): IO[YallmoError, BlobUploader] =
    registry.modifyZIO { current =>
      for {
        _ <- fail(BlobUploadFailure.InvalidSize).unless(total >= 0 && total <= BlobUploadLimits.MaxUploadBytes)
        _ <- ZIO.fail(YallmoError.BlobAlreadyExists(id)).when(current.contains(id))
        _ <- storage
          .getBlob(id)
          .foldZIO(
            { case _: YallmoError.BlobMissing => ZIO.unit; case e => ZIO.fail(e) },
            _ => ZIO.fail(YallmoError.BlobAlreadyExists(id))
          )
        _ <- fail(BlobUploadFailure.Capacity).when(
          current.size >= BlobUploadLimits.MaxUploads ||
            current.values.map(_.total).sum + total > BlobUploadLimits.MaxReservedBytes
        )
        now <- nanoTime
        result <- Promise.make[YallmoError, Unit]
        upload = new Upload(id, total, storage, registry, nanoTime, now, result)
        _ <- upload.watch.interruptible.forkDaemon
      } yield (upload, current.updated(id, upload))
    }.uninterruptible

  final class Upload private[BlobOpsUtils] (
      val blobId: BlobId,
      val total: Long,
      storage: Storage,
      registry: Registry,
      nanoTime: UIO[Long],
      created: Long,
      val result: Promise[YallmoError, Unit]
  ) extends BlobUploader {
    // Accessed only under this storage's registry permit, including terminal buffer release.
    private var buffer: Option[Array[Byte]] = Some(new Array[Byte](total.toInt))
    private var offset = 0
    private var touched = created
    private[orchestration] def stagedBytes: UIO[Int] = registry.modify(current => (buffer.fold(0)(_.length), current))

    private def remaining(now: Long): Long =
      math.min(
        BlobUploadLimits.IdleTimeout.toNanos - (now - touched),
        BlobUploadLimits.MaximumLifetime.toNanos - (now - created)
      )

    private def finish(outcome: Exit[YallmoError, Unit]): UIO[Unit] =
      ZIO.succeed { buffer = None } *> result.done(outcome).unit

    /** The handle's identity, rather than just BlobId, gates every transition and timer. */
    private def active(current: Map[BlobId, Upload]): Boolean = current.get(blobId).exists(_ eq this)

    private def transition[A](effect: IO[YallmoError, A]): IO[YallmoError, A] =
      registry
        .modifyZIO { current =>
          if !active(current) then fail(BlobUploadFailure.Unknown)
          else
            nanoTime.flatMap { now =>
              if remaining(now) <= 0 then
                finish(Exit.fail(error(BlobUploadFailure.Expired))).as(
                  (Left(error(BlobUploadFailure.Expired)), current - blobId)
                )
              else effect.either.map(outcome => (outcome, if buffer.isEmpty then current - blobId else current))
            }
        }
        .uninterruptible
        .flatMap(ZIO.fromEither)

    def append(at: Long, bytes: Chunk[Byte]): IO[YallmoError, Long] = transition {
      for {
        _ <- fail(BlobUploadFailure.InvalidChunk).unless(
          bytes.nonEmpty && bytes.size <= BlobUploadLimits.MaxChunkBytes &&
            at == offset.toLong && at + bytes.size <= total
        )
        now <- nanoTime
        _ <- ZIO.succeed {
          java.lang.System.arraycopy(bytes.toArray, 0, buffer.get, offset, bytes.size)
          offset += bytes.size
          touched = now
        }
      } yield offset.toLong
    }

    def complete: IO[YallmoError, BlobId] = transition {
      fail(BlobUploadFailure.Incomplete).unless(offset.toLong == total) *>
        ZIO.suspendSucceed {
          val blob = Blob(blobId, Chunk.fromArray(buffer.get))
          storage
            .putBlob(blob)
            .sandbox
            .foldZIO(
              _ => finish(Exit.fail(error(BlobUploadFailure.Storage))) *> fail(BlobUploadFailure.Storage),
              _ => finish(Exit.unit).as(blobId)
            )
        }
    }

    def abort: IO[YallmoError, Unit] = transition(finish(Exit.fail(error(BlobUploadFailure.Aborted))))

    private[orchestration] def expire: UIO[Option[Duration]] = registry.modifyZIO { current =>
      if !active(current) then ZIO.succeed((None, current))
      else
        nanoTime.flatMap { now =>
          val left = remaining(now)
          if left <= 0 then finish(Exit.fail(error(BlobUploadFailure.Expired))).as((None, current - blobId))
          else ZIO.succeed((Some(Duration.fromNanos(left)), current))
        }
    }.uninterruptible

    private[BlobOpsUtils] def watch: UIO[Unit] = {
      def loop(delay: Duration): UIO[Unit] = ZIO.sleep(delay) *> expire.flatMap {
        case None       => ZIO.unit
        case Some(left) => loop(left)
      }
      loop(BlobUploadLimits.IdleTimeout).raceFirst(result.await.ignore).unit
    }
  }
}
