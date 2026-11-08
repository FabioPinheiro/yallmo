package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.{AgentId, YallmoError}
import munit.FunSuite
import zio.{Config as _, *}
import zio.json.*
import zio.json.ast.Json

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

final class YamlConfigSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withDirectory(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("yaml-config-suite-")
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.iterator.asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private val fixture = """# Private operator configuration
agents:
  assistant:
    agentType: OAIAgent
    providerIds: [local]
    filesystem:
      root: ./workspace # relative to process directory
    capabilities:
      tools: [readFile, listFiles, scalamode]
    config:
      model: synthetic
      systemPrompt: |
        You are helpful.
        Keep answers concise.
providers:
  local:
    providerType: OAI
    endpoint: http://localhost:1234/v1
"""

  test("YAML and YML load nested typed configuration and literal prompts without rewriting") {
    withDirectory { root =>
      for name <- List("yallmo.yaml", "yallmo.yml", "YALLMO.YAML") do {
        val path = root.resolve(name)
        Files.writeString(path, fixture)
        val config = run(ConfigFile.loadOrInitialize(path))
        val agent = config.agents(AgentId("assistant"))
        assertEquals(agent.filesystem, Some(FileSystemConfig("./workspace")))
        assertEquals(agent.providerIds, Seq(ProviderId.unsafe("local")))
        assertEquals(
          agent.config.fields.toMap.apply("systemPrompt"),
          Json.Str("You are helpful.\nKeep answers concise.\n")
        )
        assertEquals(config.browserAudioPort, 5180)
        assertEquals(Files.readString(path), fixture)
        assert(!Files.exists(root.resolve("workspace")))
      }
    }
  }

  test("missing YAML initializes privately and concurrent callers see one complete document") {
    withDirectory { root =>
      val path = root.resolve("nested/yallmo.yaml")
      val configs = run(ZIO.foreachPar(1 to 20)(_ => ConfigFile.loadOrInitialize(path)))
      assert(configs.forall(_ == Config()))
      val text = Files.readString(path)
      assert(text.contains("browserAudioPort: 5180"))
      assertEquals(ConfigDocument.parse(path, text).map(_.config), Right(Config()))
      if Files.getFileStore(path).supportsFileAttributeView("posix") then
        assertEquals(Files.getPosixFilePermissions(path), PosixFilePermissions.fromString("rw-------"))
      assertEquals(Files.list(path.getParent).useCount, 1L)
    }
  }

  test("YAML uses the existing type and reference validation with sanitized errors") {
    withDirectory { root =>
      val path = root.resolve("yallmo.yaml")
      val invalid = List(
        "browserAudioPort: SECRET_BAD_TYPE\n",
        "browserAudioPort: 0\n",
        "agents: {a: {agentType: OAIAgent, providerIds: [SECRET_MISSING]}}\n",
        "agents: {a: {agentType: OAIAgent, filesystem: {root: '  '}}}\n",
        "agents: {a: {agentType: OAIAgent, capabilities: {tools: SECRET_BAD_TYPE}}}\n",
        "providers: [SECRET_BAD_TYPE]\n"
      )
      for text <- invalid do {
        Files.writeString(path, text)
        val error = run(ConfigFile.loadOrInitialize(path).either).swap.toOption.get
        assert(error.isInstanceOf[YallmoError.InvalidConfig])
        assert(!error.getMessage.contains("SECRET"))
        assertEquals(Files.readString(path), text)
      }
    }
  }

  test("ambiguous or unsafe YAML is rejected rather than silently resolved") {
    val invalid = List(
      "browserAudioPort: 5180\nbrowserAudioPort: 6190\n",
      "future: {nested: 1, nested: 2}\n",
      "? [complex, key]\n: value\n",
      "future: {1: value}\n",
      "future: &a [*a]\n",
      "future: &a secret\ncopy: *a\n",
      "&a future: *a\n",
      "future: &a key\n*a: value\n",
      "future: !!null SECRET_NOT_NULL\n",
      "future: !!java/object {}\n",
      "future: !custom secret\n",
      "future: {<<: {nested: 1}}\n",
      "---\n{}\n---\n{}\n",
      "[not, an, object]\n",
      "# empty document\n",
      "future: [SECRET_MALFORMED\n"
    )
    for text <- invalid do {
      val result = ConfigDocument.parse(Path.of("test.yaml"), text)
      assert(result.isLeft, text)
      assert(!result.swap.toOption.get.getMessage.contains("SECRET"))
    }
  }

  test("JSON-compatible scalars preserve words, dates, quoted numbers and multiline strings") {
    val text = """future:
  on: off
  yes: no
  date: 2026-10-31
  number: "001"
  bool: false
  nothing: null
  folded: >-
    hello
    world
"""
    val document = ConfigDocument.parse(Path.of("test.yaml"), text).toOption.get
    val fields = document.json.fields.toMap.apply("future").asInstanceOf[Json.Obj].fields.toMap
    assertEquals(fields("on"), Json.Str("off"))
    assertEquals(fields("yes"), Json.Str("no"))
    assertEquals(fields("date"), Json.Str("2026-10-31"))
    assertEquals(fields("number"), Json.Str("001"))
    assertEquals(fields("bool"), Json.Bool(false))
    assertEquals(fields("nothing"), Json.Null)
    assertEquals(fields("folded"), Json.Str("hello world"))
    val rendered = document.render(document.json).toOption.get
    assertEquals(ConfigDocument.parse(Path.of("test.yaml"), rendered).map(_.json), Right(document.json))
  }

  test("explicit migration preserves raw unknown fields and source bytes, and refuses existing destinations") {
    withDirectory { root =>
      val source = root.resolve("yallmo.json")
      val destination = root.resolve("yallmo.yaml")
      val text =
        """{"agents":{},"providers":{},"future":{"enabled":true,"values":[1,null,"on"]},"userName":"legacy"}""" + "\n\n"
      Files.writeString(source, text)
      run(ConfigFile.migrateJsonToYaml(source, destination))
      assertEquals(Files.readString(source), text)
      val yaml = Files.readString(destination)
      assertEquals(ConfigDocument.parse(destination, yaml).toOption.get.json, text.fromJson[Json.Obj].toOption.get)
      if Files.getFileStore(destination).supportsFileAttributeView("posix") then
        assertEquals(Files.getPosixFilePermissions(destination), PosixFilePermissions.fromString("rw-------"))
      assert(run(ConfigFile.migrateJsonToYaml(source, destination).either).isLeft)
      assertEquals(Files.readString(destination), yaml)
      assertEquals(Files.readString(source), text)
      assertEquals(Files.list(root).useCount, 2L)
      assertEquals(run(ConfigFile.loadOrInitialize(destination)), Config())
    }
  }

  test("explicit migration preserves environment references instead of persisting resolved values") {
    withDirectory { root =>
      val source = root.resolve("source.json")
      val destination = root.resolve("target.yaml")
      val text = """{"agents":{"assistant":{"agentType":"OAIAgent","config":{"systemPrompt":"${PATH}"}}}}"""
      Files.writeString(source, text)
      run(ConfigFile.migrateJsonToYaml(source, destination))
      val yaml = Files.readString(destination)
      assert(yaml.contains("${PATH}"))
      assert(!yaml.contains(sys.env("PATH")))
      assertEquals(Files.readString(source), text)
      assertEquals(
        run(ConfigFile.loadOrInitialize(destination))
          .agents(AgentId("assistant"))
          .config
          .fields
          .toMap
          .apply("systemPrompt"),
        Json.Str(sys.env("PATH"))
      )
    }
  }

  test("migration does not initialize a missing source or publish invalid data or wrong extensions") {
    withDirectory { root =>
      val source = root.resolve("missing.json")
      val destination = root.resolve("yallmo.yml")
      assert(run(ConfigFile.migrateJsonToYaml(source, destination).either).isLeft)
      assert(!Files.exists(source))
      assert(!Files.exists(destination))
      Files.writeString(source, "{\"browserAudioPort\":0}")
      assert(run(ConfigFile.migrateJsonToYaml(source, destination).either).isLeft)
      assert(!Files.exists(destination))
      Files.writeString(source, "{}")
      assert(run(ConfigFile.migrateJsonToYaml(source, root.resolve("wrong.json")).either).isLeft)
      assert(!Files.exists(root.resolve("wrong.json")))
    }
  }

  test("concurrent migrations publish one complete YAML destination without overwriting") {
    withDirectory { root =>
      val source = root.resolve("source.json")
      val target = root.resolve("target.yaml")
      Files.writeString(source, "{}")
      val results = run(ZIO.foreachPar(1 to 12)(_ => ConfigFile.migrateJsonToYaml(source, target).either))
      assertEquals(results.count(_.isRight), 1)
      assertEquals(run(ConfigFile.loadOrInitialize(target)), Config())
      assertEquals(Files.readString(source), "{}")
      assertEquals(Files.list(root).useCount, 2L)
    }
  }

  test("fresh YAML round-trips JSON keys and scalar-looking string values without semantic changes") {
    val tree =
      """{"future":{"<<":"literal key","true":"false","null":"null","":"empty key","001":"001","values":[1.25,1e3,-2,true,null,"on","line one\\nline two"]}}"""
        .fromJson[Json.Obj]
        .toOption
        .get
    val yaml = ConfigDocument.yaml(tree).toOption.get
    assertEquals(ConfigDocument.parse(Path.of("test.yaml"), yaml).toOption.get.json, tree)
  }

  extension (paths: java.util.stream.Stream[Path])
    private def useCount: Long = try paths.count()
    finally paths.close()
}
