package fmgp.yallmo.fs

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import zio.*
import zio.stream.ZStream

final class FsJsonlSuite extends FunSuite {
  test("JSONL reading supports split UTF-8, whitespace, CRLF, empty input and a final line without LF") {
    assertEquals(
      run(reader(" \t\"héllo 🌍\" \r\n\"世界\"").read[String]("events.jsonl").runCollect),
      Chunk("héllo 🌍", "世界")
    )
    assertEquals(run(reader("").read[Int]("events.jsonl").runCollect), Chunk.empty[Int])
    assertEquals(run(reader("1\n2\n").read[Int]("events.jsonl").runCollect), Chunk(1, 2))
  }

  test("blank, malformed and trailing-content records fail with their path and line number") {
    for text <- List("1\nnot-json\n3", "1\n\n3", "1\n  \n3", "1\n2 3\n", "1\n2]ignored\n") do {
      run(reader(text).read[Int]("events.jsonl").runCollect.either) match {
        case Left(FsError.InvalidJson(path, line, message)) =>
          assertEquals(path, "events.jsonl")
          assertEquals(line, 2L)
          assert(message.nonEmpty)
        case other => fail(s"Expected an InvalidJson error, got $other")
      }
    }
    assert(run(reader(" \t\n").read[Option[Int]]("events.jsonl").runCollect.either).isLeft)
    assert(run(reader("\"first\" \"second\"\n").read[String]("events.jsonl").runCollect.either).isLeft)
  }

  test("read errors remain typed and early termination runs the underlying finalizer") {
    val error = FsError.NotFound("missing.jsonl")
    assertEquals(run(new TestRead(ZStream.fail(error)).read[Int]("missing.jsonl").runCollect.either), Left(error))
    val invalid = new TestRead(ZStream.fromChunk(Chunk('"'.toByte, 0xc3.toByte, 0x28.toByte, '"'.toByte)))
    run(invalid.read[String]("events.jsonl").runCollect.either) match {
      case Left(FsError.IoFailure(path, _)) => assertEquals(path, "events.jsonl")
      case other                            => fail(s"Expected a UTF-8 I/O error, got $other")
    }
    val result = run {
      for {
        closed <- Ref.make(false)
        bytes = ZStream.unwrapScoped {
          ZIO
            .acquireRelease(ZIO.unit)(_ => closed.set(true))
            .as(ZStream.fromIterable("1\n2\n3\n".getBytes(UTF_8)).rechunk(1))
        }
        values <- new TestRead(bytes).read[Int]("events.jsonl").take(1).runCollect
        isClosed <- closed.get
      } yield (values, isClosed)
    }
    assertEquals(result, (Chunk(1), true))
  }

  test("commit policies count records, not chunks, and commit the final partial batch") {
    val default = run {
      for {
        fs <- TestWrite.make()
        _ <- ZStream(1, 2, 3).run(fs.write[Int]("events.jsonl", WriteMode.Append))
        log <- fs.log.get
      } yield log
    }
    assertEquals(default.syncs, Vector(1, 2, 3))
    assertEquals(default.flushes, Vector.empty[Int])
    for {
      (policy, syncs, flushes) <- List(
        (CommitPolicy.Every(2, Durability.Sync), Vector(2, 4, 5), Vector.empty[Int]),
        (CommitPolicy.Every(2, Durability.Flush), Vector.empty[Int], Vector(2, 4, 5)),
        (CommitPolicy.Buffered, Vector.empty[Int], Vector(5))
      )
      chunkSize <- List(1, 5)
    } {
      val log = writeLog(ZStream.fromIterable(1 to 5).rechunk(chunkSize), policy)
      assertEquals(log.syncs, syncs)
      assertEquals(log.flushes, flushes)
      assertEquals(log.writes.size, 5)
      assertEquals(log.opens, Vector(WriteMode.Append))
      assertEquals(log.closes, 1)
    }
  }

  test("records are written before the next upstream chunk is requested") {
    val log = run {
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
      } yield log
    }
    assertEquals(log.writes.size, 2)
    assertEquals(log.syncs, Vector(1, 2))
  }

  test("sink construction is lazy and each run gets fresh commit state and a scoped writer") {
    val result = run {
      for {
        fs <- TestWrite.make()
        sink = fs.write[Int]("events.jsonl", WriteMode.Append, CommitPolicy.Every(2, Durability.Sync))
        before <- fs.log.get
        _ <- ZStream(1).run(sink)
        _ <- ZStream(2, 3).run(sink)
        after <- fs.log.get
      } yield (before, after)
    }
    assertEquals(result._1, Log())
    assertEquals(result._2.syncs, Vector(1, 3))
    assertEquals(result._2.opens, Vector(WriteMode.Append, WriteMode.Append))
    assertEquals(result._2.closes, 2)
  }

  test("empty input opens the requested mode; invalid batches and open failures do not consume input") {
    val empty = run {
      for {
        fs <- TestWrite.make()
        _ <- ZStream.empty.run(fs.write[Int]("events.jsonl", WriteMode.Replace))
        log <- fs.log.get
      } yield log
    }
    assertEquals(empty.opens, Vector(WriteMode.Replace))
    assertEquals(empty.closes, 1)
    assertEquals(empty.writes, Vector.empty[String])
    for size <- List(0, -1) do {
      val result = run {
        for {
          fs <- TestWrite.make()
          consumed <- Ref.make(false)
          input = ZStream.fromZIO(consumed.set(true).as(1))
          outcome <- input
            .run(fs.write[Int]("events.jsonl", WriteMode.Append, CommitPolicy.Every(size, Durability.Sync)))
            .either
          log <- fs.log.get
          didConsume <- consumed.get
        } yield (outcome, log, didConsume)
      }
      assertEquals(result, (Left(FsError.InvalidArgument("Commit batch size must be positive")), Log(), false))
    }
    val failed = run {
      for {
        fs <- TestWrite.make(Some("open"))
        consumed <- Ref.make(false)
        outcome <- ZStream.fromZIO(consumed.set(true).as(1)).run(fs.write[Int]("events.jsonl", WriteMode.Append)).either
        didConsume <- consumed.get
      } yield (outcome, didConsume)
    }
    assertEquals(failed, (Left(FsError.IoFailure("events.jsonl", "open")), false))
  }

  test("upstream failure and interruption close the writer without committing a partial batch") {
    val failed = run {
      for {
        fs <- TestWrite.make()
        outcome <- (ZStream(1) ++ ZStream.fail("upstream"))
          .run(
            fs.write[Int]("events.jsonl", WriteMode.Append, CommitPolicy.Every(2, Durability.Sync))
          )
          .either
        log <- fs.log.get
      } yield (outcome, log)
    }
    assertEquals(failed._1, Left("upstream"))
    assertEquals(failed._2.writes.size, 1)
    assertEquals(failed._2.syncs, Vector.empty[Int])
    assertEquals(failed._2.closes, 1)
    val interrupted = run {
      for {
        fs <- TestWrite.make()
        waiting <- Promise.make[Nothing, Unit]
        input = ZStream(1) ++ ZStream.fromZIO(waiting.succeed(())).drain ++ ZStream.never
        fiber <- input.run(fs.write[Int]("events.jsonl", WriteMode.Append, CommitPolicy.Every(2, Durability.Sync))).fork
        _ <- waiting.await
        _ <- fiber.interrupt
        log <- fs.log.get
      } yield log
    }
    assertEquals(interrupted.writes.size, 1)
    assertEquals(interrupted.syncs, Vector.empty[Int])
    assertEquals(interrupted.closes, 1)
  }

  test("write, flush and sync failures remain typed and still close the writer") {
    for (operation, policy) <- List(
        ("write", CommitPolicy.Buffered),
        ("flush", CommitPolicy.Buffered),
        ("sync", CommitPolicy.Every(1, Durability.Sync)),
        ("sync", CommitPolicy.Every(2, Durability.Sync))
      )
    do {
      val result = run {
        for {
          fs <- TestWrite.make(Some(operation))
          outcome <- ZStream(1).run(fs.write[Int]("events.jsonl", WriteMode.Append, policy)).either
          log <- fs.log.get
        } yield (outcome, log)
      }
      assertEquals(result._1, Left(FsError.IoFailure("events.jsonl", operation)))
      assertEquals(result._2.closes, 1)
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

  private def writeLog(input: ZStream[Any, Nothing, Int], policy: CommitPolicy): Log = run {
    for {
      fs <- TestWrite.make()
      _ <- input.run(fs.write[Int]("events.jsonl", WriteMode.Append, policy))
      log <- fs.log.get
    } yield log
  }

  private def unused: IO[FsError, Nothing] = ZIO.fail(FsError.InvalidArgument("Unexpected test operation"))

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
