package fmgp.yallmo.skill

import fmgp.yallmo.fs.{FileSystem, FsError, FsRead}
import fmgp.yallmo.model.{ResourceDiagnostic, YallmoError}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.io.StringReader
import java.util.{Collections, IdentityHashMap}
import org.yaml.snakeyaml.{DumperOptions, LoaderOptions, Yaml}
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.*
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import scala.jdk.CollectionConverters.*
import scala.util.Try
import zio.{IO, Ref, UIO, ZIO}

/** One-level discovery under <storage>/skills; never creates directories. Complete skills are cached until restart.
  * Rooted FileSystem validation rejects symlinks. As with FileSystem, this is not an OS sandbox against concurrent
  * filesystem replacement or hard-link aliases.
  */
object FileSkillService {
  val MaxEntries = 256
  val MaxFileBytes = 256 * 1024
  val MaxMetadataChars = 16 * 1024

  /** Missing storage/skills is normal. Other discovery failures produce an empty catalog with diagnostics. The entry
    * count check limits skill processing, not directory enumeration memory/work, and rejects the entire scan on
    * overflow to avoid a traversal-order-dependent partial catalog.
    */
  def make(storageRoot: Option[Path]): UIO[SkillService] =
    (storageRoot match {
      case None       => ZIO.succeed(SkillService.empty)
      case Some(root) =>
        (for {
          fs <- FileSystem.open(root.resolve("skills").toString)
          names <- fs.list(".")
          _ <- ZIO.fail(FsError.InvalidArgument(s"Directory exceeds $MaxEntries entries")).when(names.size > MaxEntries)
          candidates <- ZIO.foreach(names.toVector) { directory =>
            val path = s"$directory/SKILL.md"
            (for {
              text <- readBounded(fs, path)
                .mapError(error => ResourceDiagnostic(directory, s"Cannot read SKILL.md: ${error.getMessage}"))
              skill <- ZIO.fromEither(parseSkill(text)).mapError(reason => ResourceDiagnostic(directory, reason))
              sections <- readSections(fs, directory)
                .mapError(error => ResourceDiagnostic(directory, s"Cannot load sections: ${error.getMessage}"))
            } yield directory -> skill.copy(sections = sections)).either
          }
          result = build(candidates)
        } yield result).catchAll {
          case FsError.NotFound(_) => ZIO.succeed(SkillService.empty)
          case error => ZIO.succeed(unavailable(ResourceDiagnostic("skills", s"Cannot discover skills: $error")))
        }
    }).tap(service =>
      ZIO.foreachDiscard(service.diagnostics) { diagnostic =>
        // Escape operator-controlled directory names so one entry cannot forge additional log lines.
        val safe = diagnostic.toString.map(char => if Character.isISOControl(char) then '?' else char)
        ZIO.logWarning(s"Skill discovery: $safe")
      }
    )

  private def unavailable(diagnostic: ResourceDiagnostic): SkillService = new SkillService {
    override val catalog: Vector[Skill] = Vector.empty
    override val diagnostics: Vector[ResourceDiagnostic] = Vector(diagnostic)
    override def readSkill(name: String): IO[YallmoError, Skill] = ZIO.fail(YallmoError.UnknownSkill(name))
  }

  private def build(
      candidates: Vector[Either[ResourceDiagnostic, (String, Skill)]]
  ): SkillService = {
    // Input is sorted by directory; the first valid declaration wins, independently of the declared skill name.
    val (entries, warnings) = candidates.foldLeft(
      (Map.empty[String, (String, Skill)], Vector.empty[ResourceDiagnostic])
    ) { case ((entries, warnings), candidate) =>
      candidate match {
        case Left(warning)             => (entries, warnings :+ warning)
        case Right((directory, value)) =>
          entries.get(value.name) match {
            case Some((winner, _)) =>
              (entries, warnings :+ ResourceDiagnostic(directory, s"Duplicate name '${value.name}'; using '$winner'"))
            case None => (entries.updated(value.name, directory -> value), warnings)
          }
      }
    }
    new SkillService {
      override val catalog: Vector[Skill] = entries.valuesIterator.map(_._2).toVector.sortBy(_.name)
      override val diagnostics: Vector[ResourceDiagnostic] = warnings
      override def readSkill(name: String): IO[YallmoError, Skill] =
        entries.get(name) match {
          case None             => ZIO.fail(YallmoError.UnknownSkill(name))
          case Some((_, skill)) => ZIO.succeed(skill)
        }
    }
  }

  /** Only Markdown files are instruction sections; binary assets and scripts are not context text. */
  private def readSections(fs: FsRead, directory: String): IO[YallmoError, Map[String, String]] =
    for {
      count <- Ref.make(0)
      sections <- {
        def walk(relative: String, depth: Int): IO[YallmoError, Map[String, String]] = {
          val path = if relative.isEmpty then directory else s"$directory/$relative"
          def invalid(reason: String): IO[YallmoError, Nothing] =
            ZIO.fail(YallmoError.SkillReadFailed(path, reason, new RuntimeException(reason)))
          if depth > 32 then invalid("Section nesting exceeds 32")
          else
            fs.list(path).either.flatMap {
              case Right(names) =>
                for {
                  total <- count.updateAndGet(_ + names.size)
                  _ <- invalid(s"Skill exceeds $MaxEntries entries").when(total > MaxEntries)
                  children <- ZIO.foreach(names) { name =>
                    if relative.isEmpty && name == "SKILL.md" then ZIO.succeed(Map.empty[String, String])
                    else walk(if relative.isEmpty then name else s"$relative/$name", depth + 1)
                  }
                } yield children.foldLeft(Map.empty[String, String])(_ ++ _)
              case Left(_: FsError.WrongType) =>
                if relative.endsWith(".md") then readBounded(fs, path).map(text => Map(relative -> text))
                else ZIO.succeed(Map.empty[String, String])
              case Left(error) =>
                invalid(error.toString)
            }
        }
        walk("", 0)
      }
    } yield sections

  private def readBounded(fs: FsRead, path: String): IO[YallmoError, String] =
    for {
      bytes <- fs.readBytes(path).take(MaxFileBytes.toLong + 1L).runCollect.mapError { error =>
        YallmoError.SkillReadFailed(path, error.toString, new RuntimeException(error.toString))
      }
      _ <- ZIO.fail(YallmoError.SkillTooLarge(MaxFileBytes)).when(bytes.size > MaxFileBytes)
      text <- ZIO
        .attempt(UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toArray)).toString)
        .mapError(error => YallmoError.SkillReadFailed(path, "Invalid UTF-8", error))
    } yield text

  // Match configuration's YAML 1.2/JSON scalar conventions: skill names such as "on" remain strings.
  private class MetadataResolver extends Resolver {
    override protected def addImplicitResolvers(): Unit = ()
    override def resolve(kind: NodeId, value: String, implicitValue: Boolean): Tag =
      if kind != NodeId.scalar || !implicitValue then super.resolve(kind, value, implicitValue)
      else if Set("null", "Null", "NULL", "~", "").contains(value) then Tag.NULL
      else if Set("true", "True", "TRUE", "false", "False", "FALSE").contains(value) then Tag.BOOL
      else if value.matches("-?(0|[1-9][0-9]*)") then Tag.INT
      else if value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") then Tag.FLOAT
      else Tag.STR
  }

  private def parseSkill(text: String): Either[String, Skill] = Try {
    def invalid(): Nothing = throw new IllegalArgumentException("Invalid metadata")
    val prefix = text.take(MaxMetadataChars + 10)
    val prefixLines = prefix.linesIterator.toVector
    // A truncated last line must not be mistaken for a complete closing delimiter.
    val lines = if prefix.length < text.length && !prefix.endsWith("\n") then prefixLines.dropRight(1) else prefixLines
    if lines.headOption != Some("---") then invalid()
    val end = lines.indexWhere(_ == "---", 1)
    if end < 0 then invalid()
    val header = lines.slice(1, end).mkString("\n")
    if header.length > MaxMetadataChars then invalid()
    val options = new LoaderOptions()
    options.setAllowDuplicateKeys(false)
    options.setMaxAliasesForCollections(0)
    options.setNestingDepthLimit(20)
    options.setCodePointLimit(MaxMetadataChars)
    val dumper = new DumperOptions()
    val yaml = new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options, new MetadataResolver)
    val node = yaml.compose(new StringReader(header))
    val seen = Collections.newSetFromMap(new IdentityHashMap[Node, java.lang.Boolean]())
    def validate(node: Node): Unit = {
      if node == null || !seen.add(node) then invalid()
      node match {
        case mapping: MappingNode if mapping.getTag == Tag.MAP =>
          val keys = mapping.getValue.asScala.map { tuple =>
            validate(tuple.getKeyNode)
            validate(tuple.getValueNode)
            tuple.getKeyNode match {
              case key: ScalarNode if key.getTag == Tag.STR && key.getValue != "<<" => key.getValue
              case _                                                                => invalid()
            }
          }
          if keys.distinct.size != keys.size then invalid()
        case sequence: SequenceNode if sequence.getTag == Tag.SEQ => sequence.getValue.asScala.foreach(validate)
        case scalar: ScalarNode if Set(Tag.STR, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.NULL).contains(scalar.getTag) => ()
        case _ => invalid()
      }
    }
    validate(node)
    val fields = node match {
      case mapping: MappingNode =>
        mapping.getValue.asScala.map { tuple =>
          tuple.getKeyNode.asInstanceOf[ScalarNode].getValue -> tuple.getValueNode
        }.toMap
      case _ => invalid()
    }
    def string(key: String): String = fields.get(key) match {
      case Some(value: ScalarNode) if value.getTag == Tag.STR => value.getValue
      case _                                                  => invalid()
    }
    val name = string("name")
    val description = string("description")
    if name.length > 64 || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") then invalid()
    if description.trim.isEmpty || description.length > 1024 then invalid()
    val disabled = fields.get("disable-model-invocation") match {
      case None                                                => false
      case Some(value: ScalarNode) if value.getTag == Tag.BOOL =>
        value.getValue.toLowerCase(java.util.Locale.ROOT) match {
          case "true"  => true
          case "false" => false
          case _       => invalid()
        }
      case _ => invalid()
    }
    // Find the closing delimiter in the original text to preserve body whitespace and CRLF exactly.
    val lineBreak = "\r\n|\n|\r".r
    val breaks = lineBreak.findAllMatchIn(text).take(end + 1).toVector
    val body = if breaks.size > end then text.substring(breaks(end).end) else ""
    Skill(name, description, body, disabled)
  }.toEither.left.map(_ =>
    "Invalid SKILL.md frontmatter: require a kebab-case name (1–64), description (1–1024), and valid bounded YAML"
  )
}
