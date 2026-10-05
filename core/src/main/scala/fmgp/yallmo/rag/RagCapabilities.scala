package fmgp.yallmo.rag

import zio.*

/** Converts text to model-compatible normalized embeddings. */
trait EmbeddingService:
  def embed(texts: Chunk[String]): IO[RagError, Chunk[Embedding]]

/** Tokenizes text for the embedding model. */
trait Tokenizer:
  def encode(text: String, maxTokens: Int): IO[RagError, TokenizedInput]

/** Replaces and searches an index of already embedded chunks. */
trait VectorIndex:
  def replace(entries: Chunk[IndexedChunk]): IO[RagError, Unit]
  def nearest(query: Embedding, options: RetrievalOptions): IO[RagError, Chunk[RetrievedDocument]]

/** Ingests source documents and serves bounded, source-attributed retrieval results. */
trait RagService:
  def rebuild(sources: Chunk[RagSource]): IO[RagError, IndexReport]
  def retrieve(query: String): IO[RagError, Chunk[RetrievedDocument]]
  def retrieve(query: String, options: RetrievalOptions): IO[RagError, Chunk[RetrievedDocument]]

final case class IndexedChunk(chunk: RagChunk, embedding: Embedding)
