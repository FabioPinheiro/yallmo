package fmgp.yallmo.fs

import zio.{IO, Ref, ZIO}
import zio.json.{EncoderOps, JsonDecoder, JsonEncoder, JsonError}
import zio.json.internal.RetractReader
import zio.stream.{ZPipeline, ZSink, ZStream}

/** Incremental JSONL decoding built only from an explicitly supplied FsRead capability.
  *
  * Each line must contain one JSON value; blank or malformed lines fail with InvalidJson and a 1-based line number. A
  * final record without a terminating LF is accepted.
  */
trait FsJsonlRead extends FsRead {
  def read[A: JsonDecoder](path: String): ZStream[Any, FsError, A] = {
    val decoder = FsJsonlRead.strictDecoder[A]
    readBytes(path)
      .via(ZPipeline.utf8Decode.mapError(error => FsError.IoFailure(path, error.getMessage)))
      .via(ZPipeline.splitLines)
      .zipWithIndex
      .mapZIO { case (line, index) =>
        // A whitespace delimiter keeps numeric decoders from retracting past EOF before the end check.
        ZIO
          .fromEither(decoder.decodeJson(line + " ").flatten)
          .mapError(message => FsError.InvalidJson(path, index + 1, message))
      }
  }
}

object FsJsonlRead {
  // zio-json 1.1.1 decodeJson intentionally permits trailing input. Keep the low-level end check isolated here.
  private def strictDecoder[A](using decoder: JsonDecoder[A]): JsonDecoder[Either[String, A]] =
    new JsonDecoder[Either[String, A]] {
      override def unsafeDecode(trace: List[JsonError], in: RetractReader): Either[String, A] = {
        val value = decoder.unsafeDecode(trace, in)
        var next = in.read()
        while next == ' ' || next == '\t' || next == '\r' || next == '\n' do next = in.read()
        if next != -1 then Left("Unexpected trailing content after JSON value") else Right(value)
      }
    }
}

/** Streaming JSONL encoding built only from an explicitly supplied FsWrite capability. */
trait FsJsonlWrite extends FsWrite {

  /** A sink that incrementally encodes each element as compact JSON followed by LF, with no leftovers.
    *
    * Construction performs no I/O. Each run validates the path and acquires one scoped writer, closing it on
    * completion, failure or interruption. Even empty input applies the requested open mode. State is fresh for every
    * run, and input is consumed with backpressure rather than collected in full.
    *
    * Use stream.run(write[A](path, mode, commit)); running the stream preserves its environment and upstream errors.
    * CommitPolicy.Every counts encoded records, not stream chunks, and commits the final partial batch on successful
    * completion. Invalid batch sizes fail with InvalidArgument when run. By default, every record is synchronized.
    *
    * Writes are not transactions: failure or interruption may leave a partial record or partially committed batch.
    * Different writers/processes are not coordinated.
    */
  def write[A: JsonEncoder](
      path: String,
      mode: WriteMode,
      commit: CommitPolicy = CommitPolicy.Every(1, Durability.Sync)
  ): ZSink[Any, FsError, A, Nothing, Unit] =
    ZSink.unwrapScoped {
      for {
        _ <- commit match {
          case CommitPolicy.Every(elements, _) if elements <= 0 =>
            ZIO.fail(FsError.InvalidArgument("Commit batch size must be positive"))
          case _ => ZIO.unit
        }
        writer <- openUtf8Writer(path, mode)
        pending <- Ref.make(0)
      } yield {
        def commitBatch(durability: Durability): IO[FsError, Unit] =
          durability match {
            case Durability.Flush => writer.flush
            case Durability.Sync  => writer.sync
          }

        val consume = ZSink.foreach { (value: A) =>
          for {
            _ <- writer.writeLine(value.toJson)
            _ <- commit match {
              case CommitPolicy.Buffered                    => ZIO.unit
              case CommitPolicy.Every(elements, durability) =>
                for {
                  count <- pending.updateAndGet(_ + 1)
                  _ <- if count == elements then commitBatch(durability) *> pending.set(0) else ZIO.unit
                } yield ()
            }
          } yield ()
        }

        consume.mapZIO { _ =>
          commit match {
            case CommitPolicy.Buffered             => writer.flush
            case CommitPolicy.Every(_, durability) =>
              for {
                count <- pending.get
                _ <- if count > 0 then commitBatch(durability) else ZIO.unit
              } yield ()
          }
        }
      }
    }
}

/** What to request at each commit boundary. */
enum Durability {

  /** Flush application buffers to the OS, without requesting disk synchronization. */
  case Flush

  /** Flush and request durable storage of file data and metadata. */
  case Sync
}

/** Commit frequency, independent of creation/append mode. This does not provide transactional batching. */
enum CommitPolicy {

  /** No per-record commit; the writer is flushed and closed at the end without an explicit disk sync. */
  case Buffered

  /** Commit every positive number of elements and on the final partial batch; use 1 for every element. */
  case Every(elements: Int, durability: Durability)
}
