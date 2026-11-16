package fmgp.yallmo.tool

import zio.json.JsonCodec

/** Host-authored, display-only API documentation for a resolved Scalamode function. Never contains transport tickets,
  * endpoints, dispatchers, artifact paths or runner configuration. It grants no execution authority.
  */
final case class ScalamodeApi(builtins: String, services: List[ScalamodeServiceApi] = Nil) derives JsonCodec

/** One supplied parameter, possibly exposing an intersection of reviewed API types. Interface source is supplied by the
  * binding author, not inferred from description text or the host classpath. None means source is unavailable.
  */
final case class ScalamodeServiceApi(
    parameter: String,
    apiTypes: List[String],
    description: String,
    scalaApi: Option[String] = None
) derives JsonCodec
