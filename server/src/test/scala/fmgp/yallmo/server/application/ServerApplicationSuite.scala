package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.{SessionId, ExternalToolResult}
import fmgp.yallmo.orchestration.config.{
  AgentCapabilities,
  AgentConfig,
  Config,
  FileSystemConfig,
  ProviderConfig,
  ProviderId
}
import fmgp.yallmo.server.{OrchestratorRpc, OrchestratorServerMain, OrchestratorWSClient, OrchestratorWSServer}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.tool.*
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import munit.FunSuite
import zio.{Config as _, *}
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

final class ServerApplicationSuite extends FunSuite {
  private val first = AgentId("first")
  private val second = AgentId("second")
  private val provider = ProviderId.unsafe("local")
  private val config = Config(
    // Deliberately unresolvable: startup/listing must not select configured agents.
    agents = Map(first -> AgentConfig("Unsupported"), second -> AgentConfig("Unsupported"))
  )

  test("configured options append to legacy positional defaults and accept reordered/equal syntax") {
    import OrchestratorServerMain.{Options, parse}
    assertEquals(parse(List("--storage", "sessions")), Right(Options(Path.of("sessions"))))
    assertEquals(
      parse(List("--workspace=work", "--log-level", "debug", "--config", "private.json", "--storage=sessions")),
      Right(Options(Path.of("sessions"), 8080, LogLevel.Debug, Some(Path.of("private.json")), Some(Path.of("work"))))
    )
    assertEquals(
      parse(List("--storage=sessions", "--config=private.json")).map(_.workspace),
      Right(None)
    )
  }

  test("invalid, missing and duplicate configured options fail without echoing their values") {
    List(
      List("--storage=sessions", "--workspace=PRIVATE"),
      List("--storage=sessions", "--config"),
      List("--storage=sessions", "--config="),
      List("--storage=sessions", "--config=PRIVATE", "--config=OTHER"),
      List("--storage=sessions", "--config=PRIVATE", "--workspace"),
      List("--storage=sessions", "--config=PRIVATE", "--workspace=one", "--workspace=two"),
      List("--storage=sessions", "--config=PRIVATE\u0000")
    ).foreach { args =>
      val result = OrchestratorServerMain.parse(args)
      assert(result.isLeft)
      assert(!result.swap.toOption.get.contains("PRIVATE"))
    }
  }

  test("viewer mode keeps an empty registry without loading configuration or probing tools") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        services <- probes()
        application <- ServerApplication.open(root).provideSomeLayer[Scope](services.layer)
        ids <- application.master.listAgentIds
        calls <- services.calls.get
      } yield {
        assertEquals(ids, Chunk.empty)
        assertEquals(application.configured, None)
        assertEquals(calls, Vector.empty)
        assert(Files.exists(root.resolve(".yallmo-server.lock")))
        assert(!Files.exists(root.resolve("config.json")))
      }
    })
  }

  test(
    "configured startup retains the same assembly master without a startup human or running anything"
  ) {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        path <- writeConfig(
          root,
          config.copy(
            agents = config.agents.updated(first, AgentConfig("OAIAgent", Seq(provider))),
            providers = Map(provider -> ProviderConfig.OAI("http://127.0.0.1:1/v1"))
          )
        )
        services <- probes()
        application <- ServerApplication.open(root, Some(path)).provideSomeLayer[Scope](services.layer)
        configured = application.configured.get
        ids <- application.master.listAgentIds
        session <- application.master.createSession(SessionId.unsafe("shared"), Chunk(first))
        loaded <- configured.assembly.orchestrator.loadSession(session.id)
        _ <- application.master.append(session.id, first, Chunk(MessagePayload.User("local author")))
        calls <- services.calls.get
      } yield {
        assert(application.master eq configured.assembly.orchestrator)
        assertEquals(ids.toSet, Set(first, second))
        assertEquals(configured.workspace, Path.of("").toRealPath())
        assertEquals(loaded, session)
        assertEquals(configured.assembly.context(None).systemPrompt, ApplicationRuntime.DefaultSystemPrompt)
        assertEquals(
          configured.assembly.context(None).tools.map(_.key).toSet,
          Set(
            ToolKey.ReadFile,
            ToolKey.ListFiles,
            ToolKey.Bash,
            ToolKey.RepositoryChanges,
            ToolKey.Scalamode,
            ToolKey.SdlcSet,
            ToolKey.SdlcReadTask,
            ToolKey.SdlcSetIntents,
            ToolKey.SdlcReadIntent,
            ToolKey.SdlcList
          )
        )
        assertEquals(
          calls,
          Vector(
            "bash.available",
            "git.available",
            s"git.worktree:${configured.workspace}",
            "scala.available"
          )
        )
      }
    })
  }

  test("explicit canonical workspace roots probes; unavailable tools are omitted") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        workspace <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("workspace")))
        path <- writeConfig(root, config)
        services <- probes(bash = false, git = false, scala = false)
        application <- ServerApplication
          .open(root, Some(path), Some(workspace.resolve("..").resolve("workspace")))
          .provideSomeLayer[Scope](services.layer)
        configured = application.configured.get
        calls <- services.calls.get
      } yield {
        assertEquals(configured.workspace, workspace.toRealPath())
        assertEquals(
          configured.assembly.context(None).tools.map(_.key).toSet,
          Set(
            ToolKey.ReadFile,
            ToolKey.ListFiles,
            ToolKey.SdlcSet,
            ToolKey.SdlcReadTask,
            ToolKey.SdlcSetIntents,
            ToolKey.SdlcReadIntent,
            ToolKey.SdlcList
          )
        )
        assertEquals(
          calls,
          Vector("bash.available", "git.available", "scala.available")
        )
      }
    })
  }

  test("git outside a worktree does not enter the catalog") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        path <- writeConfig(root, config)
        services <- probes(worktree = false)
        application <- ServerApplication.open(root, Some(path), Some(root)).provideSomeLayer[Scope](services.layer)
      } yield assertEquals(
        application.configured.get.assembly.context(None).tools.map(_.key).toSet,
        Set(
          ToolKey.ReadFile,
          ToolKey.ListFiles,
          ToolKey.Bash,
          ToolKey.Scalamode,
          ToolKey.SdlcSet,
          ToolKey.SdlcReadTask,
          ToolKey.SdlcSetIntents,
          ToolKey.SdlcReadIntent,
          ToolKey.SdlcList
        )
      )
    })
  }

  test("workspace does not rewrite configured cwd-relative agent filesystem roots") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        workspace <- ZIO.attemptBlocking(Files.createDirectories(root.resolve("tools/a/b/c/d/e/f/g/h")))
        agentRoot <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("agent")))
        relative = Path.of("").toRealPath().relativize(agentRoot.toRealPath()).toString
        path <- writeConfig(
          root,
          config.copy(agents =
            Map(first -> AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig(relative))))
          )
        )
        services <- probes()
        _ <- (for {
          application <- ServerApplication.open(root, Some(path), Some(workspace))
          binding <- application.configured.get.runtime.resolve(first)
        } yield {
          assertEquals(binding.adapter.id, first)
          assertEquals(application.configured.get.workspace, workspace.toRealPath())
          assert(!Files.exists(workspace.resolve(relative).normalize()))
        }).provideSomeLayer[Scope](services.layer)
      } yield ()
    })
  }

  test("missing config is privately initialized without selecting agents") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        path = root.resolve("private/config.json")
        services <- probes()
        application <- ServerApplication.open(root, Some(path)).provideSomeLayer[Scope](services.layer)
        ids <- application.master.listAgentIds
      } yield {
        assertEquals(ids, Chunk.empty)
        assert(Files.readString(path).fromJson[Config].isRight)
        if Files.getFileStore(path).supportsFileAttributeView("posix") then
          assertEquals(Files.getPosixFilePermissions(path), PosixFilePermissions.fromString("rw-------"))
      }
    })
  }

  test("invalid config and workspace fail safely and immediately release ownership") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        services <- probes()
        _ <- (for {
          path <- ZIO.attemptBlocking(Files.writeString(root.resolve("config.json"), "PRIVATE_SECRET invalid json"))
          invalid <- ServerApplication.open(root, Some(path)).either
          _ = assertSafeFailure(invalid)
          _ <- writeConfig(root, config)
          absent <- ServerApplication.open(root, Some(path), Some(root.resolve("PRIVATE_SECRET-missing"))).either
          file <- ServerApplication.open(root, Some(path), Some(path)).either
          withoutConfig <- ServerApplication.open(root, workspace = Some(root)).either
          missingStorage <- ServerApplication.open(root.resolve("PRIVATE_SECRET-missing")).either
          _ = List(absent, file, withoutConfig, missingStorage).foreach(assertSafeFailure)
          // All failed opens above occurred in this same still-open outer scope.
          recovered <- ServerApplication.open(root)
          ids <- recovered.master.listAgentIds
        } yield assertEquals(ids, Chunk.empty)).provideSomeLayer[Scope](services.layer)
      } yield ()
    })
  }

  test("capabilities are checked against available tools before the only filesystem master is opened") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        restricted = config.copy(agents =
          Map(
            first -> AgentConfig("Unsupported", capabilities = AgentCapabilities(tools = Some(Set(ToolKey.Scalamode))))
          )
        )
        path <- writeConfig(root, restricted)
        services <- probes(scala = false)
        _ <- (for {
          rejected <- ServerApplication.open(root, Some(path)).either
          _ = {
            val error = rejected.swap.toOption.get
            assert(error.isInstanceOf[IllegalStateException])
            assert(error.getCause.isInstanceOf[YallmoError.InvalidConfig])
            assert(error.getCause.getMessage.contains("capabilities.tools contains unknown IDs: scala"))
            assert(error.getCause.getMessage.contains("Agent 'first'"))
          }
          _ = assert(!Files.exists(root.resolve("objects.jsonl")))
          _ <- writeConfig(
            root,
            restricted.copy(agents =
              Map(
                first -> AgentConfig(
                  "Unsupported",
                  capabilities = AgentCapabilities(tools = Some(Set(ToolKey.ReadFile)))
                )
              )
            )
          )
          application <- ServerApplication.open(root, Some(path))
        } yield assert(application.configured.nonEmpty)).provideSomeLayer[Scope](services.layer)
      } yield ()
    })
  }

  test("configured and viewer startup retain exact storage validation causes without exposing message content") {
    run(ZIO.scoped {
      ZIO.foreachDiscard(List(false, true)) { configured =>
        for {
          root <- temporaryRoot
          path <- writeConfig(root, config)
          services <- probes()
          original = MessageData(
            first,
            None,
            java.time.Instant.parse("2026-01-01T00:00:00Z"),
            MessagePayload.User("original")
          )
          storedId = MessageId.from(original)
          changed = original.copy(payload = MessagePayload.User("PRIVATE_SECRET"))
          contents = s"""{"id":"${MessageId.value(storedId)}","kind":"msg","value":${changed.toJson}}\n"""
          objects = root.resolve("objects.jsonl")
          _ <- ZIO.attemptBlocking(Files.writeString(objects, contents))
          rejected <- ServerApplication
            .open(root, Option.when(configured)(path))
            .either
            .provideSomeLayer[Scope](services.layer)
          _ = {
            val error = rejected.swap.toOption.get
            assert(error.isInstanceOf[IllegalStateException])
            assert(error.getCause.isInstanceOf[YallmoError.StorageFailure])
            val details = error.getCause.getMessage
            assert(details.contains("objects.jsonl record 1"))
            assert(details.contains(s"stored ID ${MessageId.value(storedId)}"))
            assert(details.contains(s"computed ID ${MessageId.value(MessageId.from(changed))}"))
            assert(details.contains("hash mismatch"))
            assert(!error.toString.contains("PRIVATE_SECRET"))
            assert(!error.getCause.toString.contains("PRIVATE_SECRET"))
            assert(!error.getCause.getCause.toString.contains("PRIVATE_SECRET"))
            assertEquals(Files.readString(objects), contents)
          }
          // A failed open must release the standalone lease even when the caller retains its outer scope.
          _ <- ZIO.attemptBlocking(Files.writeString(objects, ""))
          reopened <- ServerApplication.open(root).provideSomeLayer[Scope](services.layer)
          messages <- reopened.master.listMessages
        } yield assertEquals(messages, Chunk.empty)
      }
    })
  }

  test("configured master advertises only seven read methods and rejects every reserved execution/diagnostic method") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        path <- writeConfig(root, config)
        services <- probes()
        application <- ServerApplication.open(root, Some(path)).provideSomeLayer[Scope](services.layer)
        rpc = new OrchestratorRpc(application.master)
        connection <- rpc.openConnection
        _ <- ZIO.addFinalizer(rpc.disconnect(connection))
        response <- rpc.handle(
          JsonRpc.Request(Json.Str("init"), Initialize.method, Initialize.Params(Version, "test")).toJson,
          connection
        )
        result = response.get.fromJson[JsonRpc.Success[Initialize.Result]].toOption.get.result
        failures <- ZIO.foreach(List("turn.submit", "sessions.create", "runs.cancel", "commands.get")) { method =>
          rpc.handle(JsonRpc.Request(Json.Str(method), method, Empty()).toJson, connection)
        }
        sessions <- application.master.listSessions
        messages <- application.master.listMessages
        calls <- services.calls.get
      } yield {
        assertEquals(
          result.methods.toSet,
          Set("initialize", "sessions.list", "sessions.get", "messages.list", "agents.list", "blobs.list", "blobs.read")
        )
        assertEquals(result.methods.size, 7)
        assertEquals(result.notifications, Nil)
        assertEquals(result.identity.role, Role.Viewer)
        failures.foreach(reply =>
          assertEquals(reply.get.fromJson[JsonRpc.Failure].toOption.get.error, JsonRpc.Error.MethodNotFound)
        )
        assertEquals(sessions, Chunk.empty)
        assertEquals(messages, Chunk.empty)
        assert(!calls.exists(_.contains("execute")))
      }
    })
  }

  test("claimed viewers cannot collide with any configured agent, including unresolved lazy definitions") {
    run(
      ZIO
        .scoped {
          for {
            root <- temporaryRoot
            path <- writeConfig(root, config)
            services <- probes()
            application <- ServerApplication.open(root, Some(path)).provideSomeLayer[Scope](services.layer)
            port <- new OrchestratorWSServer(application.master).start(0)
            endpoint = s"ws://127.0.0.1:$port/rpc"
            failures <- ZIO.foreach(List(first, second))(id =>
              OrchestratorWSClient.connect(endpoint, userId = Some(id)).either
            )
            observer <- OrchestratorWSClient.connect(endpoint)
            ids <- observer.listAgentIds
            registered <- application.master.listAgentIds
            calls <- services.calls.get
          } yield {
            failures.foreach(result =>
              assertEquals(
                result,
                Left(
                  fmgp.yallmo.server.OrchestratorClient.Error.Remote(
                    JsonRpc.Error(ErrorCode.Conflict, "Human identity conflicts with a runtime agent")
                  )
                )
              )
            )
            assertEquals(ids, Chunk(first, second))
            assertEquals(registered, ids)
            assert(!calls.exists(_.contains("execute")))
          }
        }
        .mapError(error => new AssertionError(error.toString))
    )
  }

  test("client and tool service layers remain live through registry and listener use, then close with the host") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        path <- writeConfig(
          root,
          config.copy(
            agents = Map(first -> AgentConfig("OAIAgent", Seq(provider), Json.Obj("model" -> Json.Str("test-model")))),
            providers = Map(provider -> ProviderConfig.OAI("http://127.0.0.1:1/v1"))
          )
        )
        alive <- Ref.make(false)
        toolsAlive <- Ref.make(false)
        hostClosedWithServices <- Ref.make(false)
        services <- probes()
        client = ZLayer.scopedEnvironment {
          ZIO.acquireRelease(alive.set(true))(_ => alive.set(false)) *> Client.default.build
        }
        toolLayer = ZLayer.scopedEnvironment {
          ZIO.acquireRelease(toolsAlive.set(true))(_ => toolsAlive.set(false)) *> services.tools.build
        }
        _ <- ZIO
          .scoped {
            for {
              // Registered before the host, so this runs after listener/lease cleanup but before service release.
              _ <- ZIO.addFinalizer(alive.get.zip(toolsAlive.get).flatMap { case (http, tools) =>
                hostClosedWithServices.set(http && tools)
              })
              application <- ServerApplication.open(root, Some(path))
              port <- new OrchestratorWSServer(application.master).start(0)
              // Resolve lazily after open, without contacting the configured endpoint.
              _ <- application.configured.get.runtime.resolve(first)
              connection <- OrchestratorWSClient
                .connect(s"ws://127.0.0.1:$port/rpc")
                .mapError(error => new AssertionError(error.toString))
              ids <- connection.listAgentIds.mapError(error => new AssertionError(error.toString))
              live <- alive.get
              liveTools <- toolsAlive.get
              tools <- ZIO.serviceWithZIO[BashExecutor](_.isAvailable)
            } yield {
              assert(live)
              assert(liveTools)
              assert(tools)
              assertEquals(ids.toSet, Set(first))
            }
          }
          .provideLayer(client ++ toolLayer)
        closed <- alive.get
        toolsClosed <- toolsAlive.get
        servicesOutlivedHost <- hostClosedWithServices.get
      } yield {
        assert(!closed)
        assert(!toolsClosed)
        assert(servicesOutlivedHost)
      }
    })
  }

  test("lease rejects overlapping standalone owners nonblockingly, preserves the inode and permits reacquisition") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        services <- probes()
        _ <- (for {
          _ <- ZIO.scoped {
            for {
              owner <- ServerApplication.open(root)
              _ <- ZIO.scoped {
                ServerApplication.open(root).either.map(result => assert(result.isLeft))
              }
              ids <- owner.master.listAgentIds
            } yield assertEquals(ids, Chunk.empty)
          }
          _ = assert(Files.exists(root.resolve(".yallmo-server.lock")))
          inode <- ZIO.attemptBlocking(
            Files
              .readAttributes(root.resolve(".yallmo-server.lock"), classOf[java.nio.file.attribute.BasicFileAttributes])
              .fileKey()
          )
          _ <- ZIO.scoped(ServerApplication.open(root))
          after <- ZIO.attemptBlocking(
            Files
              .readAttributes(root.resolve(".yallmo-server.lock"), classOf[java.nio.file.attribute.BasicFileAttributes])
              .fileKey()
          )
        } yield assertEquals(after, inode)).provideSomeLayer[Scope](services.layer)
      } yield ()
    })
  }

  test("lease observes an existing FileChannel lock and cleans up after startup interruption") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        services <- probes()
        _ <- (for {
          _ <- ZIO.scoped {
            for {
              channel <- ZIO.acquireRelease(
                ZIO.attemptBlocking(
                  FileChannel
                    .open(root.resolve(".yallmo-server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                )
              )(c => ZIO.attemptBlocking(c.close()).orDie)
              _ <- ZIO.acquireRelease(ZIO.attemptBlocking(channel.lock()))(l => ZIO.attemptBlocking(l.release()).orDie)
              denied <- ServerApplication.open(root).either
            } yield assert(denied.isLeft)
          }
          path <- writeConfig(root, config)
          started <- Promise.make[Nothing, Unit]
          blocking <- probes(beforeBash = started.succeed(()).unit *> ZIO.never)
          // Interrupt inside startup, after ownership but before assembly creation, in this same parent scope.
          fiber <- ServerApplication.open(root, Some(path)).provideSomeLayer[Scope](blocking.layer).fork
          _ <- started.await
          _ <- fiber.interrupt
          _ <- ZIO.scoped(ServerApplication.open(root))
        } yield ()).provideSomeLayer[Scope](services.layer)
      } yield ()
    })
  }

  test("symlink root aliases contend on the same lease and workspace resolves canonically when supported") {
    run(ZIO.scoped {
      for {
        parent <- temporaryRoot
        root <- ZIO.attemptBlocking(Files.createDirectory(parent.resolve("root")))
        alias <- symlink(parent.resolve("alias"), root)
        services <- probes()
        _ <- ZIO.foreachDiscard(alias) { link =>
          (for {
            path <- writeConfig(root, config)
            _ <- ZIO.scoped {
              for {
                _ <- ServerApplication.open(root)
                denied <- ServerApplication.open(link).either
              } yield assert(denied.isLeft)
            }
            _ <- ZIO.scoped {
              ServerApplication
                .open(link, Some(path), Some(link))
                .map(application => assertEquals(application.configured.get.workspace, root.toRealPath()))
            }
          } yield ()).provideSomeLayer[Scope](services.layer)
        }
      } yield ()
    })
  }

  test("symlink lock files are rejected without following or removing them when supported") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        target <- ZIO.attemptBlocking(Files.writeString(root.resolve("target"), "untouched"))
        link <- symlink(root.resolve(".yallmo-server.lock"), target)
        services <- probes()
        _ <- ZIO.foreachDiscard(link) { lock =>
          ServerApplication.open(root).either.provideSomeLayer[Scope](services.layer).map { denied =>
            assert(denied.isLeft)
            assert(Files.isSymbolicLink(lock))
            assertEquals(Files.readString(target), "untouched")
          }
        }
      } yield ()
    })
  }

  private final case class Probes(calls: Ref[Vector[String]], tools: ULayer[ServerApplication.ToolEnvironment]) {
    val layer: ZLayer[Any, Throwable, ServerApplication.Environment] = Client.default ++ tools
  }

  private def probes(
      bash: Boolean = true,
      git: Boolean = true,
      worktree: Boolean = true,
      scala: Boolean = true,
      beforeBash: UIO[Unit] = ZIO.unit
  ): UIO[Probes] = Ref.make(Vector.empty[String]).map { calls =>
    def record(value: String): UIO[Unit] = calls.update(_ :+ value)
    def unexpected[A]: UIO[A] = ZIO.dieMessage("Startup must not execute tools")

    val shell = new BashExecutor {
      def isAvailable: UIO[Boolean] = (record("bash.available") *> beforeBash).as(bash)
      def execute(command: String, workspace: Path, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] =
        unexpected
    }
    val repository = new GitClient {
      def isAvailable: UIO[Boolean] = record("git.available").as(git)
      def isWorkTree(workspace: Path): UIO[Boolean] = record(s"git.worktree:$workspace").as(worktree)
      def status(workspace: Path): IO[GitError, CommandOutput] = unexpected
      def unstagedDiff(workspace: Path): IO[GitError, CommandOutput] = unexpected
      def stagedDiff(workspace: Path): IO[GitError, CommandOutput] = unexpected
    }
    val compiler = new ScalaCodeExecutor {
      def isAvailable: UIO[Boolean] = record("scala.available").as(scala)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String])
          : Task[ExternalToolResult.ScalaMode] = unexpected
    }
    Probes(
      calls,
      ZLayer.succeed[BashExecutor](shell) ++
        ZLayer.succeed[GitClient](repository) ++ ZLayer.succeed[ScalaCodeExecutor](compiler)
    )
  }

  private def assertSafeFailure(result: Either[Throwable, ServerApplication]): Unit = {
    assert(result.isLeft)
    assert(!result.swap.toOption.get.toString.contains("PRIVATE_SECRET"))
    assertEquals(result.swap.toOption.get.getCause, null)
  }

  private def writeConfig(root: Path, value: Config): Task[Path] =
    ZIO.attemptBlocking(Files.writeString(root.resolve("config.json"), value.toJson))

  private def symlink(link: Path, target: Path): Task[Option[Path]] =
    ZIO.attemptBlocking(Some(Files.createSymbolicLink(link, target))).catchSome {
      case _: UnsupportedOperationException     => ZIO.none
      case _: java.nio.file.FileSystemException => ZIO.none
    }

  private def temporaryRoot: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-server-application-"))) { root =>
      ZIO.attemptBlocking {
        val paths = Files.walk(root)
        try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
        finally paths.close()
      }.orDie
    }

  private def run[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new AssertionError("Server application test timed out"))(20.seconds))
        .getOrThrowFiberFailure()
    }
}
