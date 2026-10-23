package fmgp.yallmo.fs

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import scala.concurrent.Future
import zio.*
import zio.stream.ZStream

final class FsJsonlSuite extends FunSuite {
  test("JSONL reading supports split UTF-8, whitespace, CRLF, empty input and a final line without LF") {
    run {
      for {
        split <- reader(" \t\"héllo 🌍\" \r\n\"世界\"").read[String]("events.jsonl").runCollect
        empty <- reader("").read[Int]("events.jsonl").runCollect
        lines <- reader("1\n2\n").read[Int]("events.jsonl").runCollect
      } yield {
        assertEquals(split, Chunk("héllo 🌍", "世界"))
        assertEquals(empty, Chunk.empty[Int])
        assertEquals(lines, Chunk(1, 2))
      }
    }
  }

  test("blank, malformed and trailing-content records fail with their path and line number") {
    run {
      for {
        _ <- ZIO.foreachDiscard(List("1\nnot-json\n3", "1\n\n3", "1\n  \n3", "1\n2 3\n", "1\n2]ignored\n")) { text =>
          reader(text).read[Int]("events.jsonl").runCollect.either.map {
            case Left(FsError.InvalidJson(path, line, message)) =>
              assertEquals(path, "events.jsonl")
              assertEquals(line, 2L)
              assert(message.nonEmpty)
            case other => fail(s"Expected an InvalidJson error, got $other")
          }
        }
        blank <- reader(" \t\n").read[Option[Int]]("events.jsonl").runCollect.either
        trailing <- reader("\"first\" \"second\"\n").read[String]("events.jsonl").runCollect.either
      } yield {
        assert(blank.isLeft)
        assert(trailing.isLeft)
      }
    }
  }

  test("read errors remain typed and early termination runs the underlying finalizer") {
    val error = FsError.NotFound("missing.jsonl")
    val invalid = new TestRead(ZStream.fromChunk(Chunk('"'.toByte, 0xc3.toByte, 0x28.toByte, '"'.toByte)))
    run {
      for {
        missing <- new TestRead(ZStream.fail(error)).read[Int]("missing.jsonl").runCollect.either
        _ = assertEquals(missing, Left(error))
        invalidResult <- invalid.read[String]("events.jsonl").runCollect.either
        _ = invalidResult match {
          case Left(FsError.IoFailure(path, _)) => assertEquals(path, "events.jsonl")
          case other                            => fail(s"Expected a UTF-8 I/O error, got $other")
        }
        closed <- Ref.make(false)
        bytes = ZStream.unwrapScoped {
          ZIO
            .acquireRelease(ZIO.unit)(_ => closed.set(true))
            .as(ZStream.fromIterable("1\n2\n3\n".getBytes(UTF_8)).rechunk(1))
        }
        values <- new TestRead(bytes).read[Int]("events.jsonl").take(1).runCollect
        isClosed <- closed.get
      } yield assertEquals((values, isClosed), (Chunk(1), true))
    }
  }

  test("commit policies count records, not chunks, and commit the final partial batch") {
    run {
      for {
        fs <- TestWrite.make()
        _ <- ZStream(1, 2, 3).run(fs.write[Int]("events.jsonl", WriteMode.Append))
        default <- fs.log.get
        _ = {
          assertEquals(default.syncs, Vector(1, 2, 3))
          assertEquals(default.flushes, Vector.empty[Int])
        }
        _ <- ZIO.foreachDiscard(
          List(
            (CommitPolicy.Every(2, Durability.Sync), Vector(2, 4, 5), Vector.empty[Int]),
            (CommitPolicy.Every(2, Durability.Flush), Vector.empty[Int], Vector(2, 4, 5)),
            (CommitPolicy.Buffered, Vector.empty[Int], Vector(5))
          )
        ) { (policy, syncs, flushes) =>
          ZIO.foreachDiscard(List(1, 5)) { chunkSize =>
            writeLog(ZStream.fromIterable(1 to 5).rechunk(chunkSize), policy).map { log =>
              assertEquals(log.syncs, syncs)
              assertEquals(log.flushes, flushes)
              assertEquals(log.writes.size, 5)
              assertEquals(log.opens, Vector(WriteMode.Append))
              assertEquals(log.closes, 1)
            }
          }
        }
      } yield ()
    }
  }

  test("records are written before the next upstream chunk is requested") {
    run {
      for {
        fs <- TestWrite.make()
        next = ZStream.fromZIO {
          for {
            log <- fs.log.get
            _ <- ZIO.fail("Input was collected before writing").unless(log.writes.nonEmpty)
          } yield 2
        }
        _ <- (ZStream(1) ++ next).run(fs.write[Int]("events.jsonl", WriteMode.Append))
        log <- fs.log.get
      } yield {
        assertEquals(log.writes.size, 2)
        assertEquals(log.syncs, Vector(1, 2))
      }
    }
  }

  test("sink construction is lazy and each run gets fresh commit state and a scoped writer") {
    run {
      for {
        fs <- TestWrite.make()
        sink = fs.write[Int]("events.jsonl", WriteMode.Append, CommitPolicy.Every(2, Durability.Sync))
        before <- fs.log.get
        _ <- ZStream(1).run(sink)
        _ <- ZStream(2, 3).run(sink)
        after <- fs.log.get
      } yield {
        assertEquals(before, Log())
        assertEquals(after.syncs, Vector(1, 3))
        assertEquals(after.opens, Vector(WriteMode.Append, WriteMode.Append))
        assertEquals(after.closes, 2)
      }
    }
  }

  test("empty input opens the requested mode; invalid batches and open failures do not consume input") {
    run {
      for {
        fs <- TestWrite.make()
        _ <- ZStream.empty.run(fs.write[Int]("events.jsonl", WriteMode.Replace))
        empty <- fs.log.get
        _ = {
          assertEquals(empty.opens, Vector(WriteMode.Replace))
          assertEquals(empty.closes, 1)
          assertEquals(empty.writes, Vector.empty[String])
        }
        _ <- ZIO.foreachDiscard(List(0, -1)) { size =>
          for {
            fs <- TestWrite.make()
            consumed <- Ref.make(false)
            input = ZStream.fromZIO(consumed.set(true).as(1))
            outcome <- input
              .run(fs.write[Int]("events.jsonl", WriteMode.Append, CommitPolicy.Every(size, Durability.Sync)))
              .either
            log <- fs.log.get
            didConsume <- consumed.get
          } yield assertEquals(
            (outcome, log, didConsume),
            (Left(FsError.InvalidArgument("Commit batch size must be positive")), Log(), false)
          )
        }
        failingFs <- TestWrite.make(Some("open"))
        consumed <- Ref.make(false)
        outcome <- ZStream
          .fromZIO(consumed.set(true).as(1))
          .run(failingFs.write[Int]("events.jsonl", WriteMode.Append))
          .either
        didConsume <- consumed.get
      } yield assertEquals((outcome, didConsume), (Left(FsError.IoFailure("events.jsonl", "open")), false))
    }
  }

  test("upstream failure and interruption close the writer without committing a partial batch") {
    run {
      for {
        fs <- TestWrite.make()
        outcome <- (ZStream(1) ++ ZStream.fail("upstream"))
          .run(
            fs.write[Int]("events.jsonl", WriteMode.Append, CommitPolicy.Every(2, Durability.Sync))
          )
          .either
        failed <- fs.log.get
        _ = {
          assertEquals(outcome, Left("upstream"))
          assertEquals(failed.writes.size, 1)
          assertEquals(failed.syncs, Vector.empty[Int])
          assertEquals(failed.closes, 1)
        }
        interruptedFs <- TestWrite.make()
        waiting <- Promise.make[Nothing, Unit]
        input = ZStream(1) ++ ZStream.fromZIO(waiting.succeed(())).drain ++ ZStream.never
        fiber <- input
          .run(interruptedFs.write[Int]("events.jsonl", WriteMode.Append, CommitPolicy.Every(2, Durability.Sync)))
          .fork
        _ <- waiting.await
        _ <- fiber.interrupt
        interrupted <- interruptedFs.log.get
      } yield {
        assertEquals(interrupted.writes.size, 1)
        assertEquals(interrupted.syncs, Vector.empty[Int])
        assertEquals(interrupted.closes, 1)
      }
    }
  }

  test("write, flush and sync failures remain typed and still close the writer") {
    run {
      ZIO.foreachDiscard(
        List(
          ("write", CommitPolicy.Buffered),
          ("flush", CommitPolicy.Buffered),
          ("sync", CommitPolicy.Every(1, Durability.Sync)),
          ("sync", CommitPolicy.Every(2, Durability.Sync))
        )
      ) { (operation, policy) =>
        for {
          fs <- TestWrite.make(Some(operation))
          outcome <- ZStream(1).run(fs.write[Int]("events.jsonl", WriteMode.Append, policy)).either
          log <- fs.log.get
        } yield {
          assertEquals(outcome, Left(FsError.IoFailure("events.jsonl", operation)))
          assertEquals(log.closes, 1)
        }
      }
    }
  }

  private def reader(text: String): TestRead =
    new TestRead(ZStream.fromIterable(text.getBytes(UTF_8)).rechunk(1))

  private final class TestRead(bytes: ZStream[Any, FsError, Byte]) extends FsJsonlRead {
    override def readBytes(path: String): ZStream[Any, FsError, Byte] = bytes
    override def list(path: String): IO[FsError, Chunk[String]] = unused
  }

  private final case class Log(
      writes: Vector[String] = Vector.empty,
      flushes: Vector[Int] = Vector.empty,
      syncs: Vector[Int] = Vector.empty,
      opens: Vector[WriteMode] = Vector.empty,
      closes: Int = 0
  )

  private final class TestWrite(val log: Ref[Log], failAt: Option[String]) extends FsJsonlWrite {
    private def record(operation: String)(update: Log => Log): IO[FsError, Unit] =
      if failAt.contains(operation) then ZIO.fail(FsError.IoFailure("events.jsonl", operation)) else log.update(update)

    override def openUtf8Writer(path: String, mode: WriteMode): ZIO[Scope, FsError, FsUtf8Writer] =
      ZIO.acquireRelease {
        record("open")(value => value.copy(opens = value.opens :+ mode)).as(new FsUtf8Writer {
          override def write(text: String): IO[FsError, Unit] =
            record("write")(value => value.copy(writes = value.writes :+ text))
          override def flush: IO[FsError, Unit] =
            record("flush")(value => value.copy(flushes = value.flushes :+ value.writes.size))
          override def sync: IO[FsError, Unit] =
            record("sync")(value => value.copy(syncs = value.syncs :+ value.writes.size))
        })
      }(_ => log.update(value => value.copy(closes = value.closes + 1)))

    override def writeBytes[R, E](
        path: String,
        bytes: ZStream[R, E, Byte],
        mode: WriteMode
    ): ZIO[R, E | FsError, Unit] = unused
    override def createDirectories(path: String): IO[FsError, Unit] = unused
    override def deleteFile(path: String): IO[FsError, Unit] = unused
    override def deleteDirectory(path: String, mode: DirectoryDeleteMode): IO[FsError, Unit] = unused
    override def move(source: String, destination: String, mode: MoveMode): IO[FsError, Unit] = unused
  }

  private object TestWrite {
    def make(failAt: Option[String] = None): UIO[TestWrite] = Ref.make(Log()).map(new TestWrite(_, failAt))
  }

  private def writeLog(input: ZStream[Any, Nothing, Int], policy: CommitPolicy): IO[FsError, Log] = {
    for {
      fs <- TestWrite.make()
      _ <- input.run(fs.write[Int]("events.jsonl", WriteMode.Append, policy))
      log <- fs.log.get
    } yield log
  }

  private def unused: IO[FsError, Nothing] = ZIO.fail(FsError.InvalidArgument("Unexpected test operation"))

  private def run[E, A](effect: ZIO[Any, E, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.mapError(error => FiberFailure(Cause.fail(error))))
    }
}
