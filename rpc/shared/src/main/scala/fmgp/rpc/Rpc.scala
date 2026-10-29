package fmgp.rpc

import zio.*
import zio.json.*
import zio.json.ast.Json

/** Sanitized failures: neither service exception messages nor raw transport details cross the API. */
final case class RpcError(code: RpcError.Code) extends Exception(code.toString)
object RpcError {
  enum Code derives JsonCodec {
    case UnknownService, UnknownMethod, VersionMismatch, InvalidArguments, ServiceFailure
    case ProtocolError, Disconnected, TimedOut, LimitExceeded, DuplicateService, Unauthorized
  }
}

final case class RpcDescriptor(serviceId: String, version: Int, methods: Set[String])

/** One connection is shared by any number of generated clients. No retry or reconnect is implied. */
trait RpcConnection {
  def call(serviceId: String, version: Int, method: String, arguments: Chunk[Json]): Task[Json]
  def close: UIO[Unit]
}

/** A concrete trait's once-derived contract; transports do not participate in derivation. */
final class Rpc[A](
    val descriptor: RpcDescriptor,
    handlers: A => Map[String, Chunk[Json] => IO[RpcError, Json]],
    makeClient: RpcConnection => A
) {
  def bind(service: A): RpcDispatcher = RpcDispatcher.single(descriptor, handlers(service))
  def client(connection: RpcConnection): A = makeClient(connection)
}
object Rpc {

  /** Default wire identity is the fully qualified trait name, version 1. Renaming is a wire change. */
  inline def derived[A]: Rpc[A] = ${ RpcMacros.deriveDefault[A] }

  /** Use literal, stable application-owned IDs and bump version for every incompatible contract change. */
  inline def derived[A](inline serviceId: String, inline version: Int): Rpc[A] =
    ${ RpcMacros.derive[A]('serviceId, 'version) }
}

/** Validates routing before executing any host code. Duplicate IDs are rejected, even across versions. */
final class RpcDispatcher private (private val services: Map[String, RpcDispatcher.Service]) {
  def merge(other: RpcDispatcher): Either[RpcError, RpcDispatcher] =
    if services.keySet.intersect(other.services.keySet).nonEmpty then Left(RpcError(RpcError.Code.DuplicateService))
    else Right(new RpcDispatcher(services ++ other.services))

  def dispatch(serviceId: String, version: Int, method: String, arguments: Chunk[Json]): IO[RpcError, Json] =
    services.get(serviceId) match {
      case None                                                   => ZIO.fail(RpcError(RpcError.Code.UnknownService))
      case Some(service) if service.descriptor.version != version =>
        ZIO.fail(RpcError(RpcError.Code.VersionMismatch))
      case Some(service) =>
        service.handlers.get(method) match {
          case None      => ZIO.fail(RpcError(RpcError.Code.UnknownMethod))
          case Some(run) =>
            ZIO.suspendSucceed(run(arguments)).catchAllCause { cause =>
              if cause.isInterruptedOnly then ZIO.refailCause(cause)
              else
                cause.failureOption match {
                  case Some(error) => ZIO.fail(error)
                  case None        => ZIO.fail(RpcError(RpcError.Code.ServiceFailure))
                }
            }
        }
    }
}
object RpcDispatcher {
  private final case class Service(
      descriptor: RpcDescriptor,
      handlers: Map[String, Chunk[Json] => IO[RpcError, Json]]
  )
  private[rpc] def single(
      descriptor: RpcDescriptor,
      handlers: Map[String, Chunk[Json] => IO[RpcError, Json]]
  ): RpcDispatcher =
    new RpcDispatcher(Map(descriptor.serviceId -> Service(descriptor, handlers)))
}

/** Public only for generated code at external call sites. */
object RpcJson {
  def encode[A: JsonEncoder](value: A): Task[Json] =
    ZIO
      .attempt(summon[JsonEncoder[A]].toJsonAST(value))
      .flatMap(ZIO.fromEither(_))
      .mapError(_ => RpcError(RpcError.Code.ProtocolError))
  def decode[A: JsonDecoder](value: Json, code: RpcError.Code): IO[RpcError, A] =
    ZIO.attempt(summon[JsonDecoder[A]].fromJsonAST(value)).flatMap(ZIO.fromEither(_)).mapError(_ => RpcError(code))
  def unit(value: Json, code: RpcError.Code): IO[RpcError, Unit] =
    ZIO.fail(RpcError(code)).unless(value == Json.Null).unit
  def arity(arguments: Chunk[Json], expected: Int): IO[RpcError, Unit] =
    ZIO.fail(RpcError(RpcError.Code.InvalidArguments)).unless(arguments.size == expected).unit
  def hostResult(effect: => Task[Json]): IO[RpcError, Json] =
    ZIO.suspendSucceed(effect).catchAllCause { cause =>
      if cause.isInterruptedOnly then ZIO.interrupt
      else ZIO.fail(RpcError(RpcError.Code.ServiceFailure))
    }
}
