# Rooted filesystem

Standalone sbt module `filesystem`, package `fmgp.yallmo.fs`. No agent-tool integration or global filesystem capability.

## Initialize once

```scala
import fmgp.yallmo.fs.*
import zio.*
import zio.stream.*

val application =
  for {
    fs <- FileSystem.open("/trusted/application/workspace")
    _ <- fs.createDirectories("notes")
    _ <- fs.writeUtf8("notes/today.txt", "hello", WriteMode.CreateNew)
    text <- fs.readUtf8("notes/today.txt")
  } yield text
```

`open(rootPath): IO[FsError, FileSystem]` validates an existing directory. Trusted configuration may use an absolute
or process-relative root; it is resolved once. Symlink roots and symlink ancestors are rejected.

This directory is the instance's **logical root**, not the machine's `/`: `"notes/today.txt"` resolves inside the
configured workspace, and `"."` denotes that workspace. Absolute operation paths, empty paths, every `..` component,
and symlinks in accessed paths are rejected. Listing also rejects symlink entries.

## Interfaces

- `FsRead`: `readBytes` (a byte stream), `readUtf8` (whole-file convenience), and sorted direct entry names via `list`.
- `FsWrite`: streaming `writeBytes`, `writeUtf8`, scoped UTF-8 writers, directory creation, deletion, and move/rename.
- `FsUtf8Writer`: `write`, LF-terminated `writeLine`, `flush`, and `sync` for one open file.
- `FsJsonlRead extends FsRead`: `read[A: JsonDecoder]` returns a `ZStream`.
- `FsJsonlWrite extends FsWrite`: `write[A: JsonEncoder]` returns a `ZSink`.
- `FileSystem` implements both JSONL traits directly, inheriting the text/JSONL convenience implementations.

Supply the required interface explicitly to each caller, for example `def load(fs: FsRead)`. Interface narrowing
hides mutation methods from read-only application code; it does not create a different runtime object or prevent
casts/reflection. No method on the read interfaces recovers the write interface. All NIO APIs and handles remain
private to the implementation; callers receive values, effects, or byte streams, not Path/File/handle objects.

## Streaming and JSONL

```scala
def saveBytes[R, E](fs: FsWrite, bytes: ZStream[R, E, Byte]): ZIO[R, E | FsError, Unit] =
  fs.writeBytes("data.bin", bytes, WriteMode.Append)

def appendNumbers(fs: FsJsonlWrite, values: ZStream[Any, Nothing, Int]) =
  values.run(fs.write[Int]("events.jsonl", WriteMode.Append, CommitPolicy.Every(100, Durability.Sync)))

def loadNumbers(fs: FsJsonlRead) =
  fs.read[Int]("events.jsonl")
```

Streams consume incrementally with backpressure and preserve upstream errors/environment requirements. Writers
open once per run, even for empty input, and close on completion, failure or interruption. Reusing a sink gets fresh
writer and commit state. JSONL emits compact JSON plus one LF per element.

`CommitPolicy.Buffered` flushes on successful completion. `Every(n, durability)` requires a positive record count
and commits each batch plus the final partial batch. The default is `Every(1, Durability.Sync)`.
`Flush` pushes application buffers to the OS; `Sync` also requests durable file data/metadata storage. Actual
persistence guarantees depend on the filesystem and hardware. Batching is not a transaction: failures may leave
partial records or batches, and different writers/processes are not coordinated.

JSONL accepts CRLF and a final record without LF. Blank/malformed records and trailing content fail with
`InvalidJson(path, line, message)` using 1-based line numbers. A private strict decoder handles zio-json 1.1.1's
permissive trailing-input behavior. UTF-8 failures use `IoFailure`. Reading buffers records without a size limit;
whole-file `readUtf8` collects the complete contents, while byte reads remain incremental.

## Mutation semantics

All writes require existing parent directories; only `createDirectories` creates parents recursively.

| Mode | Behavior |
| --- | --- |
| `WriteMode.CreateNew` | Fail if any entry exists. |
| `WriteMode.Replace` | Create or truncate a regular file, including for an empty input stream. |
| `WriteMode.Append` | Create or append without truncation. |

Directory creation succeeds for existing directories and rejects non-directories. `deleteFile` only deletes regular
files. `deleteDirectory` requires `EmptyOnly` (non-empty directories fail) or explicit `Recursive`. Missing paths fail.
Recursive deletion rejects symlink descendants and may partially complete before failure; there is no rollback.

`move` also handles rename. `MoveMode.FailIfExists` never replaces a destination; `ReplaceFile` only replaces an
existing regular file with another regular file. Directories are never replaced or merged. Destination parents must
exist. Identical endpoints, moving a directory into itself, and deleting/moving the configured root are forbidden.
Cross-filesystem moves are rejected; no copy/delete fallback, atomicity or disk synchronization is promised.

## Errors, cleanup and limits

Validation uses typed ZIO failures, not exception-based control flow. NIO exceptions are captured at the blocking
boundary and mapped to `FsError`. Normal read/write/flush/sync errors remain typed. Byte reads/writes and directory
listing explicitly close on success to keep close failures typed. Cleanup always runs through Scope; errors confined
to finalization become defects because finalizers have no typed error channel. Call a scoped writer's `flush` or
`sync` explicitly when that failure must be handled before leaving its scope. Scope exit flushes/closes, but does not
imply disk synchronization; cleanup can flush buffers even after a failed batch.

Path validation can race with concurrent filesystem mutation: **this is not an OS sandbox**. Hard-link aliases are
not prevented. Ordinary interfaces and Scope do not enforce capability lifetime or prevent retaining streams/writers
outside their granted scope. No experimental capture checking is enabled; existing Scala-tool safe mode is unchanged.

## Validation

```sh
sbt 'filesystem/testOnly fmgp.yallmo.fs.FileSystemSuite fmgp.yallmo.fs.FsApiSuite fmgp.yallmo.fs.FsDefaultsSuite fmgp.yallmo.fs.FsJsonlSuite'
sbt 'filesystem/scalafmt' 'filesystem/Test/scalafmt'
```

Tests focus on rooted I/O and mutation behavior, boundary rejection, read-only API separation, UTF-8/JSONL framing,
commit frequency, incremental consumption and cleanup/error propagation. Real-file tests check scope-exit flushing
and JSONL escaping; small instrumented capabilities check commit requests and finalization. They do not establish
power-loss durability or resistance to adversarial concurrent filesystem mutation.
