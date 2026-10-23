package fmgp.yallmo.rag

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Runs a local, ONNX-backed RAG search over Markdown documents. */
object RagMainDemo extends ZIOAppDefault:
  private final case class Arguments(model: Path, vocabulary: Path, sourceDirectory: Path, query: String)

  override def run: ZIO[ZIOAppArgs & Scope, Any, ExitCode] =
    (for
      arguments <- readArguments
      sources <- readMarkdownSources(arguments.sourceDirectory)
      result <- ZIO.scoped {
        for
          tokenizer <- WordPieceTokenizer.load(arguments.vocabulary)
          embeddingService <- OnnxEmbeddingService.open(
            OnnxEmbeddingConfig(arguments.model, arguments.vocabulary),
            tokenizer
          )
          vectorIndex <- InMemoryVectorIndex.make
          rag = RagServiceLive.make(embeddingService, vectorIndex, RagConfig())
          report <- rag.rebuild(sources)
          documents <- rag.retrieve(arguments.query)
        yield report -> documents
      }
      (report, documents) = result
      _ <- Console.printLine(
        s"Indexed ${report.chunkCount} chunks from ${report.sourceCount} sources (${report.dimensions} dimensions)."
      )
      _ <-
        if documents.isEmpty then Console.printLine("No documents met the retrieval threshold.")
        else Console.printLine(render(documents))
    yield ExitCode.success).catchAll { error =>
      Console.printLineError(s"RAG demo failed: ${error.getMessage}").as(ExitCode.failure)
    }

  private def readArguments: ZIO[ZIOAppArgs, RagError, Arguments] =
    ZIOAppArgs.getArgs.flatMap { values =>
      val args = values.toList
      for
        model <- requiredPath(args, "--model")
        vocabulary <- requiredPath(args, "--vocab")
        sourceDirectory = optionalPath(args, "--source").getOrElse(Path.of("data/lores"))
        query <- requiredValue(args, "--query")
      yield Arguments(model, vocabulary, sourceDirectory, query)
    }

  private def requiredPath(args: List[String], flag: String): IO[RagError, Path] =
    requiredValue(args, flag).map(Path.of(_))

  private def optionalPath(args: List[String], flag: String): Option[Path] =
    args.sliding(2).collectFirst { case flagValue :: value :: Nil if flagValue == flag => Path.of(value) }

  private def requiredValue(args: List[String], flag: String): IO[RagError, String] =
    args.sliding(2).collectFirst { case flagValue :: value :: Nil if flagValue == flag => value } match
      case Some(value) if value.trim.nonEmpty => ZIO.succeed(value)
      case _                                  => ZIO.fail(RagError.InvalidConfiguration(usage))

  private def readMarkdownSources(directory: Path): IO[RagError, Chunk[RagSource]] =
    ZIO
      .attemptBlockingIO {
        val files = Files.list(directory)
        try
          files.iterator.asScala
            .filter(path =>
              Files.isRegularFile(path) &&
                path.getFileName.toString.endsWith(".md") &&
                !path.getFileName.toString.equalsIgnoreCase("README.md")
            )
            .toVector
            .sortBy(_.getFileName.toString)
            .map { path =>
              RagSource(path.getFileName.toString, Files.readString(path, StandardCharsets.UTF_8))
            }
        finally files.close()
      }
      .map(Chunk.fromIterable)
      .mapError(error => RagError.SourceLoad(s"Unable to read Markdown sources from '$directory'", error))
      .flatMap { sources =>
        if sources.isEmpty then ZIO.fail(RagError.InvalidConfiguration(s"No Markdown files found in '$directory'"))
        else ZIO.succeed(sources)
      }

  private def render(documents: Chunk[RetrievedDocument]): String =
    documents
      .map { document =>
        f"""|--- ${document.source}/${document.id} (score=${document.score}%.3f)
            |${document.text}
            |""".stripMargin
      }
      .mkString("\n")

  private val usage =
    """Usage:
      |  sbt 'yallmo/run -- --model <model.onnx> --vocab <vocab.txt> --query <text> [--source data/lores]'
      |
      |For the first English MiniLM demo, download matching artifacts from one model revision, for example:
      |  mkdir -p models/all-MiniLM-L6-v2
      |  curl -L -o models/all-MiniLM-L6-v2/model.onnx https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx
      |  curl -L -o models/all-MiniLM-L6-v2/vocab.txt https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt
      |""".stripMargin
