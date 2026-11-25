package fmgp.yallmo.tool.utpc

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.ExternalFunctionCall
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

final class UTPCToolSuite extends FunSuite {
  private val input =
    """{"type":"object","properties":{"latitude":{"type":"number","minimum":-90,"maximum":90},"current":{"type":"string","pattern":"^(rain|temperature_2m)(,(rain|temperature_2m))*$"}},"required":["latitude","current"],"additionalProperties":false}"""
      .fromJson[Json]
      .toOption
      .get
  private val manual = UTCPManual(
    "1.0.0",
    "1.1.0",
    Seq(
      UTCPManual.Tool(
        "weather",
        "Weather summary",
        input,
        Json.Bool(true),
        Json.Obj(
          "transport_type" -> Json.Str("http"),
          "name" -> Json.Str("weather"),
          "url" -> Json.Str("https://example.test/weather")
        )
      )
    )
  )
  private val valid = """{"latitude":37,"current":"rain,temperature_2m"}"""

  private final class Fake extends UTPCHttpProvider {
    var calls = Vector.empty[(UTPCHttpTransport, Json.Obj)]
    var output: Json = Json.Obj("ok" -> Json.Bool(true))
    var failure: Option[YallmoError] = None
    def discover(transport: UTPCHttpTransport): IO[YallmoError, UTCPManual] = ZIO.dieMessage("No discovery allowed")
    def call(transport: UTPCHttpTransport, arguments: Json.Obj): IO[YallmoError, Json] = ZIO.suspendSucceed {
      calls :+= transport -> arguments
      failure.fold(ZIO.succeed(output))(ZIO.fail(_))
    }
  }
  private def invoke(tool: UTPCTool, args: String) =
    tool.execute(ExternalFunctionCall(UTPCToolDefinition.Execute, args))

  test("list, unresolved description and execution are distinct; startup is immutable and inert") {
    withRoot { root =>
      write(root, "weather", manual)
      val fake = new Fake
      val tool = run(UTPCTool.load(Some(root), fake)).get
      assert(tool.definition.description.endsWith("Available manuals: \"weather\""))
      assert(!tool.definition.description.contains("example.test"))
      assertEquals(fake.calls.size, 0)
      assertEquals(
        run(invoke(tool, "{}")).text.fromJson[Json],
        run(invoke(tool, """{"agrs":{}}""")).text.fromJson[Json]
      )
      val catalog = run(invoke(tool, "{}")).text
      assert(catalog.contains("weather") && catalog.contains("Weather summary"))
      assert(!catalog.contains("example.test"))
      assertEquals(run(invoke(tool, """{"manual":"weather"}""")).text.fromJson[UTCPManual], Right(manual))
      Files.delete(root.resolve("utpc/manual/weather.json"))
      assert(run(invoke(tool, """{"manual":"weather"}""")).text.contains("example.test"))
      assertEquals(run(invoke(tool, s"""{"manual":"weather","agrs":$valid}""")).text, """{"ok":true}""")
      assertEquals(fake.calls.size, 1)
      assertEquals(fake.calls.head._2.toJson, valid)
      assert(run(invoke(tool, """{"manual":"weather","agrs":{}}""").either).isLeft)
      assertEquals(fake.calls.size, 1)
    }
  }

  test(
    "direct boundary rejects null, wrong types, unknown fields, duplicates, paths and invalid nested arguments before calls"
  ) {
    withRoot { root =>
      write(root, "weather", manual)
      val fake = new Fake
      val tool = run(UTPCTool.load(Some(root), fake)).get
      val outer = Seq(
        "null",
        "[]",
        """{"manual":null}""",
        """{"agrs":null}""",
        """{"manual":3}""",
        """{"agrs":[]}""",
        """{"action":"list"}""",
        """{"tool":"weather"}""",
        """{"manual":"weather","manual":"weather"}""",
        """{"manual":"../weather"}"""
      )
      val nested = Seq(
        "{}",
        """{"latitude":91,"current":"rain"}""",
        """{"latitude":-91,"current":"rain"}""",
        """{"latitude":"37","current":"rain"}""",
        """{"latitude":37,"current":"unknown"}""",
        """{"latitude":37,"current":"rain","extra":1}""",
        """{"latitude":37,"latitude":38,"current":"rain"}"""
      )
      (outer ++ nested.map(args => s"""{"manual":"weather","agrs":$args}""")).foreach { args =>
        assert(run(invoke(tool, args).either).isLeft, args)
      }
      val arrayError = run(
        invoke(tool, """{"manual":"weather","agrs":{"latitude":37,"current":["rain"]}}""").either
      ).swap.toOption.get.getMessage
      assert(arrayError.contains("agrs.current: expected string, received array"))
      assert(arrayError.contains("Read the manual"))
      assertEquals(fake.calls.size, 0)
    }
  }

  test("zero/multiple tools and unsupported validation keywords stay describable but cannot execute") {
    withRoot { root =>
      write(root, "zero", manual.copy(tools = Seq.empty))
      write(root, "two", manual.copy(tools = manual.tools ++ manual.tools.map(_.copy(name = "other"))))
      val unsupported =
        manual.copy(tools = manual.tools.map(_.copy(inputs = Json.Obj("oneOf" -> Json.Arr(Json.Bool(true))))))
      write(root, "unsupported", unsupported)
      val fake = new Fake
      val tool = run(UTPCTool.load(Some(root), fake)).get
      assert(tool.definition.description.endsWith("Available manuals: \"two\"; \"unsupported\"; \"zero\""))
      Seq("zero", "two", "unsupported").foreach { id =>
        assert(run(invoke(tool, s"""{"manual":"$id"}""")).text.fromJson[UTCPManual].isRight)
        val error = run(invoke(tool, s"""{"manual":"$id","agrs":{}}""").either).swap.toOption.get
        assert(error.getMessage.contains(if id == "unsupported" then "schema" else "exactly one tool"))
      }
      assertEquals(fake.calls.size, 0)
    }
  }

  test("known literal secrets are rejected while placeholders remain faithfully unresolved") {
    withRoot { root =>
      val transport = manual.tools.head.toolTransport
      def auth(value: Json.Obj) =
        manual.copy(tools = manual.tools.map(_.copy(toolTransport = Json.Obj(transport.fields :+ ("auth" -> value)))))
      Seq("api_key", "password", "client_secret", "key").foreach { key =>
        write(root, key, auth(Json.Obj(key -> Json.Str("KNOWN_PRIVATE_SECRET"))))
      }
      write(
        root,
        "header",
        manual.copy(tools =
          manual.tools.map(
            _.copy(toolTransport =
              Json.Obj(transport.fields :+ ("headers" -> Json.Obj("X-Api-Key" -> Json.Str("KNOWN_PRIVATE_SECRET"))))
            )
          )
        )
      )
      Seq(
        "encoded-query" -> "?api%5Fkey=KNOWN_PRIVATE_SECRET",
        "encoded-token" -> "?%74oken=KNOWN_PRIVATE_SECRET",
        "malformed-query" -> "?api%ZZkey=KNOWN_PRIVATE_SECRET"
      ).foreach { case (id, query) =>
        val unsafe = manual.copy(tools = manual.tools.map { tool =>
          tool.copy(toolTransport = Json.Obj(tool.toolTransport.fields.map {
            case ("url", _) => "url" -> Json.Str(s"https://example.test/weather$query")
            case entry      => entry
          }))
        })
        write(root, id, unsafe)
      }
      val unresolved = auth(Json.Obj("auth_type" -> Json.Str("api_key"), "api_key" -> Json.Str("${WEATHER_KEY}")))
      write(root, "safe", unresolved)
      val fake = new Fake
      val tool = run(UTPCTool.load(Some(root), fake)).get
      val catalog = run(invoke(tool, "{}")).text
      assertEquals(catalog.fromJson[Json.Arr].toOption.get.elements.size, 1)
      assert(!catalog.contains("KNOWN_PRIVATE_SECRET"))
      assertEquals(run(invoke(tool, """{"manual":"safe"}""")).text.fromJson[UTCPManual], Right(unresolved))
      assertEquals(fake.calls.size, 0)
    }
  }

  test("GitHub's public User-Agent loads alongside weather without allowing literal bearer tokens") {
    withRoot { root =>
      def github(authorization: String): UTCPManual = manual.copy(tools = manual.tools.map { tool =>
        tool.copy(toolTransport =
          Json.Obj(
            "transport_type" -> Json.Str("http"),
            "name" -> Json.Str("github_api"),
            "url" -> Json.Str("https://api.github.com/user"),
            "headers" -> Json.Obj(
              "Accept" -> Json.Str("application/vnd.github+json"),
              "Authorization" -> Json.Str(authorization),
              "User-Agent" -> Json.Str("FabioPinheiro")
            )
          )
        )
      })
      val safe = github("Bearer ${GH_TOKEN}")
      write(root, "github", safe)
      write(root, "open-meteo", manual)
      write(root, "unsafe-github", github("Bearer KNOWN_PRIVATE_SECRET"))
      val fake = new Fake
      val tool = run(UTPCTool.load(Some(root), fake)).get
      assert(tool.definition.description.endsWith("Available manuals: \"github\"; \"open-meteo\""))
      assertEquals(run(invoke(tool, "{}")).text.fromJson[Json.Arr].toOption.get.elements.size, 2)
      assertEquals(run(invoke(tool, """{"manual":"github"}""")).text.fromJson[UTCPManual], Right(safe))
      assertEquals(fake.calls.size, 0)
    }
  }

  test("schema properties named headers are not mistaken for static transport credentials") {
    withRoot { root =>
      val schema = """{"type":"object","properties":{"headers":{"type":"object"},"password":{"type":"string"}}}"""
        .fromJson[Json]
        .toOption
        .get
      val value = manual.copy(tools = manual.tools.map(_.copy(inputs = schema)))
      write(root, "ordinary", value)
      val tool = run(UTPCTool.load(Some(root), new Fake)).get
      assertEquals(run(invoke(tool, """{"manual":"ordinary"}""")).text.fromJson[UTCPManual], Right(value))
    }
  }

  test("only rooted direct regular JSON files load; symlinks, oversized files and overflow fail closed") {
    withRoot { root =>
      val fake = new Fake
      assertEquals(run(UTPCTool.load(Some(root), fake)), None)
      write(root, "safe", manual)
      val directory = root.resolve("utpc/manual")
      Files.createDirectories(directory.resolve("nested"))
      Files.writeString(directory.resolve("nested/ignored.json"), manual.toJson)
      Files.writeString(directory.resolve("ignored.txt"), manual.toJson)
      Files.writeString(directory.resolve("huge.json"), " " * (UTPCTool.MaxFileBytes + 1))
      Files.createSymbolicLink(directory.resolve("link.json"), directory.resolve("safe.json"))
      val tool = run(UTPCTool.load(Some(root), fake)).get
      assertEquals(run(invoke(tool, "{}")).text.fromJson[Json.Arr].toOption.get.elements.size, 1)
      Files.createSymbolicLink(root.resolve("alias"), root.resolve("utpc"))
      assertEquals(run(UTPCTool.load(Some(root.resolve("alias")), fake)), None)
      (1 to UTPCTool.MaxEntries).foreach(i => Files.writeString(directory.resolve(s"entry-$i.txt"), ""))
      assertEquals(run(UTPCTool.load(Some(root), fake)), None)
      assertEquals(fake.calls.size, 0)
    }
  }

  test("gateway bounds input and output and never retries an oversized remote result") {
    withRoot { root =>
      write(root, "weather", manual)
      val fake = new Fake
      val tool = run(UTPCTool.load(Some(root), fake)).get
      assert(run(invoke(tool, " " * (UTPCTool.MaxArgumentChars + 1)).either).isLeft)
      assertEquals(fake.calls.size, 0)
      fake.output = Json.Str("x" * UTPCTool.MaxOutputChars)
      assert(run(invoke(tool, s"""{"manual":"weather","agrs":$valid}""").either).isLeft)
      assertEquals(fake.calls.size, 1)
    }
  }

  test("input-schema subset checks all branches and rejects unsupported or malformed constraints") {
    Seq(
      """{"minimum":"0"}""",
      """{"pattern":"["}""",
      """{"type":["string","null"]}""",
      """{"properties":{"unused":{"enum":[1]}}}""",
      """{"items":true}"""
    ).foreach { schema =>
      assert(UTPCInputSchema.validate(schema.fromJson[Json].toOption.get, Json.Obj()).isLeft)
    }
    assert(UTPCInputSchema.validate(input, valid.fromJson[Json].toOption.get).isRight)
  }

  test("provider failures preserve HTTP status but never disclose arbitrary messages or credentials") {
    withRoot { root =>
      write(root, "weather", manual)
      val fake = new Fake
      val tool = run(UTPCTool.load(Some(root), fake)).get
      fake.failure = Some(YallmoError.HttpFailure("UTCP endpoint returned HTTP 403"))
      val denied = run(invoke(tool, s"""{"manual":"weather","agrs":$valid}""").either).swap.toOption.get
      assert(denied.isInstanceOf[YallmoError.HttpFailure])
      assert(denied.getMessage.contains("HTTP 403"))
      assert(denied.getMessage.contains("egress approvals"))
      fake.failure = Some(YallmoError.HttpFailure("https://private.example/?token=DO_NOT_DISCLOSE"))
      val hidden = run(invoke(tool, s"""{"manual":"weather","agrs":$valid}""").either).swap.toOption.get
      assert(!hidden.getMessage.contains("DO_NOT_DISCLOSE"))
      assert(!hidden.getMessage.contains("private.example"))
      assertEquals(fake.calls.size, 2)
    }
  }

  test("long selections using the supplied weather pattern fail safely before provider execution") {
    withRoot { root =>
      val pattern =
        "^(rain|precipitation|temperature_2m|relative_humidity_2m|apparent_temperature|weather_code|wind_speed_10m|wind_direction_10m)(,(rain|precipitation|temperature_2m|relative_humidity_2m|apparent_temperature|weather_code|wind_speed_10m|wind_direction_10m))*$"
      val schema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("current" -> Json.Obj("type" -> Json.Str("string"), "pattern" -> Json.Str(pattern)))
      )
      write(root, "weather", manual.copy(tools = manual.tools.map(_.copy(inputs = schema))))
      val fake = new Fake
      val tool = run(UTPCTool.load(Some(root), fake)).get
      val arguments =
        Json.Obj("manual" -> Json.Str("weather"), "agrs" -> Json.Obj("current" -> Json.Str("rain" + ",rain" * 10000)))
      val error = run(invoke(tool, arguments.toJson).either).swap.toOption.get
      assert(error.getMessage.contains("character limit"))
      assertEquals(fake.calls.size, 0)
    }
  }

  private def write(root: Path, id: String, value: UTCPManual): Unit = {
    Files.createDirectories(root.resolve("utpc/manual"))
    Files.writeString(root.resolve(s"utpc/manual/$id.json"), value.toJson)
    ()
  }
  private def withRoot(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("utpc-gateway-").toRealPath()
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
