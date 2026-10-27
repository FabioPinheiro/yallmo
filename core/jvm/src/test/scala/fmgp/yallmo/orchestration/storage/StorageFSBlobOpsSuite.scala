package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import zio.*

final class StorageFSBlobOpsSuite extends BlobOpsSuite {
  protected def make = ZIO.attempt(Files.createTempDirectory("blob-ops-")).orDie.flatMap { root =>
    ZIO.addFinalizer(ZIO.succeed {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }) *> StorageFS.open(root)
  }
  private def runSync[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new RuntimeException("test timeout"))(10.seconds))
      .getOrThrowFiberFailure()
  }
  private def directory[A](body: Path => A): A = {
    val root = Files.createTempDirectory("blob-uploads-")
    try body(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
  test("filesystem reopen ignores abandoned partial staging files") {
    directory { root =>
      runSync(for {
        _ <- StorageFS.open(root)
        id = BlobId.fresh()
        _ <- ZIO.attempt(Files.write(root.resolve("blobs").resolve(".upload-abandoned.tmp"), Array[Byte](1)))
        reopened <- StorageFS.open(root)
        ids <- reopened.listBlobIds
        missing <- reopened.getBlob(id).either
        _ <- reopened.putBlob(Blob(id, bytes))
        duplicate <- reopened.putBlob(Blob(id, bytes.reverse)).either
        stored <- reopened.getBlob(id)
      } yield {
        assertEquals(ids, Chunk.empty)
        assertEquals(missing, Left(YallmoError.BlobMissing(id)))
        assertEquals(duplicate, Left(YallmoError.BlobAlreadyExists(id)))
        assertEquals(stored.bytes, bytes)
      })
    }
  }

  test("filesystem publish failure releases waiters; restart never exposes staged bytes") {
    directory { root =>
      runSync(for {
        storage <- StorageFS.open(root)
        id = BlobId.fresh()
        handle <- storage.beginBlobUpload(id, 3)
        dependency = handle.asInstanceOf[BlobOpsUtils.Upload].result.await
        waiter <- dependency.either.fork
        _ <- handle.append(0, bytes)
        // A directory at the target causes exclusive publication to fail after the temporary file is forced.
        _ <- ZIO.attempt(Files.createDirectory(root.resolve("blobs").resolve(id.value)))
        result <- handle.complete.either
        awaited <- waiter.join
        released <- handle.asInstanceOf[BlobOpsUtils.Upload].stagedBytes
        _ <- ZIO.attempt(Files.delete(root.resolve("blobs").resolve(id.value)))
        reopened <- StorageFS.open(root)
        missing <- reopened.getBlob(id).either
        files <- ZIO.attempt {
          val paths = Files.list(root.resolve("blobs"))
          try paths.count()
          finally paths.close()
        }
        replacement <- storage.beginBlobUpload(id, 0)
        _ <- replacement.abort
      } yield {
        assertEquals(result, failed(BlobUploadFailure.Storage))
        assertEquals(awaited, failed(BlobUploadFailure.Storage))
        assertEquals(released, 0)
        assertEquals(missing, Left(YallmoError.BlobMissing(id)))
        assertEquals(files, 0L)
      })
    }
  }

}
