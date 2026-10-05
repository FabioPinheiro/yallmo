package fmgp.yallmo.rag

import munit.FunSuite
import zio.*

final class RagServiceSuite extends FunSuite:
  test("Markdown chunking preserves source attribution and configured bounds") {
    val source = RagSource("lore", "one two three four five six seven eight nine ten eleven twelve")
    val chunks = MarkdownChunker.chunk(source, ChunkingConfig(maxCharacters = 20, overlapCharacters = 5))

    assert(chunks.nonEmpty)
    assert(chunks.forall(_.sourceId == "lore"))
    assert(chunks.forall(_.text.length <= 20))
    assertEquals(chunks.map(_.id), Chunk.fromIterable(chunks.indices.map(index => s"lore#${index + 1}")))
  }

  test("exact vector index ranks cosine matches and applies context budget") {
    val result = runZIO {
      for
        index <- InMemoryVectorIndex.make
        east <- embedding(1f, 0f)
        north <- embedding(0f, 1f)
        _ <- index.replace(
          Chunk(
            IndexedChunk(RagChunk("east", "source-a", "e" * 20, Map.empty), east),
            IndexedChunk(RagChunk("north", "source-b", "north", Map.empty), north)
          )
        )
        found <- index.nearest(east, RetrievalOptions(topK = 2, minScore = -1d, maxContextCharacters = 10))
      yield found
    }

    assertEquals(result.map(_.id), Chunk("east"))
    assertEquals(result.head.text, "e" * 10)
  }

  test("RAG service embeds source chunks and retrieves the closest source") {
    val result = runZIO {
      for
        index <- InMemoryVectorIndex.make
        rag = RagServiceLive.make(new KeywordEmbeddingService, index, RagConfig(ChunkingConfig(200, 0)))
        report <- rag.rebuild(
          Chunk(
            RagSource("norse", "Odin's ravens bring warnings across the northern sky."),
            RagSource("ocean", "Warm beaches and salt water surround the harbour.")
          )
        )
        found <- rag.retrieve("ravens and Odin", RetrievalOptions(topK = 1, minScore = -1d, maxContextCharacters = 200))
      yield report -> found
    }

    assertEquals(result._1.sourceCount, 2)
    assertEquals(result._1.dimensions, 2)
    assertEquals(result._2.map(_.source), Chunk("norse"))
  }

  private def embedding(values: Float*): IO[RagError, Embedding] =
    ZIO.fromEither(Embedding.normalized(Chunk.fromIterable(values)))

  private final class KeywordEmbeddingService extends EmbeddingService:
    override def embed(texts: Chunk[String]): IO[RagError, Chunk[Embedding]] =
      ZIO.foreach(texts) { text =>
        val lower = text.toLowerCase
        val myth = if lower.contains("odin") || lower.contains("raven") then 1f else 0f
        val coast = if lower.contains("beach") || lower.contains("ocean") || lower.contains("harbour") then 1f else 0f
        embedding(myth, coast)
      }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect.either).getOrThrowFiberFailure() match
        case Right(value) => value
        case Left(error)  => throw RuntimeException(error.toString)
    }
