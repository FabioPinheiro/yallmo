package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.{AgentId, YallmoError}
import munit.FunSuite
import zio.{Config as _, *}
import zio.json.*

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator

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

  test("missing config is initialized privately with empty catalogs and no selected agent") {
    withDirectory { root =>
      val path = root.resolve("nested/yallmo.json")
      val initial = run(ConfigFile.loadOrInitialize(path, "operator"))
      assertEquals(initial, Config("operator"))
      assertEquals(Files.readString(path).fromJson[Config], Right(initial))
      if Files.getFileStore(path).supportsFileAttributeView("posix") then
        assertEquals(Files.getPosixFilePermissions(path), PosixFilePermissions.fromString("rw-------"))
      val before = Files.readString(path)
      assertEquals(run(ConfigFile.loadOrInitialize(path, "another operator")), initial)
      assertEquals(Files.readString(path), before)
    }
  }

  test("existing configuration and literal credentials are loaded without rewriting") {
    withDirectory { root =>
      val id = ProviderId.unsafe("account")
      val config = Config(
        "operator",
        providers = Map(
          id -> ProviderConfig.ChatGPT(
            Some(OAuthCredentialsConfig("synthetic-access", "synthetic-refresh", 0))
          )
        )
      )
      val json = config.toJsonPretty + "\n\n"
      val path = root.resolve("yallmo.json")
      Files.writeString(path, json)
      assertEquals(run(ConfigFile.loadOrInitialize(path, "ignored")), config)
      assertEquals(Files.readString(path), json)
    }
  }

  test("concurrent initialization publishes one complete configuration without overwriting a winner") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val results = run(ZIO.foreachPar(1 to 20)(i => ConfigFile.loadOrInitialize(path, s"operator-$i")))
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
      val result = run(ConfigFile.loadOrInitialize(path, "ignored").either)
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
        "operator",
        providers = Map(
          ProviderId.unsafe("local") -> provider,
          ProviderId.unsafe("other") -> provider
        )
      )
      val json = config.toJson
      assert(!json.contains("\"id\""))
      Files.writeString(path, json)
      assertEquals(run(ConfigFile.loadOrInitialize(path, "ignored")), config)
      assertEquals(Files.readString(path), json)
    }
  }

  test("blank names and dangling default/provider references fail validation") {
    withDirectory { root =>
      val path = root.resolve("yallmo.json")
      val id = ProviderId.unsafe("local")
      Seq(
        Config(" "),
        Config("operator", defaultAgentId = Some(AgentId("missing"))),
        Config("operator", agents = Map(AgentId("bob") -> AgentConfig("OAIAgent", Seq(id))))
      ).foreach { config =>
        Files.writeString(path, config.toJson)
        assert(run(ConfigFile.loadOrInitialize(path, "ignored").either).isLeft)
        assertEquals(Files.readString(path), config.toJson)
      }
    }
  }

  test("non-file paths and invalid initial names fail without creating a config") {
    withDirectory { root =>
      assert(run(ConfigFile.loadOrInitialize(root, "operator").either).isLeft)
      val path = root.resolve("missing.json")
      assert(run(ConfigFile.loadOrInitialize(path, " ").either).isLeft)
      assert(!Files.exists(path))
    }
  }
}
