package fmgp.yallmo.tool.utpc

import fmgp.yallmo.fs.{FileSystem, FsError}
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.{AgentFunction, ExternalFunctionCall, ExternalFunctionResult}
import fmgp.yallmo.tool.FunctionToolDefinition
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Immutable startup snapshot. Only filename IDs are accepted at invocation; no invocation does filesystem discovery.
  * Secrets in known credential fields and non-public static headers must be unresolved variable placeholders.
  */
final class UTPCTool private (manuals: Map[String, UTCPManual], provider: UTPCHttpProvider) extends AgentFunction[Any] {
  // Publish only catalog IDs here; schemas, transports and full descriptions stay lazily discoverable.
  val definition: FunctionToolDefinition = UTPCToolDefinition.Definition.copy(
    description =
      s"${UTPCToolDefinition.Definition.description}\nAvailable manuals: ${manuals.keys.toSeq.sorted.map(_.toJson).mkString("; ")}"
  )
  private val catalog = Json.Arr(Chunk.fromIterable(manuals.toSeq.sortBy(_._1).map { case (id, manual) =>
    val description = manual.extensions
      .get("description")
      .collect { case Json.Str(text) => text }
      .getOrElse(manual.tools.map(_.description).mkString("; "))
      .take(1024)
    Json.Obj("name" -> Json.Str(id), "description" -> Json.Str(description))
  }))

  def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] = {
    def invalid(reason: String) = YallmoError.InvalidToolArguments(definition.name, reason)
    for {
      _ <- ZIO
        .fail(invalid("UTPC arguments exceed the size limit"))
        .when(call.argumentsJson.length > UTPCTool.MaxArgumentChars)
      _ <- ZIO
        .fromEither(FunctionToolDefinition.validateArgument(call.argumentsJson, definition.inputSchema))
        .mapError(_ => invalid("Expected optional manual:string and agrs:object only; null is not allowed"))
      args <- ZIO.fromEither(call.argumentsJson.fromJson[Json.Obj]).mapError(_ => invalid("Invalid UTPC arguments"))
      _ <- ZIO
        .fail(invalid("UTPC arguments contain duplicate fields or exceed the nesting limit"))
        .when(!UTPCTool.boundedJson(args))
      fields = args.fields.toMap
      output <- fields.get("manual") match {
        case None               => ZIO.succeed(catalog)
        case Some(Json.Str(id)) =>
          for {
            manual <- ZIO
              .fromOption(manuals.get(id))
              .orElseFail(invalid("Unknown manual ID; call utpc without manual to list available names"))
            result <- fields.get("agrs") match {
              case None =>
                ZIO.fromEither(manual.toJsonAST).mapError(_ => YallmoError.ProtocolFailure("Cannot encode UTCP manual"))
              case Some(arguments: Json.Obj) =>
                for {
                  _ <- ZIO
                    .fail(invalid("Execution requires a manual with exactly one tool; no tool selector is supported"))
                    .unless(manual.tools.size == 1)
                  tool = manual.tools.head
                  _ <- ZIO
                    .fromEither(UTPCInputSchema.validate(tool.inputs, arguments))
                    .mapError(reason =>
                      invalid(s"$reason. Read the manual with only the manual property before constructing agrs.")
                    )
                  transport <- ZIO
                    .fromEither(tool.httpTransport)
                    .mapError(_ => invalid("Manual does not contain a supported HTTP transport"))
                  value <- provider
                    .call(transport, arguments)
                    .mapError(UTPCHttpProviderImp.publicFailure)
                } yield value
              case _ => ZIO.fail(invalid("agrs must be an object"))
            }
          } yield result
        case _ => ZIO.fail(invalid("manual must be a string"))
      }
      text = output.toJson
      _ <- ZIO
        .fail(YallmoError.ProtocolFailure("UTPC output exceeds the size limit"))
        .when(text.length > UTPCTool.MaxOutputChars)
    } yield ExternalFunctionResult(text)
  }
}

object UTPCTool {
  val MaxEntries = 64
  val MaxFileBytes = 128 * 1024
  val MaxArgumentChars = 128 * 1024
  val MaxOutputChars = 1024 * 1024
  private val placeholder = "\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}|\\$[A-Za-z_][A-Za-z0-9_]*".r
  private val credentialNames = Set(
    "key",
    "apikey",
    "xapikey",
    "password",
    "clientsecret",
    "accesstoken",
    "refreshtoken",
    "token",
    "secret",
    "authorization",
    "proxyauthorization",
    "cookie",
    "setcookie"
  )
  private def normalized(key: String): String = key.toLowerCase(java.util.Locale.ROOT).filter(_.isLetterOrDigit)
  private def placeholderOnly(text: String): Boolean = placeholder.matches(text)
  private def headerPlaceholder(text: String): Boolean =
    placeholderOnly(text) || ((text.startsWith("Bearer ") || text.startsWith("Basic ")) && placeholderOnly(
      text.dropWhile(_ != ' ').trim
    ))

  /** Missing directories are normal. Unsafe/malformed files are omitted with value-free diagnostics. Directory scan
    * overflow rejects the whole catalog. Rooted FileSystem checks all ancestors and rejects symlinks; as elsewhere in
    * FileSystem this is not a sandbox against concurrent directory replacement or hard-link aliases. No HTTP discovery
    * or execution happens here. Restart to refresh.
    */
  def load(storageRoot: Option[Path], provider: UTPCHttpProvider): UIO[Option[UTPCTool]] =
    ZIO
      .foreach(storageRoot) { root =>
        val directory = root.resolve("utpc/manual")
        (for {
          fs <- FileSystem.open(directory.toString).mapError {
            case _: FsError.NotFound => None
            case _                   => Some("Cannot read symlink-free UTPC manual directory")
          }
          names <- ZIO
            .attemptBlocking {
              val stream = Files.newDirectoryStream(directory)
              try stream.iterator().asScala.take(MaxEntries + 1).map(_.getFileName.toString).toVector
              finally stream.close()
            }
            .mapError(_ => Some("Cannot enumerate UTPC manual directory"))
          _ <- ZIO.fail(Some("UTPC manual directory exceeds the entry limit")).when(names.size > MaxEntries)
          candidates <- ZIO.foreach(names.sorted.filter(_.endsWith(".json"))) { filename =>
            val id = filename.stripSuffix(".json")
            val read = for {
              bytes <- fs
                .readBytes(filename)
                .take(MaxFileBytes.toLong + 1)
                .runCollect
                .mapError(_ => "Cannot read regular symlink-free UTPC manual")
              _ <- ZIO.fail("UTPC manual exceeds the byte limit").when(bytes.size > MaxFileBytes)
              text <- ZIO
                .attempt(UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toArray)).toString)
                .mapError(_ => "Invalid UTPC manual UTF-8")
              raw <- ZIO.fromEither(text.fromJson[Json]).mapError(_ => "Invalid UTCP manual JSON")
              _ <- ZIO.fail("UTCP manual exceeds nesting limits or contains duplicate fields").unless(boundedJson(raw))
              _ <- ZIO
                .fail(
                  "Unsafe UTCP manual: credential fields and non-public static headers require placeholder-only secrets ($VAR or ${VAR}); URL userinfo is forbidden"
                )
                .unless(safeCredentials(raw))
              manual <- ZIO.fromEither(raw.as[UTCPManual]).mapError(_ => "Invalid UTCP manual structure")
            } yield id -> manual
            read.foldZIO(
              reason => ZIO.logWarning(s"UTPC manual omitted: $reason").as(None),
              value => ZIO.some(value)
            )
          }
        } yield candidates.flatten.toMap).catchAll {
          case None         => ZIO.succeed(Map.empty[String, UTCPManual])
          case Some(reason) => ZIO.logWarning(reason).as(Map.empty[String, UTCPManual])
        }
      }
      .map(_.filter(_.nonEmpty).map(manuals => new UTPCTool(manuals, provider)))

  private[utpc] def boundedJson(value: Json, depth: Int = 0): Boolean =
    depth <= 32 && (value match {
      case Json.Obj(fields) =>
        fields.map(_._1).distinct.size == fields.size && fields.forall { case (_, v) => boundedJson(v, depth + 1) }
      case Json.Arr(values) => values.forall(boundedJson(_, depth + 1))
      case _                => true
    })

  private def safeCredentials(value: Json, inTransport: Boolean = false): Boolean = value match {
    case Json.Obj(fields) =>
      fields.forall { case (key, item) =>
        val safe = (normalized(key), item) match {
          case (name, Json.Str(text)) if credentialNames.contains(name) =>
            if Set("authorization", "proxyauthorization").contains(name) then headerPlaceholder(text)
            else placeholderOnly(text)
          case ("auth", Json.Obj(auth)) if inTransport =>
            auth.forall { case (name, v) =>
              !credentialNames.contains(normalized(name)) || (v match {
                case Json.Str(text) => placeholderOnly(text)
                case _              => false
              })
            }
          case ("headers", Json.Obj(headers)) if inTransport =>
            headers.forall { case (name, v) =>
              v match {
                case Json.Str(_) if Set("accept", "contenttype", "useragent").contains(normalized(name)) => true
                case Json.Str(text) => headerPlaceholder(text)
                case _              => false
              }
            }
          case ("url" | "tokenurl", Json.Str(text)) =>
            val authority = text.split("://", 2).last.takeWhile(c => c != '/' && c != '?' && c != '#')
            !authority
              .contains('@') && text.takeWhile(_ != '#').dropWhile(_ != '?').drop(1).split("[&;]").forall { parameter =>
              val parts = parameter.split("=", 2)
              // Classify query fields as an HTTP server sees them, not by their percent-encoded spelling.
              Try {
                val name = URLDecoder.decode(parts.head, UTF_8)
                !credentialNames.contains(normalized(name)) ||
                (parts.length == 2 && placeholderOnly(URLDecoder.decode(parts(1), UTF_8)))
              }.getOrElse(false)
            }
          case _ => true
        }
        safe && safeCredentials(item, inTransport || key == "tool_transport")
      }
    case Json.Arr(values) => values.forall(safeCredentials(_, inTransport))
    case _                => true
  }
}
