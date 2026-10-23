package fmgp.yallmo.rag

import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession}
import zio.*

import java.nio.LongBuffer
import scala.jdk.CollectionConverters.*

/** ONNX Runtime adapter for BERT-style embedding models with `input_ids`, `attention_mask`, and `token_type_ids`.
  *
  * The first model output must be `last_hidden_state` with shape `[1, tokens, dimensions]`. The service performs
  * masked-mean pooling and L2 normalization, as prescribed by the selected MiniLM sentence-transformer model.
  */
final class OnnxEmbeddingService private (
    config: OnnxEmbeddingConfig,
    tokenizer: Tokenizer,
    environment: OrtEnvironment,
    session: OrtSession,
    permits: Semaphore
) extends EmbeddingService:
  override def embed(texts: Chunk[String]): IO[RagError, Chunk[Embedding]] =
    ZIO.foreachPar(texts)(embedOne).withParallelism(config.inferenceParallelism)

  private def embedOne(text: String): IO[RagError, Embedding] =
    for
      tokenized <- tokenizer.encode(text, config.maxTokens)
      embedding <- permits.withPermit(run(tokenized))
    yield embedding

  private def run(input: TokenizedInput): IO[RagError, Embedding] =
    ZIO
      .attemptBlocking {
        val shape = Array(1L, config.maxTokens.toLong)
        val inputIds = tensor(input.inputIds, shape)
        val attentionMask = tensor(input.attentionMask, shape)
        val tokenTypeIds = tensor(input.tokenTypeIds, shape)
        try
          val result = session.run(
            Map(
              "input_ids" -> inputIds,
              "attention_mask" -> attentionMask,
              "token_type_ids" -> tokenTypeIds
            ).asJava
          )
          try result.get(0).getValue.asInstanceOf[Array[Array[Array[Float]]]]
          finally result.close()
        finally
          inputIds.close()
          attentionMask.close()
          tokenTypeIds.close()
      }
      .mapError(error => RagError.Inference("ONNX embedding inference failed", error))
      .flatMap(values => ZIO.fromEither(maskedMean(values, input.attentionMask)))

  private def tensor(values: Chunk[Long], shape: Array[Long]): OnnxTensor =
    OnnxTensor.createTensor(environment, LongBuffer.wrap(values.toArray), shape)

  private def maskedMean(
      hiddenStates: Array[Array[Array[Float]]],
      attentionMask: Chunk[Long]
  ): Either[RagError, Embedding] =
    for
      tokens <- hiddenStates.headOption.toRight(RagError.InvalidModelOutput("Model returned no batch output"))
      dimensions <- tokens.headOption
        .map(_.length)
        .filter(_ > 0)
        .toRight(
          RagError.InvalidModelOutput("Model returned no embedding dimensions")
        )
      _ <- Either.cond(
        tokens.length == attentionMask.length,
        (),
        RagError.InvalidModelOutput("Model output token count does not match the tokenizer input")
      )
      included = tokens.zip(attentionMask).collect { case (vector, 1L) => vector }
      _ <- Either.cond(included.nonEmpty, (), RagError.InvalidModelOutput("Tokenizer produced an empty attention mask"))
      _ <- Either.cond(
        included.forall(_.length == dimensions),
        (),
        RagError.InvalidModelOutput("Model returned inconsistent embedding dimensions")
      )
      averaged = Chunk.fromIterable(
        (0 until dimensions).map(index => (included.iterator.map(_(index).toDouble).sum / included.size).toFloat)
      )
      embedding <- Embedding.normalized(averaged)
    yield embedding

object OnnxEmbeddingService:
  val layer: ZLayer[OnnxEmbeddingConfig & Tokenizer, RagError, EmbeddingService] =
    ZLayer.scoped {
      for
        config <- ZIO.service[OnnxEmbeddingConfig]
        tokenizer <- ZIO.service[Tokenizer]
        runtime <- openRuntime(config)
        permits <- Semaphore.make(config.inferenceParallelism)
      yield new OnnxEmbeddingService(config, tokenizer, runtime.environment, runtime.session, permits)
    }

  def open(config: OnnxEmbeddingConfig, tokenizer: Tokenizer): ZIO[Scope, RagError, EmbeddingService] =
    for
      runtime <- openRuntime(config)
      permits <- Semaphore.make(config.inferenceParallelism)
    yield new OnnxEmbeddingService(config, tokenizer, runtime.environment, runtime.session, permits)

  private final case class Runtime(environment: OrtEnvironment, session: OrtSession)

  private def openRuntime(config: OnnxEmbeddingConfig): ZIO[Scope, RagError, Runtime] =
    ZIO.acquireRelease(
      ZIO
        .attemptBlocking {
          val environment = OrtEnvironment.getEnvironment()
          val options = new OrtSession.SessionOptions()
          try Runtime(environment, environment.createSession(config.modelPath.toString, options))
          finally options.close()
        }
        .mapError(error => RagError.ModelLoad(s"Unable to load ONNX model '${config.modelPath}'", error))
    )(runtime => ZIO.attemptBlocking(runtime.session.close()).orDie)
