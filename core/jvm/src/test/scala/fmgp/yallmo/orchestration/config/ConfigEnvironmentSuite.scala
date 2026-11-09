package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.AgentId
import munit.FunSuite
import zio.{Runtime, Unsafe}
import zio.json.*
import zio.json.ast.Json

import java.nio.file.{Files, Path}

final class ConfigEnvironmentSuite extends FunSuite {
  private val id = ProviderId.unsafe("account")
  private val yaml = """# References remain private configuration inputs, never expanded on disk.
providers:
  account:
    providerType: ChatGPT
    credentials:
      accessToken: "${ACCESS_TOKEN}"
      refreshToken: '${REFRESH_TOKEN}'
      expiresAtMillis: 123
"""
  private val environment = Map("ACCESS_TOKEN" -> "synthetic-access", "REFRESH_TOKEN" -> "synthetic-refresh")

  private def parse(text: String, env: Map[String, String] = environment, name: String = "test.yaml") =
    ConfigDocument.parse(Path.of(name), text, env)

  test("YAML credential references resolve into the typed model, not the raw document") {
    val document = parse(yaml).toOption.get
    assertEquals(
      document.config.providers(id),
      ProviderConfig.ChatGPT(Some(OAuthCredentialsConfig("synthetic-access", "synthetic-refresh", 123)))
    )
    assert(document.json.toJson.contains("${ACCESS_TOKEN}"))
    assert(document.json.toJson.contains("${REFRESH_TOKEN}"))
    assert(!document.json.toJson.contains("synthetic-access"))
    val rendered = document.render(document.json).toOption.get
    assert(rendered.contains("${ACCESS_TOKEN}"))
    assert(!rendered.contains("synthetic-access"))
    assert(rendered.contains("# References remain"))
    assertEquals(parse(rendered).map(_.config), Right(document.config))
  }

  test("Sandbox server example decodes selected agents and only environment-provided proxy placeholders") {
    val path = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("docker/yallmo-sandbox/yallmo-workspace/yallmo.yaml"))
      .find(Files.isRegularFile(_))
      .getOrElse(fail("Public Sandbox example config not found from the test working directory"))
    val env = Map(
      "YALLMO_SBX_OPENAI_ACCESS_TOKEN" -> "oai-oat01-proxy-managed",
      "YALLMO_SBX_OPENAI_REFRESH_TOKEN" -> "oai-ort01-proxy-managed"
    )
    val document = ConfigDocument.parse(path, Files.readString(path), env).toOption.get
    assertEquals(document.config.agents.keySet, Set(AgentId("Transcriber"), AgentId("Gemma"), AgentId("sol")))
    assertEquals(document.config.providers.keySet, Set(ProviderId.unsafe("docker-mr"), ProviderId.unsafe("chatgpt-1")))
    assertEquals(
      document.config.providers(ProviderId.unsafe("docker-mr")),
      ProviderConfig.OAI("http://host.docker.internal:12434/engines/v1")
    )
    val credentials = document.config
      .providers(ProviderId.unsafe("chatgpt-1"))
      .asInstanceOf[ProviderConfig.ChatGPT]
      .credentials
      .get
    assert(fmgp.yallmo.provider.openai.OpenAiCodexAuth.isProxyManaged(credentials))
    assertEquals(credentials.expiresAtMillis, 0L)
    val rendered = document.render(document.json).toOption.get
    assert(rendered.contains("${YALLMO_SBX_OPENAI_ACCESS_TOKEN}"))
    assert(rendered.contains("${YALLMO_SBX_OPENAI_REFRESH_TOKEN}"))
    assert(!rendered.contains("oai-oat01-proxy-managed"))
  }

  test("JSON has identical reference semantics and preserves references when rendered") {
    val text =
      """{"providers":{"account":{"providerType":"ChatGPT","credentials":{"accessToken":"${ACCESS_TOKEN}","refreshToken":"${REFRESH_TOKEN}","expiresAtMillis":123}}}}"""
    val document = parse(text, name = "test.json").toOption.get
    assertEquals(document.config, parse(yaml).toOption.get.config)
    val rendered = document.render(document.json).toOption.get
    assert(rendered.contains("${ACCESS_TOKEN}"))
    assert(!rendered.contains("synthetic-access"))
  }

  test("values with YAML metacharacters, multiline text, dollar signs and quotes remain one string") {
    val secret = "synthetic: value\nproviders: [not, structure]\n\"quoted\"\\path$1"
    val document = parse(yaml, environment.updated("ACCESS_TOKEN", secret)).toOption.get
    val provider = document.config.providers(id).asInstanceOf[ProviderConfig.ChatGPT]
    assertEquals(provider.credentials.map(_.accessToken), Some(secret))
    assertEquals(document.config.providers.size, 1)
  }

  test("nested strings, arrays, adjacent references and embedded references resolve without changing keys") {
    val text = """agents:
  ${LITERAL_KEY}:
    agentType: OAIAgent
    config:
      model: "${MODEL}"
      systemPrompt: |-
        Hello ${WHO}.
        ${MODEL}${WHO}
      future: ["prefix/${WHO}/suffix", "${EMPTY}", true, 42]
"""
    val document = parse(text, Map("MODEL" -> "test-model", "WHO" -> "world", "EMPTY" -> "")).toOption.get
    val agent = document.config.agents(AgentId("${LITERAL_KEY}"))
    val values = agent.config.fields.toMap
    assertEquals(values("model"), Json.Str("test-model"))
    assertEquals(values("systemPrompt"), Json.Str("Hello world.\ntest-modelworld"))
    assertEquals(
      values("future"),
      Json.Arr(Json.Str("prefix/world/suffix"), Json.Str(""), Json.Bool(true), Json.Num(42))
    )
    assert(document.json.toJson.contains("${WHO}"))
  }

  test("escaping is single-pass and environment values are never recursively expanded") {
    val text = """agents:
  assistant:
    agentType: OAIAgent
    config:
      systemPrompt: "$${MISSING} / ${VALUE} / $HOME / $$"
"""
    val document = parse(text, Map("VALUE" -> "${ALSO_MISSING}")).toOption.get
    assertEquals(
      document.config.agents(AgentId("assistant")).config.fields.toMap.apply("systemPrompt"),
      Json.Str("${MISSING} / ${ALSO_MISSING} / $HOME / $$")
    )
    assert(document.render(document.json).toOption.get.contains("$${MISSING}"))
  }

  test("missing variables fail clearly without echoing config contents or resolved values") {
    val result = parse(yaml, Map("ACCESS_TOKEN" -> "synthetic-secret-do-not-print"))
    val error = result.swap.toOption.get.getMessage
    assert(error.contains("REFRESH_TOKEN"))
    assert(!error.contains("synthetic-secret"))
    assert(!error.contains("providers"))
  }

  test("numeric references use the existing typed decoder and validation with sanitized failures") {
    val error = parse("browserAudioPort: '${PORT}'", Map("PORT" -> "synthetic-bad-secret")).swap.toOption.get
    assert(!error.getMessage.contains("synthetic-bad-secret"))
    assertEquals(
      parse("browserAudioPort: '${PORT}'", Map("PORT" -> "8080")).map(_.config.browserAudioPort),
      Right(8080)
    )
    assert(parse("browserAudioPort: '${PORT}'", Map("PORT" -> "65536")).isLeft)
    val credentials = yaml.replace("expiresAtMillis: 123", "expiresAtMillis: '${EXPIRY}'")
    assertEquals(
      parse(credentials, environment.updated("EXPIRY", "123")).map(_.config),
      parse(yaml).map(_.config)
    )
  }

  test("loading from the real process environment never rewrites the input file") {
    val path = Files.createTempFile("environment-config-", ".yaml")
    val text = """agents:
  assistant:
    agentType: OAIAgent
    config: {systemPrompt: "${PATH}"}
"""
    try {
      Files.writeString(path, text)
      val config = Unsafe.unsafe { unsafe ?=>
        Runtime.default.unsafe.run(ConfigFile.loadOrInitialize(path)).getOrThrowFiberFailure()
      }
      assertEquals(
        config.agents(AgentId("assistant")).config.fields.toMap.apply("systemPrompt"),
        Json.Str(sys.env("PATH"))
      )
      assertEquals(Files.readString(path), text)
    } finally Files.deleteIfExists(path)
  }
}
