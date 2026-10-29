package fmgp.rpc

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import zio.*

/** Single-use bearer connection ticket. Export only over a trusted bootstrap channel. */
final class RpcToken private (private val secret: String) {
  def exportSecret: String = secret
  override def toString: String = "RpcToken(<redacted>)"
  private[rpc] def digest: String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(
      MessageDigest.getInstance("SHA-256").digest(secret.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    )
}

object RpcToken {

  /** Strict canonical 256-bit URL-safe encoding; failures never contain the input. */
  def parse(secret: String): Either[RpcError, RpcToken] =
    if secret == null || !secret.matches("[A-Za-z0-9_-]{43}") then Left(RpcError(RpcError.Code.Unauthorized))
    else
      try {
        val bytes = Base64.getUrlDecoder.decode(secret)
        if bytes.length == 32 && Base64.getUrlEncoder.withoutPadding.encodeToString(bytes) == secret then
          Right(new RpcToken(secret))
        else Left(RpcError(RpcError.Code.Unauthorized))
      } catch { case _: IllegalArgumentException => Left(RpcError(RpcError.Code.Unauthorized)) }

  private[rpc] def generate: IO[RpcError, RpcToken] =
    ZIO
      .attempt {
        val bytes = new Array[Byte](32)
        new SecureRandom().nextBytes(bytes)
        new RpcToken(Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
      }
      .mapError(_ => RpcError(RpcError.Code.ServiceFailure))
}

/** Pending tickets only: expiry/revocation never affect established connections. */
private[rpc] final class RpcTickets private (
    state: Ref[RpcTickets.State],
    config: RpcWebSocket.AuthConfig,
    now: UIO[Long]
) {
  import RpcTickets.*
  def register(dispatcher: RpcDispatcher, ttl: Duration): IO[RpcError, RpcToken] =
    if ttl <= Duration.Zero || ttl > config.maxTtl then ZIO.fail(RpcError(RpcError.Code.InvalidArguments))
    else
      for {
        token <- RpcToken.generate
        time <- now
        result <- state.modify { current =>
          val live = current.tickets.filter { case (_, ticket) => time - ticket.expires < 0 }
          if current.closed then (Left(RpcError(RpcError.Code.Disconnected)), current)
          else if live.size >= config.maxPendingTickets then
            (Left(RpcError(RpcError.Code.LimitExceeded)), current.copy(tickets = live))
          else
            (Right(token), current.copy(tickets = live.updated(token.digest, Ticket(dispatcher, time + ttl.toNanos))))
        }
        value <- ZIO.fromEither(result)
      } yield value

  def redeem(token: RpcToken): IO[RpcError, RpcDispatcher] =
    for {
      time <- now
      result <- state.modify { current =>
        val live = current.tickets.filter { case (_, ticket) => time - ticket.expires < 0 }
        val dispatcher = if current.closed then None else live.get(token.digest).map(_.dispatcher)
        (dispatcher.toRight(RpcError(RpcError.Code.Unauthorized)), current.copy(tickets = live - token.digest))
      }
      dispatcher <- ZIO.fromEither(result)
    } yield dispatcher

  def revoke(token: RpcToken): UIO[Boolean] =
    for {
      time <- now
      removed <- state.modify { current =>
        val live = current.tickets.filter { case (_, ticket) => time - ticket.expires < 0 }
        (live.contains(token.digest), current.copy(tickets = live - token.digest))
      }
    } yield removed

  def close: UIO[Unit] = state.set(State(Map.empty, closed = true))
}
private[rpc] object RpcTickets {
  private final case class Ticket(dispatcher: RpcDispatcher, expires: Long)
  private final case class State(tickets: Map[String, Ticket], closed: Boolean = false)
  def make(config: RpcWebSocket.AuthConfig, now: UIO[Long] = Clock.nanoTime): UIO[RpcTickets] =
    Ref.make(State(Map.empty)).map(new RpcTickets(_, config, now))
}
