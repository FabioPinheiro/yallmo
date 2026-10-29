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

/** Total, pure policy for translating infrastructure failures into a service's exact error type. Implementations must
  * handle every code and must not throw; throwing is a programmer defect. Domain failures are decoded directly and
  * never passed through this policy.
  */
trait RpcErrorMapper[E] {
  def apply(error: RpcError): E
}
object RpcErrorMapper {
  def apply[E](map: RpcError => E): RpcErrorMapper[E] = new RpcErrorMapper[E] {
    def apply(error: RpcError): E = map(error)
  }
  given RpcErrorMapper[Throwable] = RpcErrorMapper(error => error)
}

/** Service outcomes only; routing, codec and transport errors remain in the RpcError channel. */
enum RpcResult {
  case Success(value: Json)
  case Failure(value: Json)
}

final case class RpcDescriptor(serviceId: String, version: Int, methods: Set[String])

/** One connection is shared by any number of generated clients. No retry or reconnect is implied.
  *
  * Service outcomes are returned as RpcResult; only sanitized infrastructure failures use the error channel. This
  * revision intentionally replaces the former Task[Json] boundary.
  */
trait RpcConnection {
  def call(serviceId: String, version: Int, method: String, arguments: Chunk[Json]): IO[RpcError, RpcResult]
  def close: UIO[Unit]
}

/** A concrete trait's once-derived contract; transports do not participate in derivation. Methods must return ZIO[Any,
  * E, A]. Except for exactly Throwable (sanitized ServiceFailure, no codec required), E needs JSON codecs and an
  * explicit total RpcErrorMapper[E]. Codec-approved domain errors are not redacted and may appear in transport TRACE
  * logs. Nothing, environment requirements and stream methods are unsupported.
  */
final class Rpc[A](
    val descriptor: RpcDescriptor,
    handlers: A => Map[String, Chunk[Json] => IO[RpcError, RpcResult]],
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

  /** Routes a call, keeping encoded domain failures separate from infrastructure errors. */
  def dispatch(serviceId: String, version: Int, method: String, arguments: Chunk[Json]): IO[RpcError, RpcResult] =
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
      handlers: Map[String, Chunk[Json] => IO[RpcError, RpcResult]]
  )
  private[rpc] def single(
      descriptor: RpcDescriptor,
      handlers: Map[String, Chunk[Json] => IO[RpcError, RpcResult]]
  ): RpcDispatcher =
    new RpcDispatcher(Map(descriptor.serviceId -> Service(descriptor, handlers)))
}

/** Public only for generated code at external call sites. */
object RpcJson {
  def encode[A: JsonEncoder](value: A): IO[RpcError, Json] =
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
  def hostResult[E, A](
      effect: => IO[E, A],
      failure: E => IO[RpcError, RpcResult],
      success: A => IO[RpcError, Json]
  ): IO[RpcError, RpcResult] =
    ZIO
      .suspendSucceed(effect)
      .foldCauseZIO(
        cause =>
          if cause.isInterrupted then ZIO.interrupt
          else if cause.defects.nonEmpty then ZIO.fail(RpcError(RpcError.Code.ServiceFailure))
          else
            cause.failureOption match {
              case Some(error) => failure(error)
              case None        => ZIO.fail(RpcError(RpcError.Code.ServiceFailure))
            }
        ,
        value => success(value).map(RpcResult.Success(_))
      )
}
