package fmgp.yallmo.rag

import zio.*

final class RagServiceLive private (
    embeddingService: EmbeddingService,
    vectorIndex: VectorIndex,
    config: RagConfig
) extends RagService:
  override def rebuild(sources: Chunk[RagSource]): IO[RagError, IndexReport] =
    for
      _ <- validateSources(sources)
      chunks = sources.flatMap(source => MarkdownChunker.chunk(source, config.chunking))
      embeddings <- embedChunks(chunks)
      entries = chunks.zipWith(embeddings)(IndexedChunk.apply)
      _ <- vectorIndex.replace(entries)
      dimensions = embeddings.headOption.fold(0)(_.values.size)
    yield IndexReport(sources.size, chunks.size, dimensions)

  override def retrieve(query: String): IO[RagError, Chunk[RetrievedDocument]] =
    retrieve(query, config.defaultRetrieval)

  override def retrieve(query: String, options: RetrievalOptions): IO[RagError, Chunk[RetrievedDocument]] =
    if query.trim.isEmpty then ZIO.fail(RagError.InvalidConfiguration("A retrieval query cannot be empty"))
    else
      embeddingService.embed(Chunk(query)).flatMap {
        case Chunk(embedding) => vectorIndex.nearest(embedding, options)
        case _ => ZIO.fail(RagError.InvalidModelOutput("Embedding service returned an unexpected query result"))
      }

  private def validateSources(sources: Chunk[RagSource]): IO[RagError, Unit] =
    if sources.exists(source => source.id.trim.isEmpty || source.text.trim.isEmpty) then
      ZIO.fail(RagError.InvalidConfiguration("Each RAG source requires a non-empty id and text"))
    else if sources.map(_.id).distinct.size != sources.size then
      ZIO.fail(RagError.InvalidConfiguration("RAG source ids must be unique"))
    else ZIO.unit

  private def embedChunks(chunks: Chunk[RagChunk]): IO[RagError, Chunk[Embedding]] =
    ZIO
      .foreach(Chunk.fromIterable(chunks.grouped(config.embeddingBatchSize).toVector))(batch =>
        embeddingService.embed(batch.map(_.text))
      )
      .map(_.flatten)

object RagServiceLive:
  val layer: ZLayer[EmbeddingService & VectorIndex & RagConfig, Nothing, RagService] =
    ZLayer.fromZIO {
      for
        embeddingService <- ZIO.service[EmbeddingService]
        vectorIndex <- ZIO.service[VectorIndex]
        config <- ZIO.service[RagConfig]
      yield new RagServiceLive(embeddingService, vectorIndex, config)
    }

  def make(embeddingService: EmbeddingService, vectorIndex: VectorIndex, config: RagConfig): RagService =
    new RagServiceLive(embeddingService, vectorIndex, config)
