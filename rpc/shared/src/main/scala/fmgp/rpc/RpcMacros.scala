package fmgp.rpc

import scala.quoted.*
import zio.*
import zio.json.*
import zio.json.ast.Json

object RpcMacros {
  def deriveDefault[A: Type](using Quotes): Expr[Rpc[A]] = {
    import quotes.reflect.*
    derive[A](Expr(TypeRepr.of[A].typeSymbol.fullName), Expr(1))
  }

  def derive[A: Type](id: Expr[String], version: Expr[Int])(using Quotes): Expr[Rpc[A]] = {
    import quotes.reflect.*
    val serviceType = TypeRepr.of[A].dealias
    val symbol = serviceType.typeSymbol
    def reject(message: String): Nothing = report.errorAndAbort(s"Rpc[${serviceType.show}]: $message")
    val serviceId = id.valueOrAbort
    val serviceVersion = version.valueOrAbort
    if !serviceId.matches("[A-Za-z0-9_$.-]{1,160}") || serviceVersion <= 0 then
      reject("service ID must be 1-160 letters/digits/._$- and version must be positive")
    if !symbol.flags.is(Flags.Trait) || serviceType.typeArgs.nonEmpty then
      reject("expected a non-generic service trait")
    if serviceType.baseClasses.filter(_.flags.is(Flags.Trait)).exists(_.declaredTypes.nonEmpty) then
      reject("abstract or nested type members are unsupported")
    if symbol.fieldMembers.nonEmpty then reject("vals/vars are unsupported; use Task methods")
    if symbol.methodMembers.exists(_.paramSymss.flatten.exists(_.flags.is(Flags.HasDefault))) then
      reject("default parameters are unsupported")
    if symbol.methodMembers.exists(m =>
        m.owner.flags.is(Flags.Trait) && !m.flags.is(Flags.Deferred) &&
          !m.flags.is(Flags.Synthetic) && !m.isClassConstructor
      )
    then reject("concrete service methods are unsupported; use abstract Task methods")
    val methods = symbol.methodMembers.filter(_.flags.is(Flags.Deferred)).sortBy(_.name)
    if methods.isEmpty then reject("expected at least one abstract Task method")
    if methods.map(_.name).distinct.size != methods.size then reject("overloaded methods are unsupported")

    final case class Method(symbol: Symbol, parameters: List[TypeRepr], result: TypeRepr)
    def checkCodec(tpe: TypeRepr, member: String): Unit = {
      if tpe.dealias.isFunctionType || tpe.dealias.isContextFunctionType ||
        (0 to 22).exists(arity => tpe.baseClasses.contains(defn.FunctionClass(arity)))
      then reject(s"$member: callbacks are unsupported")
      if tpe =:= TypeRepr.of[Unit] then ()
      else
        tpe.asType match {
          case '[t] =>
            if Expr.summon[JsonEncoder[t]].isEmpty || Expr.summon[JsonDecoder[t]].isEmpty then
              reject(s"$member requires JSON encoder and decoder for ${tpe.show}; callbacks are unsupported")
        }
    }
    val checked = methods.map { method =>
      if method.flags.is(Flags.Protected) || method.flags.is(Flags.Private) then
        reject(s"${method.name}: service methods must be public")
      if method.name.length > 160 then reject(s"${method.name}: method names must be at most 160 characters")
      val member = serviceType.memberType(method).dealias
      val (params, result) = member match {
        case _: PolyType    => reject(s"${method.name}: polymorphic methods are unsupported")
        case mt: MethodType =>
          if mt.isImplicit then reject(s"${method.name}: contextual parameters are unsupported")
          mt.resType match {
            case _: MethodType => reject(s"${method.name}: multiple parameter lists are unsupported")
            case _             => ()
          }
          (mt.paramTypes, mt.resType.dealias)
        case byName: ByNameType => (Nil, byName.underlying.dealias)
        case other              => (Nil, other)
      }
      if params.size > 64 then reject(s"${method.name}: at most 64 arguments are supported")
      params.foreach { param =>
        param match {
          case _: ByNameType => reject(s"${method.name}: by-name parameters are unsupported")
          case _             => ()
        }
        if param.typeSymbol == defn.RepeatedParamClass then
          reject(s"${method.name}: repeated parameters are unsupported")
        checkCodec(param, method.name)
      }
      val value = result match {
        case AppliedType(base, List(env, error, value))
            if base.typeSymbol == TypeRepr.of[ZIO].typeSymbol &&
              env =:= TypeRepr.of[Any] && error =:= TypeRepr.of[Throwable] =>
          value
        case _ =>
          reject(
            s"${method.name}: expected Task[A] (no environment requirements or typed errors), found ${result.show}"
          )
      }
      checkCodec(value, method.name)
      Method(method, params, value)
    }

    def encode(tpe: TypeRepr, value: Term): Expr[Task[Json]] =
      if tpe =:= TypeRepr.of[Unit] then '{ ZIO.succeed(Json.Null) }
      else
        tpe.asType match {
          case '[t] =>
            val encoder = Expr.summon[JsonEncoder[t]].get
            '{ RpcJson.encode[t](${ value.asExprOf[t] })(using $encoder) }
        }
    def decode(tpe: TypeRepr, value: Expr[Json], code: Expr[RpcError.Code]): Term =
      if tpe =:= TypeRepr.of[Unit] then '{ RpcJson.unit($value, $code) }.asTerm
      else
        tpe.asType match {
          case '[t] =>
            val decoder = Expr.summon[JsonDecoder[t]].get
            '{ RpcJson.decode[t]($value, $code)(using $decoder) }.asTerm
        }

    def host(service: Expr[A]): Expr[Map[String, Chunk[Json] => IO[RpcError, Json]]] = {
      val entries = checked.map { method =>
        def body(args: Expr[Chunk[Json]]): Expr[IO[RpcError, Json]] = {
          def loop(remaining: List[TypeRepr], values: List[Term], index: Int): Expr[IO[RpcError, Json]] =
            remaining match {
              case Nil =>
                val selected = Select(service.asTerm, method.symbol)
                val call = serviceType.memberType(method.symbol) match {
                  case _: MethodType => Apply(selected, values)
                  case _             => selected
                }
                method.result.asType match {
                  case '[r] =>
                    '{
                      RpcJson.hostResult(${
                        call.asExprOf[Task[r]]
                      }.flatMap(value => ${ encode(method.result, 'value.asTerm) }))
                    }
                }
              case head :: tail =>
                head.asType match {
                  case '[t] =>
                    val decoded = decode(head, '{ $args(${ Expr(index) }) }, '{ RpcError.Code.InvalidArguments })
                      .asExprOf[IO[RpcError, t]]
                    '{ $decoded.flatMap(value => ${ loop(tail, values :+ 'value.asTerm, index + 1) }) }
                }
            }
          '{ RpcJson.arity($args, ${ Expr(method.parameters.size) }) *> ${ loop(method.parameters, Nil, 0) } }
        }
        '{ (${ Expr(method.symbol.name) }, (args: Chunk[Json]) => ${ body('args) }) }
      }
      '{ Map.from(${ Expr.ofList(entries) }) }
    }

    def client(connection: Expr[RpcConnection]): Expr[A] = {
      val parents = List(TypeRepr.of[Object], serviceType)
      var generated = List.empty[Symbol]
      val cls = Symbol.newClass(
        Symbol.spliceOwner,
        "RpcClient",
        parents,
        cls => {
          generated = checked.map(m =>
            Symbol.newMethod(cls, m.symbol.name, serviceType.memberType(m.symbol), Flags.Override, Symbol.noSymbol)
          )
          generated
        },
        None
      )
      val definitions = checked.zip(generated).map { (method, implementation) =>
        DefDef(
          implementation,
          paramLists => {
            val parameters = paramLists.flatten.map(_.asInstanceOf[Term])
            val encoded = Expr.ofList(method.parameters.zip(parameters).map((tpe, arg) => encode(tpe, arg)))
            val call = method.result.asType match {
              case '[r] =>
                '{
                  ZIO
                    .collectAll($encoded)
                    .flatMap { values =>
                      $connection.call($id, $version, ${ Expr(method.symbol.name) }, Chunk.fromIterable(values))
                    }
                    .flatMap(value =>
                      ${ decode(method.result, 'value, '{ RpcError.Code.ProtocolError }).asExprOf[Task[r]] }
                    )
                }.asTerm
            }
            Some(call.changeOwner(implementation))
          }
        )
      }
      val definition = ClassDef(cls, List(TypeTree.of[Object], TypeTree.of[A]), definitions)
      val instance = Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil)
      Block(List(definition), instance).asExprOf[A]
    }

    val names = Expr.ofList(checked.map(m => Expr(m.symbol.name)))
    '{
      new Rpc[A](
        RpcDescriptor($id, $version, $names.toSet),
        (service: A) => ${ host('service) },
        (connection: RpcConnection) => ${ client('connection) }
      )
    }
  }
}
