package fmgp.yallmo.rag

import zio.*

import java.nio.file.Path

/** A complete document made available to the local retrieval index.
  *
  * @param id
  *   stable caller-controlled source identifier; it becomes part of each generated chunk identifier
  * @param text
  *   source content to split and embed
  * @param attributes
  *   optional provenance or filtering metadata preserved on generated chunks
  */
final case class RagSource(id: String, text: String, attributes: Map[String, String] = Map.empty)

/** A bounded, independently retrievable portion of a [[RagSource]].
  *
  * @param id
  *   stable identifier generated from the source id and ordinal
  * @param sourceId
  *   identifier of the source that produced this chunk
  * @param text
  *   chunk text supplied to the embedding model and, after retrieval, the LLM context
  * @param attributes
  *   provenance metadata copied from the source
  */
final case class RagChunk(id: String, sourceId: String, text: String, attributes: Map[String, String])

/** A finite, non-empty L2-normalized embedding. Normalization makes cosine similarity a dot product. */
final case class Embedding private (values: Chunk[Float])
object Embedding:
  /** Validates and L2-normalizes raw model output. */
  def normalized(values: Chunk[Float]): Either[RagError, Embedding] =
    if values.isEmpty then Left(RagError.InvalidEmbedding("An embedding cannot be empty"))
    else if values.exists(value => !value.isFinite) then Left(RagError.InvalidEmbedding("An embedding must be finite"))
    else
      val magnitude = math.sqrt(values.foldLeft(0d)((total, value) => total + value * value))
      if magnitude == 0d then Left(RagError.InvalidEmbedding("An embedding cannot have zero magnitude"))
      else Right(Embedding(values.map(value => (value / magnitude).toFloat)))

/** Configuration for a BERT-compatible ONNX embedding session.
  *
  * `modelPath` and `vocabularyPath` must come from the same model revision. `maxTokens` is the fixed sequence length
  * sent to the model, including the CLS and SEP special tokens.
  */
final case class OnnxEmbeddingConfig(
    modelPath: Path,
    vocabularyPath: Path,
    maxTokens: Int = 256,
    inferenceParallelism: Int = 1
):
  require(maxTokens >= 3, "maxTokens must leave space for CLS and SEP tokens")
  require(inferenceParallelism > 0, "inferenceParallelism must be positive")

/** Markdown chunking policy.
  *
  * Limits are characters so source loading remains independent of a particular tokenizer. Overlap is retained at the
  * start of every chunk after the first to preserve local context across a boundary.
  */
final case class ChunkingConfig(maxCharacters: Int = 900, overlapCharacters: Int = 120):
  require(maxCharacters > 0, "maxCharacters must be positive")
  require(
    overlapCharacters >= 0 && overlapCharacters < maxCharacters,
    "overlap must be non-negative and smaller than max"
  )

/** Bounds applied before retrieved text is handed to an LLM.
  *
  * `minScore` is a cosine-similarity threshold. `maxContextCharacters` is a total budget across all returned chunks,
  * not a limit per chunk.
  */
final case class RetrievalOptions(topK: Int = 5, minScore: Double = 0.2d, maxContextCharacters: Int = 5000):
  require(topK > 0, "topK must be positive")
  require(minScore >= -1d && minScore <= 1d, "minScore must be a cosine similarity")
  require(maxContextCharacters > 0, "maxContextCharacters must be positive")

/** Service-wide ingestion and default retrieval policy. */
final case class RagConfig(
    chunking: ChunkingConfig = ChunkingConfig(),
    embeddingBatchSize: Int = 16,
    defaultRetrieval: RetrievalOptions = RetrievalOptions()
):
  require(embeddingBatchSize > 0, "embeddingBatchSize must be positive")

/** A source-attributed result returned by a [[VectorIndex]].
  *
  * The `text` remains untrusted retrieved content; callers must render it as context rather than executable
  * instructions when passing it to an LLM.
  */
final case class RetrievedDocument(id: String, source: String, text: String, score: Double)

/** Summary emitted after atomically replacing an index. */
final case class IndexReport(sourceCount: Int, chunkCount: Int, dimensions: Int)

/** Fixed-length BERT-compatible model inputs after WordPiece tokenization. */
final case class TokenizedInput(inputIds: Chunk[Long], attentionMask: Chunk[Long], tokenTypeIds: Chunk[Long])
