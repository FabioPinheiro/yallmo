package fmgp.yallmo.server

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.{OrchestratorLive, RuntimeInstances}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import munit.FunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

final class OrchestratorRpcSuite extends FunSuite {
  private val executable = AgentId("runtime-agent")
  private val alice = AgentId("alice")
  private val bob = AgentId("bob")

  private def master: IO[YallmoError, OrchestratorLive[Any]] =
    RuntimeInstances
      .fromAgents(Chunk(new Agent[Any] {
        val id = executable
        def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
          ZIO.dieMessage("Viewer presence must never execute an agent")
      }))
      .flatMap(OrchestratorLive.inMemory(_))

  test("failed version, params and registered-agent collisions never initialize or publish presence") {
    run(for {
      live <- master
      rpc = new OrchestratorRpc(live)
      observer <- rpc.openConnection
      _ <- init(rpc, observer, None)
      connection <- rpc.openConnection
      version <- init(rpc, connection, Some(alice), 99)
      invalid <- rpc
        .handle(
          """{"jsonrpc":"2.0","id":"bad","method":"initialize","params":{"protocolVersion":1,"clientName":"test","humanId":" "}}""",
          connection
        )
        .map(_.get)
      collision <- init(rpc, connection, Some(executable))
      unauthorized <- call(rpc, connection, AgentsList.method)
      ids <- agents(rpc, observer)
      _ <- rpc.disconnect(connection)
    } yield {
      assertEquals(error(version).code, ErrorCode.UnsupportedVersion)
      assertEquals(error(invalid), JsonRpc.Error.InvalidParams)
      assertEquals(error(collision).code, ErrorCode.Conflict)
      assertEquals(error(unauthorized).code, ErrorCode.Unauthorized)
      assertEquals(ids, Chunk(executable))
    })
  }

  test("initialized identity is stable across same, changed, removed and anonymous repeated initialize") {
    run(for {
      live <- master
      rpc = new OrchestratorRpc(live)
      connection <- rpc.openConnection
      first <- init(rpc, connection, Some(alice))
      same <- init(rpc, connection, Some(alice))
      changed <- init(rpc, connection, Some(bob))
      removed <- init(rpc, connection, None)
      version <- init(rpc, connection, Some(alice), 99)
      ids <- agents(rpc, connection)
      anonymous <- rpc.openConnection
      anonFirst <- init(rpc, anonymous, None)
      anonAgain <- init(rpc, anonymous, None)
      added <- init(rpc, anonymous, Some(bob))
      _ <- rpc.disconnect(connection)
      _ <- rpc.disconnect(anonymous)
    } yield {
      val result = success[Initialize.Result](first)
      assertEquals(success[Initialize.Result](same), result)
      assertEquals(result.identity, Identity(alice, Role.Viewer))
      assertEquals(
        result.methods.toSet,
        Set("initialize", "sessions.list", "sessions.get", "messages.list", "agents.list", "blobs.list", "blobs.read")
      )
      assertEquals(result.methods.size, 7)
      assertEquals(result.notifications, Nil)
      assertEquals(error(changed).code, ErrorCode.Conflict)
      assertEquals(error(removed).code, ErrorCode.Conflict)
      assertEquals(error(version).code, ErrorCode.UnsupportedVersion)
      assertEquals(ids.toSet, Set(executable, alice))
      assertEquals(success[Initialize.Result](anonFirst).identity, Identity(AgentId("local-viewer"), Role.Viewer))
      assertEquals(success[Initialize.Result](anonAgain), success[Initialize.Result](anonFirst))
      assertEquals(error(added).code, ErrorCode.Conflict)
    })
  }

  test("concurrent initialization binds one identity and disconnect cannot be followed by re-publication") {
    run(for {
      live <- master
      rpc = new OrchestratorRpc(live)
      observer <- rpc.openConnection
      _ <- init(rpc, observer, None)
      connection <- rpc.openConnection
      replies <- ZIO.foreachPar(List(alice, bob))(id => init(rpc, connection, Some(id)))
      bound <- agents(rpc, observer)
      _ <- rpc.disconnect(connection).zipPar(init(rpc, connection, Some(alice)))
      closed <- init(rpc, connection, Some(bob))
      after <- agents(rpc, observer)
      registered <- live.listAgentIds
      _ <- rpc.disconnect(observer)
    } yield {
      assertEquals(replies.count(_.fromJson[JsonRpc.Success[Initialize.Result]].isRight), 1)
      assertEquals(replies.count(_.fromJson[JsonRpc.Failure].toOption.exists(_.error.code == ErrorCode.Conflict)), 1)
      assertEquals(bound.size, 2)
      assertEquals(error(closed).code, ErrorCode.Unauthorized)
      assertEquals(after, Chunk(executable))
      assertEquals(registered, Chunk(executable))
    })
  }

  private def init(
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection,
      human: Option[AgentId],
      version: Int = Version
  ): UIO[String] =
    rpc
      .handle(
        JsonRpc.Request(Json.Str("init"), Initialize.method, Initialize.Params(version, "test", human)).toJson,
        connection
      )
      .map(_.get)

  private def call(rpc: OrchestratorRpc, connection: OrchestratorRpc.Connection, method: String): UIO[String] =
    rpc.handle(JsonRpc.Request(Json.Str("read"), method, Empty()).toJson, connection).map(_.get)

  private def agents(rpc: OrchestratorRpc, connection: OrchestratorRpc.Connection): UIO[Chunk[AgentId]] =
    call(rpc, connection, AgentsList.method).map(success[Chunk[AgentId]])

  private def error(reply: String): JsonRpc.Error = reply.fromJson[JsonRpc.Failure].fold(fail(_), _.error)
  private def success[A: JsonCodec](reply: String): A = reply.fromJson[JsonRpc.Success[A]].fold(fail(_), _.result)

  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new AssertionError("RPC test timed out"))(5.seconds))
      .getOrThrowFiberFailure()
  }
}
