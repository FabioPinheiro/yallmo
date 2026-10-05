package fmgp.yallmo.rag

import zio.*

/** Exact cosine-similarity index for small and medium local corpora. */
final class InMemoryVectorIndex private (state: Ref.Synchronized[Chunk[IndexedChunk]]) extends VectorIndex:
  override def replace(entries: Chunk[IndexedChunk]): IO[RagError, Unit] =
    validateDimensions(entries) *> state.set(entries)

  override def nearest(
      query: Embedding,
      options: RetrievalOptions
  ): IO[RagError, Chunk[RetrievedDocument]] =
    state.get.flatMap { entries =>
      val compatible = entries.filter(_.embedding.values.size == query.values.size)
      if compatible.size != entries.size then
        ZIO.fail(RagError.Index("The index contains embeddings with incompatible dimensions"))
      else
        ZIO.succeed(
          compatible
            .map(entry => entry -> similarity(query, entry.embedding))
            .filter(_._2 >= options.minScore)
            .sortBy { case (_, score) => -score }
            .take(options.topK)
            .foldLeft(Chunk.empty[RetrievedDocument] -> 0) { case ((documents, usedCharacters), (entry, score)) =>
              val remaining = options.maxContextCharacters - usedCharacters
              if remaining <= 0 then documents -> usedCharacters
              else
                val text = entry.chunk.text.take(remaining)
                (documents :+ RetrievedDocument(entry.chunk.id, entry.chunk.sourceId, text, score)) ->
                  (usedCharacters + text.length)
            }
            ._1
        )
    }

  private def validateDimensions(entries: Chunk[IndexedChunk]): IO[RagError, Unit] =
    entries.headOption match
      case None                                                                                  => ZIO.unit
      case Some(first) if entries.forall(_.embedding.values.size == first.embedding.values.size) => ZIO.unit
      case Some(_) => ZIO.fail(RagError.Index("All indexed embeddings must have the same dimension"))

  private def similarity(left: Embedding, right: Embedding): Double =
    left.values.zip(right.values).foldLeft(0d) { case (total, (a, b)) => total + a * b }

object InMemoryVectorIndex:
  val layer: ULayer[VectorIndex] = ZLayer.fromZIO(make)

  def make: UIO[InMemoryVectorIndex] =
    Ref.Synchronized.make(Chunk.empty[IndexedChunk]).map(new InMemoryVectorIndex(_))
