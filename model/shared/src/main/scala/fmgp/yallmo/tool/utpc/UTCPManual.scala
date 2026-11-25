package fmgp.yallmo.tool.utpc

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** A UTCP 1.0 RFC manual. Discovery configuration is separate from this document.
  *
  * Versions are preserved, not negotiated. Extension fields are retained; decoding validates document structure, not
  * tool arguments against JSON Schema. Constructors are intended for trusted application code.
  */
final case class UTCPManual(
    utcpVersion: String,
    manualVersion: String,
    tools: Seq[UTCPManual.Tool],
    extensions: Map[String, Json] = Map.empty
)

object UTCPManual {

  /** One tool, with arbitrary JSON Schemas and an inert transport description.
    *
    * Non-HTTP transports are preserved without implementing them. Authentication and variable placeholders remain
    * unresolved data. `averageResponseSize` is an estimate in characters, not a response-size limit.
    */
  final case class Tool(
      name: String,
      description: String,
      inputs: Json,
      outputs: Json,
      toolTransport: Json.Obj,
      tags: Option[Seq[String]] = None,
      averageResponseSize: Option[Int] = None,
      extensions: Map[String, Json] = Map.empty
  ) {

    /** Interprets only RFC HTTP transports; unsupported transports remain available as raw JSON. */
    def httpTransport: Either[String, UTPCHttpTransport] = toolTransport.as[UTPCHttpTransport]
  }

  object Tool {
    private val keys =
      Set("name", "description", "inputs", "outputs", "tool_transport", "tags", "average_response_size")

    given JsonCodec[Tool] = Wire.codec(
      fields =>
        for {
          name <- Wire.nonBlank(fields, "name")
          description <- Wire.required[String](fields, "description")
          inputs <- Wire.schema(fields, "inputs")
          outputs <- Wire.schema(fields, "outputs")
          transport <- Wire.required[Json.Obj](fields, "tool_transport")
          _ <- Wire.nonBlank(transport.fields.toMap, "transport_type")
          tags <- Wire.optional[Seq[String]](fields, "tags")
          size <- Wire.optional[Int](fields, "average_response_size")
          _ <- Either.cond(size.forall(_ >= 0), (), "average_response_size must be non-negative")
        } yield Tool(name, description, inputs, outputs, transport, tags, size, fields -- keys),
      json
    )

    private[utpc] def json(tool: Tool): Json.Obj =
      Wire.obj(
        Map(
          "name" -> Json.Str(tool.name),
          "description" -> Json.Str(tool.description),
          "inputs" -> tool.inputs,
          "outputs" -> tool.outputs,
          "tool_transport" -> tool.toolTransport
        ) ++ tool.tags.map(value => "tags" -> Json.Arr(Chunk.fromIterable(value.map(Json.Str(_))))) ++
          tool.averageResponseSize.map(value => "average_response_size" -> Json.Num(value)),
        tool.extensions -- keys
      )
  }

  private val keys = Set("utcp_version", "manual_version", "tools")

  given JsonCodec[UTCPManual] = Wire.codec(
    fields =>
      for {
        version <- Wire.nonBlank(fields, "utcp_version")
        manualVersion <- Wire.nonBlank(fields, "manual_version")
        tools <- Wire.required[Seq[Tool]](fields, "tools")
        _ <- Either.cond(tools.map(_.name).distinct.size == tools.size, (), "tool names must be unique within a manual")
      } yield UTCPManual(version, manualVersion, tools, fields -- keys),
    manual =>
      Wire.obj(
        Map(
          "utcp_version" -> Json.Str(manual.utcpVersion),
          "manual_version" -> Json.Str(manual.manualVersion),
          "tools" -> Json.Arr(Chunk.fromIterable(manual.tools.map(Tool.json)))
        ),
        manual.extensions -- keys
      )
  )
}

/** Small JSON-object helpers shared by the manual and HTTP descriptor codecs. */
private[utpc] object Wire {
  def codec[A](decode: Map[String, Json] => Either[String, A], encode: A => Json.Obj): JsonCodec[A] =
    summon[JsonCodec[Json]].transformOrFail(
      {
        case Json.Obj(fields) if fields.map(_._1).distinct.size == fields.size => decode(fields.toMap)
        case _: Json.Obj                                                       => Left("duplicate object fields")
        case _                                                                 => Left("expected a JSON object")
      },
      encode
    )

  def required[A: JsonDecoder](fields: Map[String, Json], key: String): Either[String, A] =
    fields.get(key).toRight(s"missing required field '$key'").flatMap(_.as[A].left.map(error => s"$key: $error"))

  def optional[A: JsonDecoder](fields: Map[String, Json], key: String): Either[String, Option[A]] =
    fields.get(key) match {
      case None | Some(Json.Null) => Right(None)
      case Some(_)                => required[A](fields, key).map(Some(_))
    }

  def default[A: JsonDecoder](fields: Map[String, Json], key: String, value: A): Either[String, A] =
    if fields.contains(key) then required[A](fields, key) else Right(value)

  def nonBlank(fields: Map[String, Json], key: String): Either[String, String] =
    required[String](fields, key).flatMap(value => Either.cond(value.trim.nonEmpty, value, s"$key must not be blank"))

  def schema(fields: Map[String, Json], key: String): Either[String, Json] =
    required[Json](fields, key).flatMap {
      case value: Json.Obj  => Right(value)
      case value: Json.Bool => Right(value)
      case _                => Left(s"$key must be a JSON Schema object or boolean")
    }

  def obj(fields: Map[String, Json], extensions: Map[String, Json]): Json.Obj =
    Json.Obj(Chunk.fromIterable((extensions ++ fields).toSeq.sortBy(_._1)))
}
