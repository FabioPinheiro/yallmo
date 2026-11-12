package fmgp.yallmo.server.application

import fmgp.yallmo.model.tool.{
  BashToolDefinition,
  FilesystemToolDefinition,
  RepositoryChangesToolDefinition,
  ScalamodeToolDefinition,
  SdlcToolDefinition
}

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.{SessionId, ExternalFunctionResult}
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

  test("startup requires config and accepts reordered/equal syntax") {
    import OrchestratorServerMain.{Options, parse}
    assert(parse(List("--storage", "sessions")).isLeft)
    assertEquals(
      parse(List("--workspace=work", "--log-level", "debug", "--config", "private.json", "--storage=sessions")),
      Right(Options(Path.of("sessions"), Path.of("private.json"), 8080, LogLevel.Debug, Some(Path.of("work"))))
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

  test(
    "configured startup retains the same application master without a startup human or running anything"
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
        application <- ServerApplication.open(root, path).provideSomeLayer[Scope](services.layer)
        configured = application
        ids <- application.application.master.listAgentIds
        session <- application.application.master.createSession(SessionId.unsafe("shared"), Chunk(first))
        loaded <- configured.application.master.loadSession(session.id)
        _ <- application.application.master.append(session.id, first, Chunk(MessagePayload.User("local author")))
        calls <- services.calls.get
      } yield {
        assert(application.application.master eq configured.application.master)
        assertEquals(ids.toSet, Set(first, second))
        assertEquals(configured.workspace, Path.of("").toRealPath())
        assertEquals(loaded, session)
        assertEquals(configured.application.context(None).systemPrompt, ServerApplication.DefaultSystemPrompt)
        assertEquals(
          configured.application.context(None).functions.map(_.name).toSet,
          Set(
            FilesystemToolDefinition.ReadFile,
            FilesystemToolDefinition.ListFiles,
            BashToolDefinition.Execute,
            RepositoryChangesToolDefinition.Inspect,
            ScalamodeToolDefinition.Execute,
            SdlcToolDefinition.SetTasks,
            SdlcToolDefinition.ReadTask,
            SdlcToolDefinition.SetIntents,
            SdlcToolDefinition.ReadIntent,
            SdlcToolDefinition.List
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
          .open(root, path, Some(workspace.resolve("..").resolve("workspace")))
          .provideSomeLayer[Scope](services.layer)
        configured = application
        calls <- services.calls.get
      } yield {
        assertEquals(configured.workspace, workspace.toRealPath())
        assertEquals(
          configured.application.context(None).functions.map(_.name).toSet,
          Set(
            FilesystemToolDefinition.ReadFile,
            FilesystemToolDefinition.ListFiles,
            SdlcToolDefinition.SetTasks,
            SdlcToolDefinition.ReadTask,
            SdlcToolDefinition.SetIntents,
            SdlcToolDefinition.ReadIntent,
            SdlcToolDefinition.List
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
        application <- ServerApplication.open(root, path, Some(root)).provideSomeLayer[Scope](services.layer)
      } yield assertEquals(
        application.application.context(None).functions.map(_.name).toSet,
        Set(
          FilesystemToolDefinition.ReadFile,
          FilesystemToolDefinition.ListFiles,
          BashToolDefinition.Execute,
          ScalamodeToolDefinition.Execute,
          SdlcToolDefinition.SetTasks,
          SdlcToolDefinition.ReadTask,
          SdlcToolDefinition.SetIntents,
          SdlcToolDefinition.ReadIntent,
          SdlcToolDefinition.List
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
          application <- ServerApplication.open(root, path, Some(workspace))
          binding <- application.application.resolve(first)
        } yield {
          assertEquals(binding.adapter.id, first)
          assertEquals(application.workspace, workspace.toRealPath())
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
        application <- ServerApplication.open(root, path).provideSomeLayer[Scope](services.layer)
        ids <- application.application.master.listAgentIds
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
          invalid <- ServerApplication.open(root, path).either
          _ = assertSafeFailure(invalid)
          _ <- writeConfig(root, config)
          absent <- ServerApplication.open(root, path, Some(root.resolve("PRIVATE_SECRET-missing"))).either
          file <- ServerApplication.open(root, path, Some(path)).either
          missingStorage <- ServerApplication.open(root.resolve("PRIVATE_SECRET-missing"), path).either
          _ = List(absent, file, missingStorage).foreach(assertSafeFailure)
          // All failed opens above occurred in this same still-open outer scope.
          recovered <- ServerApplication.open(root, path)
          ids <- recovered.application.master.listAgentIds
        } yield assertEquals(ids.toSet, Set(first, second))).provideSomeLayer[Scope](services.layer)
      } yield ()
    })
  }

  test("capabilities are checked against available tools before the only filesystem master is opened") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        restricted = config.copy(agents =
          Map(
            first -> AgentConfig(
              "Unsupported",
              capabilities = AgentCapabilities(functions = Some(Set(ScalamodeToolDefinition.Execute)))
            )
          )
        )
        path <- writeConfig(root, restricted)
        services <- probes(scala = false)
        _ <- (for {
          rejected <- ServerApplication.open(root, path).either
          _ = {
            val error = rejected.swap.toOption.get
            assert(error.isInstanceOf[IllegalStateException])
            assert(error.getCause.isInstanceOf[YallmoError.InvalidConfig])
            assert(error.getCause.getMessage.contains("capabilities.functions contains unknown IDs: scala"))
            assert(error.getCause.getMessage.contains("Agent 'first'"))
          }
          _ = assert(!Files.exists(root.resolve("objects.jsonl")))
          _ <- writeConfig(
            root,
            restricted.copy(agents =
              Map(
                first -> AgentConfig(
                  "Unsupported",
                  capabilities = AgentCapabilities(functions = Some(Set(FilesystemToolDefinition.ReadFile)))
                )
              )
            )
          )
          application <- ServerApplication.open(root, path)
        } yield assertEquals(application.application.agentSummaries.map(_.id), Vector(first)))
          .provideSomeLayer[Scope](services.layer)
      } yield ()
    })
  }

  test("startup retains exact storage validation causes without exposing message content") {
    run(ZIO.scoped {
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
          .open(root, path)
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
        reopened <- ServerApplication.open(root, path).provideSomeLayer[Scope](services.layer)
        messages <- reopened.application.master.listMessages
      } yield assertEquals(messages, Chunk.empty)
    })
  }

  test("anonymous connections advertise read methods and cannot execute") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        path <- writeConfig(root, config)
        services <- probes()
        application <- ServerApplication.open(root, path).provideSomeLayer[Scope](services.layer)
        rpc = new OrchestratorRpc(application)
        connection <- rpc.openConnection
        _ <- ZIO.addFinalizer(rpc.disconnect(connection))
        response <- rpc.handle(
          JsonRpc.Request(Json.Str("init"), Initialize.method, Initialize.Params("test")).toJson,
          connection
        )
        result = response.get.fromJson[JsonRpc.Success[Initialize.Result]].toOption.get.result
        failures <- ZIO.foreach(List("sessions.create", "runs.cancel", "commands.get")) { method =>
          rpc.handle(JsonRpc.Request(Json.Str(method), method, Empty()).toJson, connection)
        }
        sessions <- application.application.master.listSessions
        messages <- application.application.master.listMessages
        calls <- services.calls.get
      } yield {
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
            path <- writeConfig(
              root,
              config.copy(agents = config.agents.map { case (id, definition) =>
                AgentId("user-" + AgentId.value(id)) -> definition
              })
            )
            services <- probes()
            application <- ServerApplication.open(root, path).provideSomeLayer[Scope](services.layer)
            port <- new OrchestratorWSServer(application).start(0)
            endpoint = s"ws://127.0.0.1:$port/rpc"
            failures <- ZIO.foreach(List(first, second))(id =>
              OrchestratorWSClient
                .connect(endpoint, userId = Some(UserId(AgentId.value(id).stripPrefix("user-"))))
                .either
            )
            observer <- OrchestratorWSClient.connect(endpoint)
            ids <- observer.listAgentIds
            registered <- application.application.master.listAgentIds
            calls <- services.calls.get
          } yield {
            failures.foreach(result =>
              assertEquals(
                result,
                Left(
                  fmgp.yallmo.server.OrchestratorClient.Error.Remote(
                    JsonRpc.Error(ErrorCode.Conflict, "Owned actor conflicts with a runtime agent")
                  )
                )
              )
            )
            assertEquals(ids, Chunk(AgentId("user-first"), AgentId("user-second")))
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
              application <- ServerApplication.open(root, path)
              port <- new OrchestratorWSServer(application).start(0)
              // Resolve lazily after open, without contacting the configured endpoint.
              _ <- application.application.resolve(first)
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
              _ <- ServerApplication.lease(root)
              _ <- ZIO.scoped {
                ServerApplication.lease(root).either.map(result => assert(result.isLeft))
              }
            } yield ()
          }
          _ = assert(Files.exists(root.resolve(".yallmo-server.lock")))
          inode <- ZIO.attemptBlocking(
            Files
              .readAttributes(root.resolve(".yallmo-server.lock"), classOf[java.nio.file.attribute.BasicFileAttributes])
              .fileKey()
          )
          _ <- ZIO.scoped(ServerApplication.lease(root))
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
              denied <- ServerApplication.lease(root).either
            } yield assert(denied.isLeft)
          }
          path <- writeConfig(root, config)
          started <- Promise.make[Nothing, Unit]
          blocking <- probes(beforeBash = started.succeed(()).unit *> ZIO.never)
          // Interrupt inside startup, after ownership but before application composition, in this same parent scope.
          fiber <- ServerApplication.open(root, path).provideSomeLayer[Scope](blocking.layer).fork
          _ <- started.await
          _ <- fiber.interrupt
          _ <- ZIO.scoped(ServerApplication.lease(root))
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
                _ <- ServerApplication.lease(root)
                denied <- ServerApplication.lease(link.toRealPath()).either
              } yield assert(denied.isLeft)
            }
            _ <- ZIO.scoped {
              ServerApplication
                .open(link, path, Some(link))
                .map(application => assertEquals(application.workspace, root.toRealPath()))
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
          ServerApplication.lease(root).either.provideSomeLayer[Scope](services.layer).map { denied =>
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
          : Task[ExternalFunctionResult.ScalaMode] = unexpected
    }
    Probes(
      calls,
      ZLayer.succeed[BashExecutor](shell) ++
        ZLayer.succeed[GitClient](repository) ++ ZLayer.succeed[ScalaCodeExecutor](compiler)
    )
  }

  private def assertSafeFailure(result: Either[Throwable, ServerExecutionService]): Unit = {
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
