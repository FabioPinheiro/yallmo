package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import munit.FunSuite
import scala.concurrent.Future
import zio.*

abstract class BlobOpsSuite extends FunSuite {
  protected def make: ZIO[Scope, YallmoError, Storage]
  protected val bytes = Chunk[Byte](1, 2, 3)
  protected def failed(reason: BlobUploadFailure) = Left(YallmoError.BlobUploadFailed(reason))
  protected def run[A](effect: ZIO[Any, Any, A]): Future[A] = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe.runToFuture(
      effect
        .mapError(error => new RuntimeException(error.toString))
        .timeoutFail(new RuntimeException("test timeout"))(10.seconds)
    )
  }
  private def check[A](body: Storage => IO[Any, A]): Future[A] = run(ZIO.scoped(make.flatMap(body)))
  private def internal(u: BlobUploader): BlobOpsUtils.Upload = u.asInstanceOf[BlobOpsUtils.Upload]
  private def suspended[E, A](fiber: Fiber.Runtime[E, A]): UIO[Unit] =
    (ZIO.yieldNow *> fiber.status).repeatUntil(_.isInstanceOf[Fiber.Status.Suspended]).unit

  test("completed-only reads, positive waiting, empty uploads and duplicate rejection") {
    check { storage =>
      for {
        id <- ZIO.succeed(BlobId.fresh())
        upload <- storage.beginBlobUpload(id, 960044)
        duplicate <- storage.beginBlobUpload(id, 0).either
        waiter <- storage.readBlob(id, BlobUploadLimits.AwaitTimeout).fork
        _ <- suspended(waiter)
        missing <- storage.readBlob(id).either
        listed <- storage.listBlobIds
        _ <- ZIO.foreachDiscard(0 until 960044 by 65536)(offset =>
          upload.append(offset, Chunk.fill((960044 - offset).min(65536))(42.toByte))
        )
        pending <- waiter.poll
        completed <- upload.complete
        blob <- waiter.join
        immediate <- storage.readBlob(id, 1.second)
        again <- storage.beginBlobUpload(id, 0).either
        late <- upload.append(960044, bytes).either
        sessions <- storage.listSessions
        messages <- storage.listMessages
        empty <- storage.beginBlobUpload(BlobId.fresh(), 0)
        _ <- empty.complete
        emptyBlob <- storage.readBlob(empty.blobId)
        released <- internal(upload).stagedBytes
      } yield {
        assertEquals(upload.blobId, id)
        assertEquals(duplicate, Left(YallmoError.BlobAlreadyExists(id)))
        assertEquals(missing, Left(YallmoError.BlobMissing(id)))
        assertEquals(listed, Chunk.empty)
        assertEquals(pending, None)
        assertEquals(completed, id)
        assertEquals(blob.bytes.size, 960044)
        assertEquals(immediate, blob)
        assertEquals(again, Left(YallmoError.BlobAlreadyExists(id)))
        assertEquals(late, failed(BlobUploadFailure.Unknown))
        assertEquals(sessions, Chunk.empty)
        assertEquals(messages, Chunk.empty)
        assertEquals(emptyBlob.bytes, Chunk.empty)
        assertEquals(released, 0)
      }
    }
  }

  test("unknown IDs fail immediately; invalid timeouts are explicit") {
    check { storage =>
      for {
        id <- ZIO.succeed(BlobId.fresh())
        unknown <- storage.readBlob(id, BlobUploadLimits.AwaitTimeout).either
        negative <- storage.readBlob(id, Duration.fromSeconds(-1)).either
        infinite <- storage.readBlob(id, Duration.Infinity).either
        bad <- storage.beginBlobUpload(id, -1).either
        huge <- storage.beginBlobUpload(id, BlobUploadLimits.MaxUploadBytes.toLong + 1).either
      } yield {
        assertEquals(unknown, Left(YallmoError.BlobMissing(id)))
        assert(negative.isLeft)
        assert(infinite.isLeft)
        assertEquals(bad, failed(BlobUploadFailure.InvalidSize))
        assertEquals(huge, failed(BlobUploadFailure.InvalidSize))
      }
    }
  }

  test("rejected chunks and incomplete Complete leave staging unpublished") {
    check { storage =>
      for {
        upload <- storage.beginBlobUpload(BlobId.fresh(), 3)
        offset <- upload.append(1, bytes).either
        overrun <- upload.append(0, bytes ++ bytes).either
        oversized <- upload.append(0, Chunk.fill(BlobUploadLimits.MaxChunkBytes + 1)(0.toByte)).either
        empty <- upload.append(0, Chunk.empty).either
        incomplete <- upload.complete.either
        _ <- upload.append(0, bytes.take(1))
        missing <- storage.readBlob(upload.blobId).either
        _ <- upload.abort
        released <- internal(upload).stagedBytes
        listed <- storage.listBlobIds
      } yield {
        List(offset, overrun, oversized, empty).foreach(value =>
          assertEquals(value, failed(BlobUploadFailure.InvalidChunk))
        )
        assertEquals(incomplete, failed(BlobUploadFailure.Incomplete))
        assertEquals(missing, Left(YallmoError.BlobMissing(upload.blobId)))
        assertEquals(released, 0)
        assertEquals(listed, Chunk.empty)
      }
    }
  }

  test("count and byte reservations release even while terminal handles are retained") {
    check { storage =>
      for {
        empty <- ZIO.foreach(0 until BlobUploadLimits.MaxUploads)(_ => storage.beginBlobUpload(BlobId.fresh(), 0))
        count <- storage.beginBlobUpload(BlobId.fresh(), 0).either
        _ <- empty.head.abort
        replacement <- storage.beginBlobUpload(BlobId.fresh(), 0)
        _ <- ZIO.foreachDiscard(empty.tail)(_.abort)
        _ <- replacement.abort
        reserved <- ZIO.foreach(0L until BlobUploadLimits.MaxReservedBytes by BlobUploadLimits.MaxUploadBytes.toLong)(
          _ => storage.beginBlobUpload(BlobId.fresh(), BlobUploadLimits.MaxUploadBytes)
        )
        capacity <- storage.beginBlobUpload(BlobId.fresh(), 1).either
        _ <- reserved.head.abort
        released <- internal(reserved.head).stagedBytes
        reused <- storage.beginBlobUpload(BlobId.fresh(), BlobUploadLimits.MaxUploadBytes)
        _ <- ZIO.foreachDiscard(reserved.tail)(_.abort)
        _ <- reused.abort
      } yield {
        assertEquals(count, failed(BlobUploadFailure.Capacity))
        assertEquals(capacity, failed(BlobUploadFailure.Capacity))
        assertEquals(released, 0)
      }
    }
  }

  test("concurrent Begin claims once; Complete/Abort and conflicting chunks serialize") {
    check { storage =>
      for {
        id <- ZIO.succeed(BlobId.fresh())
        claims <- storage.beginBlobUpload(id, 3).either.zipPar(storage.beginBlobUpload(id, 3).either)
        upload = claims._1.toOption.orElse(claims._2.toOption).get
        appends <- upload.append(0, bytes).either.zipPar(upload.append(0, bytes).either)
        terminal <- upload.complete.either.zipPar(upload.abort.either)
        blob <- storage.readBlob(id).either
        released <- internal(upload).stagedBytes
      } yield {
        assertEquals(List(claims._1, claims._2).count(_.isRight), 1)
        assertEquals(List(appends._1, appends._2).count(_.isRight), 1)
        assertEquals(List(terminal._1, terminal._2).count(_.isRight), 1)
        assertEquals(blob.isRight, terminal._1.isRight)
        assertEquals(released, 0)
      }
    }
  }

  for expired <- List(false, true) do
    test(s"${if expired then "expiry" else "abort"} binds old handles, timers and waiters to original state") {
      check { storage =>
        for {
          registry <- BlobOpsUtils.registry
          clock <- Ref.make(0L)
          id <- ZIO.succeed(BlobId.fresh())
          first <- BlobOpsUtils.begin(storage, registry, id, 3, clock.get)
          previous = internal(first).result.await
          _ <- first.append(0, bytes.take(1))
          _ <- if expired then clock.set(10.seconds.toNanos) *> internal(first).expire.unit else first.abort
          next <- BlobOpsUtils.begin(storage, registry, id, 0, clock.get)
          stale <- first.abort.either
          staleWrite <- first.append(1, bytes.take(1)).either
          staleComplete <- first.complete.either
          _ <- internal(first).expire
          released <- internal(first).stagedBytes
          completed <- next.complete
          oldResult <- previous.either
          newResult <- internal(next).result.await *> storage.readBlob(id)
        } yield {
          assertEquals(stale, failed(BlobUploadFailure.Unknown))
          assertEquals(staleWrite, failed(BlobUploadFailure.Unknown))
          assertEquals(staleComplete, failed(BlobUploadFailure.Unknown))
          assertEquals(released, 0)
          assertEquals(oldResult, failed(if expired then BlobUploadFailure.Expired else BlobUploadFailure.Aborted))
          assertEquals(completed, id)
          assertEquals(newResult, Blob(id, Chunk.empty))
        }
      }
    }

  test("idle is 10 seconds; rejected chunks do not refresh it") {
    check { storage =>
      for {
        registry <- BlobOpsUtils.registry
        clock <- Ref.make(0L)
        upload <- BlobOpsUtils.begin(storage, registry, BlobId.fresh(), 3, clock.get)
        _ <- clock.set(9.seconds.toNanos)
        _ <- upload.append(1, bytes).either
        _ <- clock.set(10.seconds.toNanos)
        _ <- internal(upload).expire
        result <- internal(upload).result.await.either
        released <- internal(upload).stagedBytes
        replacement <- BlobOpsUtils.begin(storage, registry, upload.blobId, 0, clock.get)
        _ <- replacement.abort
      } yield {
        assertEquals(result, failed(BlobUploadFailure.Expired))
        assertEquals(released, 0)
      }
    }
  }

  test("accepted chunks move only the idle deadline") {
    check { storage =>
      for {
        registry <- BlobOpsUtils.registry
        clock <- Ref.make(0L)
        upload <- BlobOpsUtils.begin(storage, registry, BlobId.fresh(), 3, clock.get)
        _ <- clock.set(9.seconds.toNanos)
        _ <- upload.append(0, bytes.take(1))
        _ <- clock.set(10.seconds.toNanos)
        left <- internal(upload).expire
        _ <- clock.set(19.seconds.toNanos)
        _ <- internal(upload).expire
        result <- internal(upload).result.await.either
      } yield {
        assertEquals(left, Some(9.seconds))
        assertEquals(result, failed(BlobUploadFailure.Expired))
      }
    }
  }

  for completed <- List(false, true) do
    test(s"${if completed then "Complete" else "Abort"} stops the sleeping timer") {
      check { storage =>
        for {
          registry <- BlobOpsUtils.registry
          sleeping <- Promise.make[Nothing, Unit]
          stopped <- Promise.make[Nothing, Unit]
          clock = new Clock {
            export Clock.ClockLive.{
              currentTime,
              currentDateTime,
              javaClock,
              localDateTime,
              nanoTime,
              scheduler,
              instant
            }
            def sleep(duration: => Duration)(using Trace): UIO[Unit] = {
              val _ = duration
              (sleeping.succeed(()) *> ZIO.never).ensuring(stopped.succeed(()).unit)
            }
          }
          upload <- BlobOpsUtils.begin(storage, registry, BlobId.fresh(), 0).withClock(clock)
          _ <- sleeping.await
          _ <- if completed then upload.complete.unit else upload.abort
          _ <- stopped.await
          remaining <- registry.get
        } yield assertEquals(remaining.size, 0)
      }
    }

  test("accepted chunks refresh idle but cannot extend the absolute 60-second deadline") {
    check { storage =>
      for {
        registry <- BlobOpsUtils.registry
        clock <- Ref.make(0L)
        upload <- BlobOpsUtils.begin(storage, registry, BlobId.fresh(), 10, clock.get)
        _ <- ZIO.foreachDiscard(1 to 6) { n =>
          clock.set(n * 9.seconds.toNanos) *> upload.append(n - 1, bytes.take(1)) *> internal(upload).expire
        }
        before <- internal(upload).result.poll
        _ <- clock.set(60.seconds.toNanos)
        _ <- internal(upload).expire
        result <- internal(upload).result.await.either
        released <- internal(upload).stagedBytes
      } yield {
        assertEquals(before, None)
        assertEquals(result, failed(BlobUploadFailure.Expired))
        assertEquals(released, 0)
      }
    }
  }

  test("automatic no-traffic expiry needs no sweep and stops its timer") {
    check { storage =>
      for {
        registry <- BlobOpsUtils.registry
        time <- Ref.make(0L)
        sleeping <- Promise.make[Nothing, Unit]
        wake <- Promise.make[Nothing, Unit]
        clock = new Clock {
          export Clock.ClockLive.{currentTime, currentDateTime, javaClock, localDateTime, scheduler, instant}
          def nanoTime(using Trace): UIO[Long] = time.get
          def sleep(duration: => Duration)(using Trace): UIO[Unit] =
            ZIO.succeed(assertEquals(duration, 10.seconds)) *> sleeping.succeed(()) *> wake.await
        }
        upload <- BlobOpsUtils.begin(storage, registry, BlobId.fresh(), 3, time.get).withClock(clock)
        _ <- sleeping.await
        _ <- time.set(10.seconds.toNanos)
        _ <- wake.succeed(())
        result <- internal(upload).result.await.either
        released <- internal(upload).stagedBytes
        active <- registry.get
      } yield {
        assertEquals(result, failed(BlobUploadFailure.Expired))
        assertEquals(released, 0)
        assertEquals(active.size, 0)
      }
    }
  }

  test("wait timeout abandons only the waiter, not the upload") {
    check { storage =>
      for {
        upload <- storage.beginBlobUpload(BlobId.fresh(), 0)
        clock = new Clock {
          export Clock.ClockLive.{currentTime, currentDateTime, javaClock, localDateTime, nanoTime, scheduler, instant}
          def sleep(duration: => Duration)(using Trace): UIO[Unit] =
            ZIO.succeed(assertEquals(duration, BlobUploadLimits.AwaitTimeout))
        }
        result <- storage.readBlob(upload.blobId, BlobUploadLimits.AwaitTimeout).withClock(clock).either
        completed <- upload.complete
      } yield {
        assertEquals(result, failed(BlobUploadFailure.AwaitTimedOut))
        assertEquals(completed, upload.blobId)
      }
    }
  }

  test("one-byte chunks preserve sequential content without a chunk-node chain") {
    check { storage =>
      for {
        upload <- storage.beginBlobUpload(BlobId.fresh(), 1024)
        _ <- ZIO.foreachDiscard(0 until 1024)(offset => upload.append(offset, Chunk.single(offset.toByte)))
        _ <- upload.complete
        blob <- storage.readBlob(upload.blobId)
      } yield assertEquals(blob.bytes, Chunk.fromIterable((0 until 1024).map(_.toByte)))
    }
  }
}

final class StorageInMemoryBlobOpsSuite extends BlobOpsSuite {
  protected def make = StorageInMemory.make
}
