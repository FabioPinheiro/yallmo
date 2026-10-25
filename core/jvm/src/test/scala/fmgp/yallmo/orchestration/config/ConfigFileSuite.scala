package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.{AgentId, YallmoError}
import munit.FunSuite
import zio.{Config as _, *}
import zio.json.*

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList

final class ConfigFileSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withDirectory(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-config-test-")
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  test("missing config is initialized privately with empty catalogs and no terminal preferences") {
    withDirectory { root =>
      val path = root.resolve("nested/yallmo.json")
      val initial = run(ConfigFile.loadOrInitialize(path))
      assertEquals(initial, Config())
      assertEquals(initial.agents, Map.empty)
      assertEquals(initial.providers, Map.empty)
      assertEquals(
        initial.toJson.fromJson[zio.json.ast.Json.Obj].toOption.get.fields.map(_._1).toSet,
        Set("agents", "providers", "browserAudioPort")
      )
      assertEquals(initial.browserAudioPort, 5180)
      assert("\"browserAudioPort\"\\s*:\\s*5180".r.findFirstIn(Files.readString(path)).nonEmpty)
      assertEquals(Files.readString(path).fromJson[Config], Right(initial))
      if Files.getFileStore(path).supportsFileAttributeView("posix") then
        assertEquals(Files.getPosixFilePermissions(path), PosixFilePermissions.fromString("rw-------"))
      val before = Files.readString(path)
      assertEquals(run(ConfigFile.loadOrInitialize(path)), initial)
      assertEquals(Files.readString(path), before)
    }
  }

  test("older configs without browserAudioPort default to 5180 without rewriting") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val json = """{"userName":"operator","agents":{},"providers":{}}"""
      Files.writeString(path, json)
      assertEquals(run(ConfigFile.loadOrInitialize(path)).browserAudioPort, 5180)
      assertEquals(Files.readString(path), json)
    }
  }

  test("per-agent filesystem roots round-trip without opening directories; older configs need no filesystem") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val id = AgentId("folder-reviewer")
      val legacy = """{"userName":"operator","agents":{"folder-reviewer":{"agentType":"OAIAgent"}}}"""
      Files.writeString(path, legacy)
      assertEquals(run(ConfigFile.loadOrInitialize(path)).agents(id).filesystem, None)
      assertEquals(Files.readString(path), legacy)

      val workspace = root.resolve("not created")
      val definition = AgentConfig("OAIAgent", filesystem = Some(FileSystemConfig(workspace.toString)))
      val config = Config(agents = Map(id -> definition))
      val json = config.toJson
      Files.writeString(path, json)
      assertEquals(run(ConfigFile.loadOrInitialize(path)), config)
      assertEquals(Files.readString(path), json)
      assert(!Files.exists(workspace))
    }
  }

  test("blank or malformed filesystem roots fail validation without rewriting the config") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      for filesystem <- List("{}", """{"root":""}""", """{"root":"  "}""", """{"root":123}""", "[]") do {
        val json =
          s"""{"userName":"operator","agents":{"folder-reviewer":{"agentType":"OAIAgent","filesystem":$filesystem}}}"""
        Files.writeString(path, json)
        val result = run(ConfigFile.loadOrInitialize(path).either)
        assert(result.left.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
        assertEquals(Files.readString(path), json)
      }
    }
  }

  test("configured browser audio ports round-trip and invalid ports fail without rewriting") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      for port <- List(1, 6190, 65535) do {
        val config = Config(browserAudioPort = port)
        val json = config.toJson
        Files.writeString(path, json)
        assertEquals(run(ConfigFile.loadOrInitialize(path)), config)
        assertEquals(Files.readString(path), json)
      }
      for value <- List("-1", "0", "65536", "1.5", "\"not-a-port\"") do {
        val json = s"""{"userName":"operator","browserAudioPort":$value}"""
        Files.writeString(path, json)
        val result = run(ConfigFile.loadOrInitialize(path).either)
        assert(result.left.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]), value)
        assertEquals(Files.readString(path), json)
      }
    }
  }

  test("existing configuration and literal credentials are loaded without rewriting") {
    withDirectory { root =>
      val id = ProviderId.unsafe("account")
      val config = Config(
        providers = Map(
          id -> ProviderConfig.ChatGPT(
            Some(OAuthCredentialsConfig("synthetic-access", "synthetic-refresh", 0))
          )
        )
      )
      val json = config.toJsonPretty + "\n\n"
      val path = root.resolve("yallmo.json")
      Files.writeString(path, json)
      assertEquals(run(ConfigFile.loadOrInitialize(path)), config)
      assertEquals(Files.readString(path), json)
    }
  }

  test("concurrent initialization publishes one complete configuration without overwriting a winner") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val results = run(ZIO.foreachPar(1 to 20)(_ => ConfigFile.loadOrInitialize(path)))
      assertEquals(results.distinct.size, 1)
      assertEquals(Files.readString(path).fromJson[Config], Right(results.head))
      val files = Files.list(root)
      try assertEquals(files.count(), 1L)
      finally files.close()
    }
  }

  test("malformed JSON errors are sanitized and never overwrite existing files") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val json =
        """{"userName":"operator","providers":{"account":{"providerType":"ChatGPT","credentials":{"accessToken":"secret-placeholder","refreshToken":"refresh-placeholder","expiresAtMillis":"bad-secret-value"}}}}"""
      Files.writeString(path, json)
      val result = run(ConfigFile.loadOrInitialize(path).either)
      assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
      assert(!result.swap.toOption.get.toString.contains("secret"))
      assertEquals(Files.readString(path), json)
    }
  }

  test("provider identities round-trip solely as map keys") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val provider = ProviderConfig.OAI("http://localhost")
      val config = Config(
        providers = Map(
          ProviderId.unsafe("local") -> provider,
          ProviderId.unsafe("other") -> provider
        )
      )
      val json = config.toJson
      assert(!json.contains("\"id\""))
      Files.writeString(path, json)
      assertEquals(run(ConfigFile.loadOrInitialize(path)), config)
      assertEquals(Files.readString(path), json)
    }
  }

  test("dangling provider references fail validation without rewriting") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val id = ProviderId.unsafe("local")
      val config = Config(agents = Map(AgentId("bob") -> AgentConfig("OAIAgent", Seq(id))))
      Files.writeString(path, config.toJson)
      assert(run(ConfigFile.loadOrInitialize(path).either).isLeft)
      assertEquals(Files.readString(path), config.toJson)
    }
  }

  test("non-file paths fail without changing the directory") {
    withDirectory { root =>
      assert(run(ConfigFile.loadOrInitialize(root).either).isLeft)
      assert(Files.isDirectory(root))
    }
  }

  test("legacy terminal keys ignore all values and emit one fixed sanitized warning without rewriting") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val warning =
        "Ignoring removed configuration keys userName, defaultAgentId, transcriberAgent; use REPL flags --user-name, --agent, --transcriber-agent instead"
      val legacyFields = List(
        """"userName":"SECRET_NAME"""",
        """"defaultAgentId":"SECRET_MISSING_AGENT"""",
        """"transcriberAgent":"SECRET_MISSING_TRANSCRIBER"""",
        """"userName":[],"defaultAgentId":123,"transcriberAgent":{"SECRET":"VALUE"}"""
      )
      for fields <- legacyFields do {
        val legacy = s"{$fields}"
        val logs = new CopyOnWriteArrayList[String]()
        val logger = ZLogger.default.map(line => { logs.add(line); () })
        Files.writeString(path, legacy)
        val loaded = run {
          ZIO.scoped {
            (Runtime.removeDefaultLoggers >>> Runtime.addLogger(logger)).build *> ConfigFile.loadOrInitialize(path)
          }
        }
        assertEquals(loaded, Config())
        assertEquals(legacy.fromJson[Config], Right(Config()))
        assertEquals(loaded.toJson, Config().toJson)
        assertEquals(logs.size, 1)
        assert(logs.get(0).contains("level=WARN"))
        assert(logs.get(0).contains(warning))
        assert(!logs.get(0).contains("SECRET"))
        assertEquals(Files.readString(path), legacy)
      }
    }
  }

  test("unknown fields remain forward compatible and nested terminal key names do not warn") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val json = """{"future":{"userName":"unused"},"browserAudioPort":6190}"""
      val logs = new CopyOnWriteArrayList[String]()
      val logger = ZLogger.default.map(line => { logs.add(line); () })
      Files.writeString(path, json)
      val loaded = run {
        ZIO.scoped {
          (Runtime.removeDefaultLoggers >>> Runtime.addLogger(logger)).build *> ConfigFile.loadOrInitialize(path)
        }
      }
      assertEquals(loaded, Config(browserAudioPort = 6190))
      assert(logs.isEmpty)
      assertEquals(Files.readString(path), json)
    }
  }

  test("malformed capability settings fail without echoing arbitrary decoder values") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val json =
        """{"userName":"operator","agents":{"agent":{"agentType":"OAIAgent","capabilities":{"tools":"SECRET_INVALID_TYPE"}}}}"""
      Files.writeString(path, json)
      val error = run(ConfigFile.loadOrInitialize(path).either).swap.toOption.get
      assert(error.isInstanceOf[YallmoError.InvalidConfig])
      assert(!error.getMessage.contains("SECRET_INVALID_TYPE"))
      assertEquals(Files.readString(path), json)
    }
  }

}
