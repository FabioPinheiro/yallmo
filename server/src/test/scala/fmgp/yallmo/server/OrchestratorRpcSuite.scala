package fmgp.yallmo.server

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.{OrchestratorLive, RuntimeInstances, SessionId, SessionSummary}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import munit.FunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

final class OrchestratorRpcSuite extends FunSuite {
  private val executable = AgentId("user-runtime-agent")
  private val alice = AgentId("user-alice")
  private val bob = AgentId("user-bob")

  private def master: IO[YallmoError, OrchestratorLive[Any]] =
    RuntimeInstances
      .fromAgents(Chunk(new Agent[Any] {
        val id = executable
        def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
          ZIO.dieMessage("Viewer presence must never execute an agent")
      }))
      .flatMap(OrchestratorLive.inMemory(_))

  test("invalid params and registered-agent collisions never initialize or publish presence") {
    run(for {
      live <- master
      rpc = new OrchestratorRpc(live)
      observer <- rpc.openConnection
      _ <- init(rpc, observer, None)
      connection <- rpc.openConnection
      invalid <- rpc
        .handle(
          """{"jsonrpc":"2.0","id":"bad","method":"initialize","params":{"clientName":"test","userId":" "}}""",
          connection
        )
        .map(_.get)
      obsolete <- rpc
        .handle(
          """{"jsonrpc":"2.0","id":"obsolete","method":"initialize","params":{"clientName":"test","protocolVersion":1}}""",
          connection
        )
        .map(_.get)
      collision <- init(rpc, connection, Some(executable))
      unauthorized <- call(rpc, connection, AgentsList.method)
      ids <- agents(rpc, observer)
      _ <- rpc.disconnect(connection)
    } yield {
      assertEquals(error(invalid), JsonRpc.Error.InvalidParams)
      assertEquals(error(obsolete), JsonRpc.Error.InvalidParams)
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
      assertEquals(
        result.identity,
        Identity(
          UserId(AgentId.value(alice).stripPrefix("user-")),
          Role.Viewer,
          Chunk(alice, UserId.websiteAgent(UserId(AgentId.value(alice).stripPrefix("user-"))))
        )
      )
      assertEquals(
        result.methods.toSet,
        Set(
          "initialize",
          "sessions.list",
          "sessions.get",
          "messages.list",
          "agents.list",
          "blobs.list",
          "blobs.read",
          "events.subscribe",
          "events.unsubscribe"
        )
      )
      assertEquals(result.methods.size, 9)
      assertEquals(result.notifications, List(EventsUpdate.method))
      assertEquals(error(changed).code, ErrorCode.Conflict)
      assertEquals(error(removed).code, ErrorCode.Conflict)
      assertEquals(ids.toSet, Set(executable, alice, UserId.websiteAgent(UserId("alice"))))
      assertEquals(
        success[Initialize.Result](anonFirst).identity,
        Identity(UserId("local-viewer"), Role.Viewer, Chunk.empty)
      )
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
      assertEquals(bound.size, 3)
      assertEquals(error(closed).code, ErrorCode.Unauthorized)
      assertEquals(after, Chunk(executable))
      assertEquals(registered, Chunk(executable))
    })
  }

  test("graph reads require initialization; unsubscribe is scoped, idempotent and releases registry capacity") {
    run(for {
      live <- master
      rpc = new OrchestratorRpc(live)
      connection <- rpc.openConnection
      unauthorized <- rpc
        .handle(
          JsonRpc.Request(Json.Str("s"), EventsSubscribe.method, EventsSubscribe.Params("nonce")).toJson,
          connection
        )
        .map(_.get)
      _ <- init(rpc, connection, None)
      bad <- rpc
        .handle(
          """{"jsonrpc":"2.0","id":"bad","method":"events.subscribe","params":{"nonce":"n","all":true}}""",
          connection
        )
        .map(_.get)
      _ <- ZIO.foreachDiscard(1 to 140) { index =>
        for {
          reply <- rpc
            .handle(
              JsonRpc.Request(Json.Str("s"), EventsSubscribe.method, EventsSubscribe.Params(s"nonce-$index")).toJson,
              connection
            )
            .map(_.get)
          result = success[EventsSubscribe.Result](reply)
          _ <- rpc.handle(
            JsonRpc
              .Request(Json.Str("u"), EventsUnsubscribe.method, EventsUnsubscribe.Params(result.subscriptionId))
              .toJson,
            connection
          )
          _ <- rpc.handle(
            JsonRpc
              .Request(Json.Str("u2"), EventsUnsubscribe.method, EventsUnsubscribe.Params(result.subscriptionId))
              .toJson,
            connection
          )
        } yield ()
      }
      _ <- rpc.disconnect(connection)
      closed <- rpc
        .handle(
          JsonRpc.Request(Json.Str("s"), EventsSubscribe.method, EventsSubscribe.Params("closed")).toJson,
          connection
        )
        .map(_.get)
    } yield {
      assertEquals(error(unauthorized).code, ErrorCode.Unauthorized)
      assertEquals(error(bad), JsonRpc.Error.InvalidParams)
      assertEquals(error(closed).code, ErrorCode.Unauthorized)
    })
  }

  test("eventSessionSnapshot retains unreadable inventory metadata with an unknown head and sanitized error") {
    val summary = SessionSummary(SessionId.unsafe("broken"), 7L, Chunk(alice))
    val snapshot = OrchestratorRpc.eventSessionSnapshot(
      summary,
      Left(YallmoError.InvalidConfig("PRIVATE /server/path credential")),
      42L
    )
    assertEquals(snapshot.id, summary.id)
    assertEquals(snapshot.agentIds, summary.agentIds)
    assertEquals(snapshot.messageCount, 7L)
    assertEquals(snapshot.revision, 42L)
    assertEquals(snapshot.head, None)
    assertEquals(snapshot.error, Some("Session history unavailable"))
  }

  private def init(
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection,
      human: Option[AgentId]
  ): UIO[String] =
    rpc
      .handle(
        JsonRpc
          .Request(
            Json.Str("init"),
            Initialize.method,
            Initialize.Params("test", human.map(id => UserId(AgentId.value(id).stripPrefix("user-"))))
          )
          .toJson,
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
