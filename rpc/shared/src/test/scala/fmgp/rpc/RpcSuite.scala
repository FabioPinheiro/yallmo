package fmgp.rpc

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import zio.*
import zio.json.*
import zio.json.ast.Json

object RpcFixtures {
  final case class Greeting(message: String, count: Int) derives JsonCodec
  trait Numbers {
    def add(left: Int, right: Int): Task[Int]
    def reset(): Task[Unit]
    def current: Task[Int]
  }
  trait GreetingBase {
    def inherited(): Task[String]
  }
  trait Greetings extends GreetingBase {
    def hello(name: String, count: Int): Task[Greeting]
    def repeat(input: Greeting): Task[Greeting]
    def acknowledge(value: Unit): Task[Unit]
  }
  val numbers: Rpc[Numbers] = Rpc.derived[Numbers]("example.numbers", 1)
  val greetings: Rpc[Greetings] = Rpc.derived[Greetings]
  val numberService: Numbers = new Numbers {
    def add(left: Int, right: Int): Task[Int] = ZIO.succeed(left + right)
    def reset(): Task[Unit] = ZIO.unit
    def current: Task[Int] = ZIO.succeed(7)
  }
  val greetingService: Greetings = new Greetings {
    def inherited(): Task[String] = ZIO.succeed("inherited")
    def hello(name: String, count: Int): Task[Greeting] = ZIO.succeed(Greeting(name, count))
    def repeat(input: Greeting): Task[Greeting] = ZIO.succeed(input)
    def acknowledge(value: Unit): Task[Unit] = ZIO.succeed(value)
  }
}

class RpcSuite extends FunSuite {
  import RpcFixtures.*
  def run[A](effect: Task[A]): A = Unsafe.unsafe { _ ?=>
    Runtime.default.unsafe.run(effect.timeoutFail(new Exception("test deadline"))(10.seconds)).getOrThrowFiberFailure()
  }
  test("generated host and typed client support multiple, zero and parameterless args, Unit and DTOs") {
    val dispatcher = numbers.bind(numberService).merge(greetings.bind(greetingService)).toOption.get
    val connection = new RpcConnection {
      def call(serviceId: String, version: Int, method: String, arguments: Chunk[Json]): Task[Json] =
        dispatcher.dispatch(serviceId, version, method, arguments)
      def close: UIO[Unit] = ZIO.unit
    }
    val math = numbers.client(connection)
    val greeter = greetings.client(connection)
    assertEquals(run(math.add(4, 5)), 9)
    assertEquals(run(math.current), 7)
    assertEquals(run(math.reset()), ())
    assertEquals(run(greeter.hello("hello", 2)), Greeting("hello", 2))
    assertEquals(run(greeter.repeat(Greeting("dto", 3))), Greeting("dto", 3))
    assertEquals(run(greeter.acknowledge(())), ())
    assertEquals(run(greeter.inherited()), "inherited")
    assertEquals(greetings.descriptor.serviceId, "fmgp.rpc.RpcFixtures$.Greetings")
  }
  test("routing and malformed argument failures remain explicit; merging duplicate IDs fails") {
    val dispatcher = numbers.bind(numberService)
    assertEquals(dispatcher.merge(dispatcher).left.toOption, Some(RpcError(RpcError.Code.DuplicateService)))
    val versionTwo = Rpc.derived[Numbers]("example.numbers", 2).bind(numberService)
    assertEquals(dispatcher.merge(versionTwo).left.toOption, Some(RpcError(RpcError.Code.DuplicateService)))
    val invalid = run(dispatcher.dispatch("example.numbers", 1, "add", Chunk(Json.Str("bad"), Json.Num(2))).either)
    assertEquals(invalid, Left(RpcError(RpcError.Code.InvalidArguments)))
    assertEquals(
      run(dispatcher.dispatch("example.numbers", 1, "add", Chunk.empty).either),
      Left(RpcError(RpcError.Code.InvalidArguments))
    )
    assertEquals(
      run(dispatcher.dispatch("missing", 1, "add", Chunk.empty).either),
      Left(RpcError(RpcError.Code.UnknownService))
    )
    assertEquals(
      run(dispatcher.dispatch("example.numbers", 1, "missing", Chunk.empty).either),
      Left(RpcError(RpcError.Code.UnknownMethod))
    )
    assertEquals(
      run(dispatcher.dispatch("example.numbers", 2, "current", Chunk.empty).either),
      Left(RpcError(RpcError.Code.VersionMismatch))
    )
  }
  test("wire bounds nesting, duplicate keys, unknown fields and ambiguous responses") {
    assert(RpcWire.request("[" * 33 + "]" * 33).isLeft)
    assert(
      RpcWire
        .request("""{"id":"1","id":"2","service":"example.numbers","version":1,"method":"current","arguments":[]}""")
        .isLeft
    )
    assert(RpcWire.response("""{"id":"1","result":{"x":1,"x":2}}""").isLeft)
    assert(RpcWire.response("""{"id":"1","result":1,"error":"ServiceFailure"}""").isLeft)
    assert(RpcWire.response("""{"id":"1","result":1,"error":null}""").isLeft)
    assert(RpcWire.request("""{"id":"1","service":"example.numbers","version":1,"method":"current"}""").isLeft)
    assert(RpcWire.response("""{"id":"1","result":1,"unexpected":true}""").isLeft)
    assertEquals(RpcWire.response(RpcWire.success("1", Json.Null)).toOption.flatMap(_.result), Some(Json.Null))
  }
  test("macro rejects unsupported argument lists, generic traits, concrete members and missing codecs") {
    val curried = typeCheckErrors("""
      trait Bad { def call(a: Int)(b: Int): zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val contextual = typeCheckErrors("""
      trait Bad { def call(using a: Int): zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val byName = typeCheckErrors("""
      trait Bad { def call(a: => Int): zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val repeated = typeCheckErrors("""
      trait Bad { def call(a: Int*): zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val generic = typeCheckErrors("""
      trait Bad[A] { def call(a: A): zio.Task[A] }
      fmgp.rpc.Rpc.derived[Bad[Int]]
    """)
    val concrete = typeCheckErrors("""
      trait Bad { def call(): zio.Task[Int] = zio.ZIO.succeed(1) }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val defaults = typeCheckErrors("""
      trait Bad { def call(a: Int = 1): zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val notTrait = typeCheckErrors("""
      abstract class Bad { def call(): zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val concreteField = typeCheckErrors("""
      trait Bad { val value: Int = 1; def call(): zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val typeMember = typeCheckErrors("""
      trait Parent { type Member }
      trait Bad extends Parent { def call(): zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val codec = typeCheckErrors("""
      class NoCodec
      trait Bad { def call(): zio.Task[NoCodec] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    assert(curried.exists(_.message.contains("multiple parameter lists")), curried.toString)
    assert(contextual.exists(_.message.contains("contextual parameters")), contextual.toString)
    assert(byName.exists(_.message.contains("by-name")), byName.toString)
    assert(repeated.exists(_.message.contains("repeated parameters")), repeated.toString)
    assert(generic.exists(_.message.contains("non-generic")), generic.toString)
    assert(concrete.exists(_.message.contains("concrete service methods")), concrete.toString)
    assert(codec.exists(_.message.contains("JSON encoder and decoder")), codec.toString)
    assert(defaults.exists(_.message.contains("default parameters")), defaults.toString)
    assert(notTrait.exists(_.message.contains("non-generic service trait")), notTrait.toString)
    assert(concreteField.exists(_.message.contains("vals/vars")), concreteField.toString)
    assert(typeMember.exists(_.message.contains("type members")), typeMember.toString)
  }
  test("macro rejects polymorphic, overloaded, callback, environment, typed error and val contracts") {
    val polymorphic = typeCheckErrors("""
      trait Bad { def apply[A](a: A): zio.Task[A] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val overloaded = typeCheckErrors("""
      trait Bad { def call(a: Int): zio.Task[Int]; def call(a: String): zio.Task[String] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val callback = typeCheckErrors("""
      trait Bad { def call(a: Int => Int): zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val environment = typeCheckErrors("""
      trait Bad { def call(): zio.ZIO[String, Throwable, Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val typedError = typeCheckErrors("""
      trait Bad { def call(): zio.IO[String, Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    val field = typeCheckErrors("""
      trait Bad { val call: zio.Task[Int] }
      fmgp.rpc.Rpc.derived[Bad]
    """)
    assert(polymorphic.exists(_.message.contains("polymorphic")), polymorphic.toString)
    assert(overloaded.exists(_.message.contains("overloaded")), overloaded.toString)
    assert(callback.exists(_.message.contains("callbacks")), callback.toString)
    assert(environment.exists(_.message.contains("expected Task")), environment.toString)
    assert(typedError.exists(_.message.contains("expected Task")), typedError.toString)
    assert(field.exists(_.message.contains("vals/vars")), field.toString)
  }
}
