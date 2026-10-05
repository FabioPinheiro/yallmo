package fmgp.yallmo.rag

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import scala.jdk.CollectionConverters.*

/** Minimal BERT WordPiece tokenizer for models distributed with a `vocab.txt` file.
  *
  * This deliberately supports the English MiniLM/BERT artifact selected for the first local RAG milestone. Models using
  * SentencePiece or a tokenizer with extra normalization rules must supply another [[Tokenizer]] implementation.
  */
final class WordPieceTokenizer private (vocabulary: Map[String, Long]) extends Tokenizer:
  private val clsId = vocabulary("[CLS]")
  private val sepId = vocabulary("[SEP]")
  private val padId = vocabulary("[PAD]")
  private val unknownId = vocabulary("[UNK]")

  override def encode(text: String, maxTokens: Int): IO[RagError, TokenizedInput] =
    ZIO
      .attempt {
        val content = basicTokens(text).flatMap(wordPieces).take(maxTokens - 2)
        val ids = (Vector(clsId) ++ content ++ Vector(sepId)).padTo(maxTokens, padId)
        val mask = Vector.fill(content.size + 2)(1L).padTo(maxTokens, 0L)
        TokenizedInput(Chunk.fromIterable(ids), Chunk.fromIterable(mask), Chunk.fill(maxTokens)(0L))
      }
      .mapError(error => RagError.Tokenization("Unable to tokenize text", error))

  private def basicTokens(text: String): Vector[String] =
    """[\p{L}\p{N}]+|[^\s\p{L}\p{N}]""".r
      .findAllIn(text.toLowerCase(Locale.ROOT))
      .toVector

  private def wordPieces(token: String): Vector[Long] =
    if token.length > 100 then Vector(unknownId)
    else
      val pieces = Vector.newBuilder[Long]
      var start = 0
      var matched = true
      while start < token.length && matched do
        var end = token.length
        var found: Option[(Int, Long)] = None
        while end > start && found.isEmpty do
          val candidate = (if start == 0 then "" else "##") + token.substring(start, end)
          vocabulary.get(candidate).foreach(id => found = Some(end -> id))
          end -= 1
        found match
          case Some((next, id)) =>
            pieces += id
            start = next
          case None => matched = false
      if matched then pieces.result() else Vector(unknownId)

object WordPieceTokenizer:
  val layer: ZLayer[OnnxEmbeddingConfig, RagError, Tokenizer] =
    ZLayer.fromZIO(ZIO.service[OnnxEmbeddingConfig].flatMap(config => load(config.vocabularyPath)))

  def load(path: java.nio.file.Path): IO[RagError, WordPieceTokenizer] =
    ZIO
      .attemptBlockingIO(Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector.map(_.trim))
      .mapError(error => RagError.Tokenization(s"Unable to read vocabulary '$path'", error))
      .flatMap { tokens =>
        val vocabulary = tokens.zipWithIndex.collect {
          case (token, index) if token.nonEmpty => token -> index.toLong
        }.toMap
        val required = Set("[CLS]", "[SEP]", "[PAD]", "[UNK]")
        if required.subsetOf(vocabulary.keySet) then ZIO.succeed(new WordPieceTokenizer(vocabulary))
        else
          ZIO.fail(
            RagError
              .InvalidConfiguration(s"Vocabulary '$path' is missing ${required.diff(vocabulary.keySet).mkString(", ")}")
          )
      }
