package fmgp.yallmo.orchestration

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import fmgp.yallmo.fs.{FileSystem, WriteMode}
import fmgp.yallmo.model.{AgentId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason}
import fmgp.yallmo.agents.FolderStructureAgent
import fmgp.yallmo.agents.FolderStructureAgent.{File, Folder, State}
import fmgp.yallmo.orchestration.config.{AgentConfig, Config, ConfigFile, FileSystemConfig}
import munit.FunSuite
import zio.{Config as _, *}
import zio.http.Client
import zio.json.*

final class FolderStructureAgentSuite extends FunSuite {
  private val id = AgentId("folder-reviewer")
  private val request = AgentTurnRequest("folder-structure", Chunk(MessagePayload.Instructions("Review folder names.")))

  test("writes a described tree, refreshes it and restores the count when reconstructed") {
    withWorkspace { (_, fs) =>
      run(fs.createDirectories("src/empty"))
      run(fs.writeUtf8("README.md", "not examined", WriteMode.CreateNew))
      run(fs.writeUtf8("src/Main.scala", "not examined", WriteMode.CreateNew))
      val agent = FolderStructureAgent.make(id, fs)
      val expected = Folder(
        ".",
        "Configured workspace.",
        List(
          Folder(
            "src",
            "Directory src.",
            List(Folder("empty", "Directory empty.", Nil, Nil)),
            List(File("Main.scala", "File Main.scala."))
          )
        ),
        List(File("README.md", "File README.md."))
      )
      assertEquals(
        run(agent.turn(request)),
        AgentTurnResponse(Chunk(MessagePayload.Assistant(expected.toJsonPretty)), LlmStopReason.Stop)
      )
      assertEquals(run(fs.readUtf8("fs-structure.json")).fromJson[Folder], Right(expected))

      run(fs.writeUtf8("z.txt", "new file", WriteMode.CreateNew))
      run(FolderStructureAgent.make(id, fs).turn(request))
      val refreshed = expected.copy(files = expected.files :+ File("z.txt", "File z.txt."))
      assertEquals(run(fs.readUtf8("fs-structure.json")).fromJson[Folder], Right(refreshed))
      assertEquals(run(fs.read[State]("state.jsonl").runCollect), Chunk(State(1), State(2)))
    }
  }

  test("corrupt state fails without resetting state or replacing the structure") {
    withWorkspace { (_, fs) =>
      val state = State(1).toJson + "\nbroken\n"
      run(fs.writeUtf8("state.jsonl", state, WriteMode.CreateNew))
      run(fs.writeUtf8("fs-structure.json", "previous structure", WriteMode.CreateNew))
      run(FolderStructureAgent.make(id, fs).turn(request).either) match {
        case Left(error: YallmoError.AgentExecutionFailed) => assertEquals(error.agentId, id)
        case other                                         => fail(s"Expected a typed agent failure, got $other")
      }
      assertEquals(run(fs.readUtf8("state.jsonl")), state)
      assertEquals(run(fs.readUtf8("fs-structure.json")), "previous structure")
    }
  }

  test("configuration constructs cached agents with independently bound roots and no per-turn environment") {
    withWorkspace { (root, fs) =>
      val otherRoot = Files.createDirectory(root.resolveSibling("other"))
      val otherFs = run(FileSystem.open(otherRoot.toString))
      val otherId = AgentId("other-reviewer")
      val config = Config(
        agents = Map(
          id -> AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig(root.toString))),
          otherId -> AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig(otherRoot.toString)))
        )
      )
      val path = root.resolveSibling("agents.json")
      Files.writeString(path, config.toJson)
      run {
        (for {
          loaded <- ConfigFile.loadOrInitialize(path)
          instances <- RuntimeInstances.make(loaded)
          agent <- instances.getAgent(id)
          cached <- instances.getAgent(id)
          other <- instances.getAgent(otherId)
          _ <- agent.turn(request)
          _ <- cached.turn(request)
          _ <- other.turn(request)
          counts <- fs.read[State]("state.jsonl").runCollect
          otherCounts <- otherFs.read[State]("state.jsonl").runCollect
        } yield {
          assert(agent eq cached)
          assertEquals(counts, Chunk(State(1), State(2)))
          assertEquals(otherCounts, Chunk(State(1)))
        }).provide(Client.default)
      }
    }
  }

  test("configured roots resolve symlinks in the root or its ancestors, including relative paths") {
    withWorkspace { (root, fs) =>
      val rootLink = Files.createSymbolicLink(root.resolveSibling("root-link"), root)
      val parentLink = Files.createSymbolicLink(root.resolveSibling("parent-link"), root.getParent)
      val relative = Path.of("").toAbsolutePath.relativize(rootLink)
      for (path, index) <- List(rootLink, parentLink.resolve(root.getFileName), relative).zipWithIndex do {
        val config = Config(
          agents = Map(id -> AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig(path.toString))))
        )
        val instances = run(RuntimeInstances.make(config).provide(Client.default))
        val agent = run(instances.getAgent(id))
        assert(run(instances.getAgent(id)) eq agent)
        run(agent.turn(request))
        assertEquals(run(fs.read[State]("state.jsonl").runCollect).last, State(index.toLong + 1))
        assert(Files.isRegularFile(root.resolve("fs-structure.json")))
      }
    }
  }

  test("a configured symlink is resolved once and retargeting it does not redirect an initialized agent") {
    withWorkspace { (root, fs) =>
      val otherRoot = Files.createDirectory(root.resolveSibling("other"))
      val link = Files.createSymbolicLink(root.resolveSibling("root-link"), root)
      val config = Config(
        agents = Map(id -> AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig(link.toString))))
      )
      val instances = run(RuntimeInstances.make(config).provide(Client.default))
      val agent = run(instances.getAgent(id))
      assert(!Files.exists(root.resolve("state.jsonl")))
      Files.delete(link)
      Files.createSymbolicLink(link, otherRoot)
      run(agent.turn(request))
      assertEquals(run(fs.read[State]("state.jsonl").runCollect), Chunk(State(1)))
      assert(!Files.exists(otherRoot.resolve("state.jsonl")))
      assert(!Files.exists(otherRoot.resolve("fs-structure.json")))
    }
  }

  test("resolving a configured root does not permit symbolic links inside the workspace") {
    withWorkspace { (root, _) =>
      val target = Files.writeString(root.resolveSibling("outside.txt"), "unchanged")
      Files.createSymbolicLink(root.resolve("inside-link"), target)
      val link = Files.createSymbolicLink(root.resolveSibling("root-link"), root)
      val config = Config(
        agents = Map(id -> AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig(link.toString))))
      )
      val result = run {
        RuntimeInstances.make(config).flatMap(_.getAgent(id)).flatMap(_.turn(request)).either.provide(Client.default)
      }
      assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.AgentExecutionFailed]))
      assertEquals(Files.readString(target), "unchanged")
      assert(!Files.exists(root.resolve("state.jsonl")))
      assert(!Files.exists(root.resolve("fs-structure.json")))
    }
  }

  test("configured folder agents reject missing, blank, invalid, non-directory and dangling roots") {
    withWorkspace { (root, _) =>
      val missing = root.resolve("missing")
      val file = Files.writeString(root.resolve("file.txt"), "not a directory")
      val dangling = Files.createSymbolicLink(root.resolveSibling("dangling"), missing)
      val invalid = List("", " ", "invalid\u0000root", missing.toString, file.toString, dangling.toString)
      val results = run {
        ZIO
          .foreach(None :: invalid.map(path => Some(FileSystemConfig(path)))) { filesystem =>
            val config =
              Config(agents = Map(id -> AgentConfig("FolderStructureAgent", filesystem = filesystem)))
            RuntimeInstances.make(config).flatMap(_.getAgent(id).either)
          }
          .provide(Client.default)
      }
      results.foreach {
        case Left(_: YallmoError.InvalidConfig) => ()
        case other                              => fail(s"Expected a configuration failure, got $other")
      }
      assert(!Files.exists(missing))
    }
  }

  private def withWorkspace(test: (Path, FileSystem) => Unit): Unit = {
    val parent = Files.createTempDirectory("yallmo-folder-agent-").toRealPath()
    val root = Files.createDirectory(parent.resolve("workspace"))
    try test(root, run(FileSystem.open(root.toString)))
    finally {
      val paths = Files.walk(parent)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
      finally paths.close()
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
