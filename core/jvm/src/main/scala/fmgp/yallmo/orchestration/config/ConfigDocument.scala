package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.YallmoError
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import org.yaml.snakeyaml.{DumperOptions, LoaderOptions, Yaml}
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.*
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

import java.io.{StringReader, StringWriter}
import java.nio.file.Path
import java.util.{Collections, IdentityHashMap, Locale}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A validated file snapshot, including unknown fields and the original YAML comments/styles.
  *
  * Never log this document: both the tree and rendered text may contain credentials. Updating a YAML document retains
  * comments on surviving nodes, key order and scalar styles; whitespace/indentation may be normalized. `json` retains
  * environment references for persistence; only the typed `config` contains their resolved values.
  */
final class ConfigDocument private (
    val json: Json.Obj,
    val config: Config,
    private val original: String,
    private val yaml: Boolean
) {

  /** Render an updated tree without discarding unknown fields or comments in untouched subtrees. */
  def render(updated: Json.Obj): Either[YallmoError, String] =
    ConfigDocument.safely {
      if yaml then ConfigDocument.checkedYaml(ConfigDocument.update(ConfigDocument.compose(original), updated), updated)
      else updated.toJsonPretty + "\n"
    }
}

object ConfigDocument {
  def isYaml(path: Path): Boolean = {
    Option(path.getFileName).exists { fileName =>
      val name = fileName.toString.toLowerCase(Locale.ROOT)
      name.endsWith(".yaml") || name.endsWith(".yml")
    }
  }

  /** YAML uses JSON-compatible scalars (true/false, null, finite JSON numbers, strings). Aliases, merge keys, custom
    * tags, duplicate/non-string keys and multiple documents are rejected, rather than silently changing their meaning.
    * String values resolve explicit `${NAME}` references before typed decoding, using the server process environment by
    * default. Missing names fail without exposing values; `$${NAME}` is literal. Keys and the raw tree are unchanged.
    * The environment argument permits deterministic tests without mutating the process environment.
    */
  def parse(path: Path, text: String, environment: Map[String, String] = sys.env): Either[YallmoError, ConfigDocument] =
    for {
      tree <- safely {
        if isYaml(path) then yamlTree(text)
        else text.fromJson[Json].fold(_ => invalid(), identity)
      }
      obj <- tree match {
        case value: Json.Obj if uniqueKeys(value) => Right(value)
        case _                                    => Left(error)
      }
      resolved <- ConfigEnvironment.resolve(obj, environment)
      config <- resolved.toJson.fromJson[Config].left.map(_ => error)
      _ <- ConfigFile.validate(config)
    } yield new ConfigDocument(obj, config, text, isYaml(path))

  /** Fresh YAML for initialization or explicit migration. Existing YAML must instead use document.render. */
  def yaml(json: Json.Obj): Either[YallmoError, String] = safely(checkedYaml(toNode(json), json))

  private def error: YallmoError =
    YallmoError.InvalidConfig("Invalid configuration JSON/YAML; check its structure and field types")

  private def invalid(): Nothing = throw new IllegalArgumentException("Invalid configuration structure")

  private def safely[A](body: => A): Either[YallmoError, A] = Try(body).toEither.left.map(_ => error)

  private def uniqueKeys(json: Json): Boolean = json match {
    case obj: Json.Obj =>
      val names = obj.fields.map(_._1)
      names.distinct.size == names.size && obj.fields.forall(field => uniqueKeys(field._2))
    case arr: Json.Arr => arr.elements.forall(uniqueKeys)
    case _             => true
  }

  // YAML 1.2/JSON scalar resolution: words such as on, off, yes and dates remain strings.
  private class JsonResolver extends Resolver {
    override protected def addImplicitResolvers(): Unit = ()
    override def resolve(kind: NodeId, value: String, implicitValue: Boolean): Tag =
      if kind != NodeId.scalar || !implicitValue then super.resolve(kind, value, implicitValue)
      else if Set("null", "Null", "NULL", "~", "").contains(value) then Tag.NULL
      else if Set("true", "True", "TRUE", "false", "False", "FALSE").contains(value) then Tag.BOOL
      else if value.matches("-?(0|[1-9][0-9]*)") then Tag.INT
      else if value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") then Tag.FLOAT
      else Tag.STR
  }

  private def parser(): Yaml = {
    val loader = new LoaderOptions()
    loader.setProcessComments(true)
    loader.setAllowDuplicateKeys(false)
    loader.setMaxAliasesForCollections(0)
    loader.setNestingDepthLimit(50)
    val dumper = new DumperOptions()
    dumper.setProcessComments(true)
    dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
    new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader, new JsonResolver)
  }

  private def compose(text: String): Node = Option(parser().compose(new StringReader(text))).getOrElse(invalid())

  private def yamlTree(text: String): Json =
    toJson(compose(text), Collections.newSetFromMap(new IdentityHashMap[Node, java.lang.Boolean]()))

  // Never publish a document our reader cannot load, or one whose scalar styles changed its meaning.
  private def checkedYaml(node: Node, expected: Json): String = {
    val text = serialize(node)
    if yamlTree(text) != expected then invalid()
    text
  }

  private def toJson(node: Node, seen: java.util.Set[Node]): Json = {
    // Reject even scalar aliases, as updating an aliased subtree would otherwise break its relationship silently.
    if !seen.add(node) then invalid()
    node match {
      case obj: MappingNode if obj.getTag == Tag.MAP =>
        val fields = obj.getValue.asScala.map { tuple =>
          if !seen.add(tuple.getKeyNode) then invalid()
          val key = tuple.getKeyNode match {
            case scalar: ScalarNode if scalar.getTag == Tag.STR && (scalar.getValue != "<<" || !scalar.isPlain) =>
              scalar.getValue
            case _ => invalid()
          }
          key -> toJson(tuple.getValueNode, seen)
        }
        Json.Obj(Chunk.fromIterable(fields))
      case arr: SequenceNode if arr.getTag == Tag.SEQ =>
        Json.Arr(Chunk.fromIterable(arr.getValue.asScala.map(toJson(_, seen))))
      case scalar: ScalarNode =>
        val value = scalar.getValue
        scalar.getTag match {
          case Tag.STR  => Json.Str(value)
          case Tag.NULL =>
            if Set("null", "Null", "NULL", "~", "").contains(value) then Json.Null else invalid()
          case Tag.BOOL =>
            value.toLowerCase(Locale.ROOT) match {
              case "true"  => Json.Bool(true)
              case "false" => Json.Bool(false)
              case _       => invalid()
            }
          case Tag.INT | Tag.FLOAT =>
            value.fromJson[Json].toOption.collect { case n: Json.Num => n }.getOrElse(invalid())
          case _ => invalid()
        }
      case _ => invalid()
    }
  }

  private def toNode(json: Json): Node = json match {
    case obj: Json.Obj =>
      new MappingNode(
        Tag.MAP,
        obj.fields.map { case (key, value) => new NodeTuple(toNode(Json.Str(key)), toNode(value)) }.toList.asJava,
        DumperOptions.FlowStyle.BLOCK
      )
    case arr: Json.Arr =>
      new SequenceNode(Tag.SEQ, arr.elements.map(toNode).toList.asJava, DumperOptions.FlowStyle.BLOCK)
    case value =>
      val (tag, text) = value match {
        case Json.Str(text) => Tag.STR -> text
        case Json.Null      => Tag.NULL -> "null"
        case _: Json.Bool   => Tag.BOOL -> value.toJson
        case _: Json.Num    =>
          val text = value.toJson
          (if text.matches("-?(0|[1-9][0-9]*)") then Tag.INT else Tag.FLOAT) -> text
        case _ => invalid()
      }
      val style = if tag == Tag.STR && text.contains('\n') then DumperOptions.ScalarStyle.LITERAL
      else if tag == Tag.STR && text == "<<" then DumperOptions.ScalarStyle.DOUBLE_QUOTED
      else DumperOptions.ScalarStyle.PLAIN
      new ScalarNode(tag, text, null, null, style)
  }

  private def serialize(node: Node): String = {
    val writer = new StringWriter()
    parser().serialize(node, writer)
    writer.toString
  }

  private def update(node: Node, json: Json): Node = (node, json) match {
    case (obj: MappingNode, value: Json.Obj) =>
      val remaining = scala.collection.mutable.LinkedHashMap.from(value.fields)
      val fields = obj.getValue.asScala.toList.flatMap { tuple =>
        val key = tuple.getKeyNode.asInstanceOf[ScalarNode].getValue
        remaining.remove(key).map(value => new NodeTuple(tuple.getKeyNode, update(tuple.getValueNode, value)))
      }
      obj.setValue((fields ++ remaining.toList.map { case (key, value) =>
        new NodeTuple(toNode(Json.Str(key)), toNode(value))
      }).asJava)
      obj
    case (arr: SequenceNode, value: Json.Arr) =>
      val old = arr.getValue.asScala.toVector
      val updated = value.elements.zipWithIndex.map { case (item, index) =>
        old.lift(index).fold(toNode(item))(update(_, item))
      }
      arr.getValue.clear()
      arr.getValue.addAll(updated.toList.asJava)
      arr
    case _ =>
      val replacement = toNode(json)
      val result = (node, replacement) match {
        case (old: ScalarNode, fresh: ScalarNode) if old.getTag == fresh.getTag =>
          new ScalarNode(fresh.getTag, fresh.getValue, null, null, old.getScalarStyle)
        case _ => replacement
      }
      result.setBlockComments(node.getBlockComments)
      result.setInLineComments(node.getInLineComments)
      result.setEndComments(node.getEndComments)
      result
  }
}
