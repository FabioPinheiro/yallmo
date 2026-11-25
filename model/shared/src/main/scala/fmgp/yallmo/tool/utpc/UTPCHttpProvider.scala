package fmgp.yallmo.tool.utpc

import fmgp.yallmo.model.YallmoError
import zio.IO
import zio.json.ast.Json

/** Client-side HTTP contract; the concrete implementation lives in core, not the shared model module. No registry, MCP
  * support or process launching is implied.
  *
  * Implementations own HTTP/authentication, variable resolution, URL encoding, destination policy, time/output bounds
  * and status handling. Resolve configured variables before invocation; unresolved variables must fail, not become
  * literal credentials. Never log secrets or automatically retry an ambiguously completed side effect.
  */
trait UTPCHttpProvider {

  /** Fetches and decodes an RFC manual using independently supplied discovery configuration.
    *
    * Invalid manuals fail with ProtocolFailure; no automatic OpenAPI conversion is required by this interface.
    */
  def discover(transport: UTPCHttpTransport): IO[YallmoError, UTCPManual]

  /** Calls an HTTP tool directly, without discovery, registration or schema validation.
    *
    * Arguments bind to URL placeholders, the configured body field, configured header fields, then remaining query
    * parameters, in that order. Return JSON responses as JSON and text responses as Json.Str; non-success HTTP statuses
    * fail with HttpFailure. The caller owns input/output validation against the tool's schemas.
    */
  def call(transport: UTPCHttpTransport, arguments: Json.Obj): IO[YallmoError, Json]
}
