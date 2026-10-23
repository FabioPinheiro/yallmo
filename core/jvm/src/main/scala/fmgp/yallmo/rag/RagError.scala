package fmgp.yallmo.rag

sealed trait RagError extends RuntimeException
object RagError:
  final case class InvalidConfiguration(message: String) extends RuntimeException(message) with RagError
  final case class ModelLoad(message: String, cause: Throwable) extends RuntimeException(message, cause) with RagError
  final case class Tokenization(message: String, cause: Throwable)
      extends RuntimeException(message, cause)
      with RagError
  final case class SourceLoad(message: String, cause: Throwable) extends RuntimeException(message, cause) with RagError
  final case class Inference(message: String, cause: Throwable) extends RuntimeException(message, cause) with RagError
  final case class InvalidModelOutput(message: String) extends RuntimeException(message) with RagError
  final case class InvalidEmbedding(message: String) extends RuntimeException(message) with RagError
  final case class Index(message: String) extends RuntimeException(message) with RagError
