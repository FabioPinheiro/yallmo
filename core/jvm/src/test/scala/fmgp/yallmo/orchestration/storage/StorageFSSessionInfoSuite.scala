package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.Storage
import java.nio.file.Files
import java.util.Comparator
import scala.concurrent.Future
import zio.*

final class StorageFSSessionInfoSuite extends StorageSessionInfoSuite {
  protected def withStorage[A](test: Storage => IO[YallmoError, A]): Future[A] =
    runStorageTest {
      ZIO.acquireReleaseWith(
        ZIO.attemptBlocking(Files.createTempDirectory("yallmo-session-info-")).mapError(YallmoError.fromThrowable)
      )(root =>
        ZIO.attemptBlocking {
          val paths = Files.walk(root)
          try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
          finally paths.close()
        }.orDie
      )(root => StorageFS.open(root).flatMap(test))
    }
}
