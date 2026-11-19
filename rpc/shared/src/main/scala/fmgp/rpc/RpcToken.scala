package fmgp.rpc

/** Single-use connection ticket. Export only over a trusted bootstrap channel; default rendering is redacted. */
final class RpcToken private (private val secret: String) {
  def exportSecret: String = secret
  override def toString: String = "RpcToken(<redacted>)"
}
object RpcToken {

  /** Canonical unpadded base64url for 32 bytes: the final character has two zero padding bits. No host authority. */
  def parse(secret: String): Either[RpcError, RpcToken] =
    if secret != null && secret.matches("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]") then Right(new RpcToken(secret))
    else Left(RpcError(RpcError.Code.Unauthorized))
}

private[rpc] object RpcHandshake {
  val Protocol = "yallmo.rpc.v2"
  private val TicketPrefix = "yallmo.ticket."
  def offer(token: RpcToken): String = s"$Protocol, $TicketPrefix${token.exportSecret}"

  /** Reject duplicate header fields, protocol offers and any unknown/conflicting credential. */
  def parse(headers: List[String]): Either[RpcError, RpcToken] = headers match {
    case List(header) =>
      val protocols = header.split(",", -1).toList.map(_.trim)
      protocols match {
        case List(first, second) if first == Protocol && second.startsWith(TicketPrefix) =>
          RpcToken.parse(second.stripPrefix(TicketPrefix))
        case List(first, second) if second == Protocol && first.startsWith(TicketPrefix) =>
          RpcToken.parse(first.stripPrefix(TicketPrefix))
        case _ => Left(RpcError(RpcError.Code.Unauthorized))
      }
    case _ => Left(RpcError(RpcError.Code.Unauthorized))
  }
}
