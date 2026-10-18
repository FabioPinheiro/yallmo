package fmgp.yallmo.repl

import fmgp.yallmo.orchestration.config.{Config, OAuthCredentialsConfig, ProviderConfig, ProviderId}
import fmgp.yallmo.provider.openai.OpenAiCodexClient
import munit.FunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileSystems, Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

final class ReplProviderCatalogSuite extends FunSuite {
  import ReplProviderCatalog.*

  private val fixture = """{
    "userName": "synthetic-user",
    "defaultAgentId": "bob",
    "agents": {"bob": {"agentType": "OAIAgent", "providerIds": ["local"], "config": {"model": "synthetic"}}},
    "providers": {
      "local": {"providerType": "OAI", "endpoint": "http://localhost:1234/v1", "futureProviderField": {"keep": true}},
      "account": {"providerType": "ChatGPT", "credentials": {
        "accessToken": "synthetic-access-secret", "refreshToken": "synthetic-refresh-secret", "expiresAtMillis": 123
      }, "futureAccountField": [1, 2]},
      "logged-out": {"providerType": "ChatGPT"}
    },
    "unrelated": {"nested": ["preserve", 1, null, true]}
  }"""

  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }

  private def withFile(body: (Path, ReplProviderCatalog) => Unit): Unit = {
    val directory = Files.createTempDirectory("provider-catalog-suite-")
    val path = directory.resolve("synthetic.json")
    try {
      Files.writeString(path, fixture, UTF_8)
      body(path, run(ReplProviderCatalog.make(path)))
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
      val paths = Files.walk(directory)
      try paths.iterator.asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def tree(path: Path): Json = Files.readString(path, UTF_8).fromJson[Json].toOption.get
  private def config(path: Path): Config = Files.readString(path, UTF_8).fromJson[Config].toOption.get
  private def id(value: String): ProviderId = ProviderId.unsafe(value)
  private def children(path: Path): Set[String] = {
    val stream = Files.list(path.getParent)
    try stream.iterator.asScala.map(_.getFileName.toString).toSet
    finally stream.close()
  }

  test("list and load include all configured providers without exposing credentials") {
    withFile { (_, catalog) =>
      val entries = run(catalog.list)
      assertEquals(entries.map(_.displayId), Vector("account", "local", "logged-out"))
      assertEquals(
        entries.map(_.credentials),
        Vector(CredentialStatus.Configured, CredentialStatus.NotApplicable, CredentialStatus.LoggedOut)
      )
      assertEquals(run(catalog.load(id("account"))).map(_.endpoint), Some(OpenAiCodexClient.CodexResponsesUrl))
      assertEquals(run(catalog.load(id("missing"))), None)
      assert(!entries.toString.contains("synthetic-access-secret"))
      assert(!entries.toString.contains("synthetic-refresh-secret"))
    }
  }

  test("details sanitize endpoint userinfo, query, fragment and terminal control characters") {
    withFile { (path, catalog) =>
      val synthetic = Config(
        "test",
        providers = Map(
          id("unsafe\u001b[31m\n\u202e") -> ProviderConfig.OAI(
            "https://user:synthetic-password@example.com:8443/v1?key=synthetic-query#synthetic-fragment"
          ),
          id("controls") -> ProviderConfig.OAI("https://example.com/\u001b[31m"),
          id("bad") -> ProviderConfig.ChatGPT(endpoint = Some("not-a-url synthetic-secret"))
        )
      )
      Files.writeString(path, synthetic.toJson, UTF_8)
      val entries = run(catalog.list)
      val endpoint = entries.find(_.displayId.startsWith("unsafe")).get
      assertEquals(endpoint.endpoint, "https://example.com:8443/v1")
      assertEquals(endpoint.displayId, "unsafe?[31m??")
      assertEquals(entries.find(_.displayId == "controls").get.endpoint, "<invalid endpoint>")
      assertEquals(entries.find(_.displayId == "bad").get.endpoint, "<invalid endpoint>")
      val rendered = entries.toString
      List("synthetic-password", "synthetic-query", "synthetic-fragment", "synthetic-secret", "\u001b", "\n", "\u202e")
        .foreach { secret =>
          assert(!rendered.contains(secret))
        }
    }
  }

  test("create OAI and logged-out ChatGPT while preserving all existing JSON fields and credentials") {
    withFile { (path, catalog) =>
      val before = tree(path).asInstanceOf[Json.Obj]
      val local = run(catalog.add("new-local", Creation.OAI("https://example.com/api/v1")))
      val account = run(catalog.add("new-account", Creation.ChatGPT))
      assertEquals(local.providerType, ProviderType.OAI)
      assertEquals(account.credentials, CredentialStatus.LoggedOut)
      assertEquals(account.endpoint, OpenAiCodexClient.CodexResponsesUrl)
      assertEquals(config(path).providers(id("new-local")), ProviderConfig.OAI("https://example.com/api/v1"))
      assertEquals(config(path).providers(id("new-account")), ProviderConfig.ChatGPT())
      val after = tree(path).asInstanceOf[Json.Obj]
      assertEquals(after.fields.filterNot(_._1 == "providers"), before.fields.filterNot(_._1 == "providers"))
      val beforeProviders = before.fields.find(_._1 == "providers").get._2.asInstanceOf[Json.Obj]
      val afterProviders = after.fields.find(_._1 == "providers").get._2.asInstanceOf[Json.Obj]
      beforeProviders.fields.foreach { field => assert(afterProviders.fields.contains(field)) }
      val newAccount = afterProviders.fields.find(_._1 == "new-account").get._2.asInstanceOf[Json.Obj]
      assertEquals(newAccount.fields, Chunk("providerType" -> Json.Str("ChatGPT")))
    }
  }

  test("credential update changes only the selected credentials and preserves private permissions") {
    withFile { (path, catalog) =>
      val before = tree(path).asInstanceOf[Json.Obj]
      val credentials = OAuthCredentialsConfig("new-access", "new-refresh", 456)
      run(catalog.updateChatGptCredentials(id("account"), credentials))
      val after = tree(path).asInstanceOf[Json.Obj]
      assertEquals(after.fields.filterNot(_._1 == "providers"), before.fields.filterNot(_._1 == "providers"))
      def providers(root: Json.Obj) = root.fields.find(_._1 == "providers").get._2.asInstanceOf[Json.Obj]
      val oldProviders = providers(before)
      val newProviders = providers(after)
      assertEquals(newProviders.fields.filterNot(_._1 == "account"), oldProviders.fields.filterNot(_._1 == "account"))
      def account(root: Json.Obj) = root.fields.find(_._1 == "account").get._2.asInstanceOf[Json.Obj]
      assertEquals(
        account(newProviders).fields.filterNot(_._1 == "credentials"),
        account(oldProviders).fields.filterNot(_._1 == "credentials")
      )
      assertEquals(
        config(path).providers(id("account")),
        ProviderConfig.ChatGPT(
          Some(
            OAuthCredentialsConfig("new-access", "new-refresh", 456)
          )
        )
      )
      assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), "rw-------")
      assertEquals(children(path), Set("synthetic.json"))
      assertEquals(run(catalog.load(id("account"))).get.credentials, CredentialStatus.Configured)
    }
  }

  test("credential updates reject invalid fields and non-ChatGPT or untrusted targets without writing") {
    withFile { (path, catalog) =>
      val before = Files.readAllBytes(path).toVector
      val valid = OAuthCredentialsConfig("access", "refresh", 1)
      List(
        valid.copy(accessToken = ""),
        valid.copy(refreshToken = ""),
        valid.copy(accessToken = "has space"),
        valid.copy(refreshToken = "has\nnewline"),
        valid.copy(accessToken = "control\u001b"),
        valid.copy(refreshToken = "format\u202e"),
        valid.copy(expiresAtMillis = -1)
      ).foreach { credentials =>
        assertEquals(
          run(catalog.updateChatGptCredentials(id("account"), credentials).either),
          Left(CredentialError.InvalidCredentials)
        )
      }
      assertEquals(run(catalog.updateChatGptCredentials(id("local"), valid).either), Left(CredentialError.NotChatGPT))
      assertEquals(
        run(catalog.updateChatGptCredentials(id("missing"), valid).either),
        Left(CredentialError.MissingProvider)
      )
      assertEquals(Files.readAllBytes(path).toVector, before)
      val changed = fixture.replace(
        "\"futureAccountField\":",
        "\"endpoint\":\"https://untrusted.example/responses\",\"futureAccountField\":"
      )
      Files.writeString(path, changed, UTF_8)
      assertEquals(
        run(catalog.validateLoginTarget(id("account")).either),
        Left(CredentialError.Catalog(Error.InvalidEndpoint))
      )
      assertEquals(
        run(catalog.updateChatGptCredentials(id("account"), valid).either),
        Left(CredentialError.Catalog(Error.InvalidEndpoint))
      )
      assertEquals(Files.readString(path, UTF_8), changed)
    }
  }

  test("credential update retains explicit trusted endpoint and serializes with concurrent adds") {
    withFile { (path, catalog) =>
      Files.writeString(
        path,
        fixture.replace(
          "\"futureAccountField\":",
          s"\"endpoint\":\"${OpenAiCodexClient.CodexResponsesUrl}/\",\"futureAccountField\":"
        ),
        UTF_8
      )
      run(
        catalog
          .updateChatGptCredentials(id("account"), OAuthCredentialsConfig("access", "refresh", 1))
          .zipPar(catalog.add("concurrent", Creation.ChatGPT))
      )
      assertEquals(run(catalog.list).size, 4)
      val account = config(path).providers(id("account")).asInstanceOf[ProviderConfig.ChatGPT]
      assertEquals(account.endpoint, Some(OpenAiCodexClient.CodexResponsesUrl + "/"))
      assertEquals(account.credentials.map(_.accessToken), Some("access"))
    }
  }

  test("add handles an omitted provider catalog") {
    withFile { (path, catalog) =>
      Files.writeString(path, """{"userName":"test", "extra":true}""", UTF_8)
      assertEquals(run(catalog.list), Vector.empty)
      run(catalog.add("first", Creation.ChatGPT))
      assertEquals(config(path).providers.keySet, Set(id("first")))
    }
  }

  test("duplicates, blank IDs and invalid endpoints leave target bytes unchanged") {
    withFile { (path, catalog) =>
      val before = Files.readAllBytes(path).toVector
      assertEquals(run(catalog.add("local", Creation.ChatGPT).either), Left(Error.DuplicateId))
      List("", "  ", "\n\t").foreach { value =>
        assertEquals(run(catalog.add(value, Creation.ChatGPT).either), Left(Error.InvalidId))
      }
      List("", "/relative", "ftp://example.com", "https:relative", "http://", "https://host/\n", " https://host")
        .foreach { endpoint =>
          assertEquals(run(catalog.add("new", Creation.OAI(endpoint)).either), Left(Error.InvalidEndpoint))
        }
      assertEquals(Files.readAllBytes(path).toVector, before)
      assertEquals(children(path), Set("synthetic.json"))
    }
  }

  test("constructing an abandoned creation effect or loading is cancellation-neutral") {
    withFile { (path, catalog) =>
      val before = Files.readAllBytes(path).toVector
      val abandoned = catalog.add("abandoned", Creation.ChatGPT)
      assert(abandoned ne null)
      run(catalog.list)
      run(catalog.load(id("local")))
      assertEquals(Files.readAllBytes(path).toVector, before)
    }
  }

  test("malformed JSON, invalid field types and duplicate keys fail without clobbering") {
    withFile { (path, catalog) =>
      List(
        "{ malformed synthetic-secret",
        """{"userName":"test","providers":[]}""",
        """{"userName":"  "}""",
        """{"userName":"test","defaultAgentId":"missing"}""",
        """{"userName":"test","providers":{"same":{"providerType":"ChatGPT"},"same":{"providerType":"ChatGPT"}}}""",
        """{"userName":"test","providers":{"x":{"providerType":"Unknown","secret":"synthetic-secret"}}}"""
      ).foreach { input =>
        Files.writeString(path, input, UTF_8)
        assertEquals(run(catalog.list.either), Left(Error.InvalidConfig))
        assertEquals(run(catalog.add("new", Creation.ChatGPT).either), Left(Error.InvalidConfig))
        assertEquals(Files.readString(path, UTF_8), input)
        assertEquals(children(path), Set("synthetic.json"))
      }
    }
  }

  test("save rereads latest config rather than overwriting an earlier catalog snapshot") {
    withFile { (path, catalog) =>
      run(catalog.list)
      val otherWriter = fixture
        .replace("synthetic-user", "updated-user")
        .replace("\"unrelated\":", "\"anotherField\": 42, \"unrelated\":")
      Files.writeString(path, otherWriter, UTF_8)
      run(catalog.add("new", Creation.ChatGPT))
      assertEquals(config(path).userName, "updated-user")
      assert(tree(path).asInstanceOf[Json.Obj].fields.contains("anotherField" -> Json.Num(42)))
    }
  }

  test("same-service concurrent adds are serialized without losing entries") {
    withFile { (_, catalog) =>
      run(ZIO.foreachPar(1 to 12)(index => catalog.add(s"new-$index", Creation.ChatGPT)))
      assertEquals(run(catalog.list).size, 15)
    }
  }

  test("successful atomic replacement is owner-only and leaves no temporary file") {
    withFile { (path, catalog) =>
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
      run(catalog.add("new", Creation.ChatGPT))
      assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), "rw-------")
      assertEquals(children(path), Set("synthetic.json"))
    }
  }

  test("failed save in a non-writable directory leaves target and permissions unchanged") {
    withFile { (path, catalog) =>
      val before = Files.readAllBytes(path).toVector
      val permissions = Files.getPosixFilePermissions(path)
      Files.setPosixFilePermissions(path.getParent, PosixFilePermissions.fromString("r-x------"))
      try {
        assertEquals(run(catalog.add("new", Creation.ChatGPT).either), Left(Error.SaveFailed))
        assertEquals(
          run(catalog.updateChatGptCredentials(id("account"), OAuthCredentialsConfig("access", "refresh", 1)).either),
          Left(CredentialError.Catalog(Error.SaveFailed))
        )
        assertEquals(Files.readAllBytes(path).toVector, before)
        assertEquals(Files.getPosixFilePermissions(path), permissions)
        assertEquals(children(path), Set("synthetic.json"))
      } finally Files.setPosixFilePermissions(path.getParent, PosixFilePermissions.fromString("rwx------"))
    }
  }

  test("filesystems without private POSIX persistence fail closed without clobbering") {
    withFile { (path, _) =>
      val archive = path.resolveSibling("synthetic.zip")
      val fileSystem = FileSystems.newFileSystem(URI.create(s"jar:${archive.toUri}"), Map("create" -> "true").asJava)
      try {
        val target = fileSystem.getPath("/synthetic.json")
        Files.writeString(target, fixture, UTF_8)
        val catalog = run(ReplProviderCatalog.make(target))
        assertEquals(run(catalog.list).size, 3)
        assertEquals(run(catalog.add("new", Creation.ChatGPT).either), Left(Error.SaveFailed))
        assertEquals(Files.readString(target, UTF_8), fixture)
        assertEquals(children(target), Set("synthetic.json"))
      } finally fileSystem.close()
    }
  }

  test("symlink targets are refused without modifying their referent") {
    withFile { (path, catalog) =>
      val referent = path.resolveSibling("referent.json")
      Files.move(path, referent)
      val before = Files.readAllBytes(referent).toVector
      Files.createSymbolicLink(path, referent.getFileName)
      assertEquals(run(catalog.list.either), Left(Error.ReadFailed))
      assertEquals(run(catalog.add("new", Creation.ChatGPT).either), Left(Error.ReadFailed))
      assertEquals(
        run(catalog.updateChatGptCredentials(id("account"), OAuthCredentialsConfig("access", "refresh", 1)).either),
        Left(CredentialError.Catalog(Error.ReadFailed))
      )
      assert(Files.isSymbolicLink(path))
      assertEquals(Files.readAllBytes(referent).toVector, before)
    }
  }

  test("missing config is not initialized by this component") {
    withFile { (path, catalog) =>
      Files.delete(path)
      assertEquals(run(catalog.list.either), Left(Error.ReadFailed))
      assertEquals(run(catalog.add("new", Creation.ChatGPT).either), Left(Error.ReadFailed))
      assert(!Files.exists(path))
      assertEquals(children(path), Set.empty)
    }
  }
}
