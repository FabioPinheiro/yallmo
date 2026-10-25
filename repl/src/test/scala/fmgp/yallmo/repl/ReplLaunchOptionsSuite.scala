package fmgp.yallmo.repl

import fmgp.yallmo.model.AgentId
import munit.FunSuite
import zio.{Chunk, LogLevel}

final class ReplLaunchOptionsSuite extends FunSuite {
  private val options = ReplLaunchOptions("operator")

  test("--user-name is required and identity and selection flags compose in either syntax") {
    assert(ReplLaunchOptions.parse(Chunk.empty).isLeft)
    assertEquals(ReplLaunchOptions.parse(Chunk("--user-name=operator")), Right(options))
    assertEquals(
      ReplLaunchOptions.parse(Chunk("--agent", "eve", "--transcriber-agent=voice", "--user-name", "operator")),
      Right(options.copy(agentId = Some(AgentId("eve")), transcriberAgentId = Some(AgentId("voice"))))
    )
  }

  test("--log-level accepts every terminal level in either syntax, case-insensitively") {
    assertEquals(options.logLevel, LogLevel.Warning)
    for (name, level) <- List(
        "trace" -> LogLevel.Trace,
        "debug" -> LogLevel.Debug,
        "info" -> LogLevel.Info,
        "warning" -> LogLevel.Warning,
        "error" -> LogLevel.Error,
        "fatal" -> LogLevel.Fatal,
        "off" -> LogLevel.None
      )
    do
      for flag <- List(Chunk(s"--log-level=$name"), Chunk("--log-level", name.toUpperCase(java.util.Locale.ROOT))) do
        assertEquals(
          ReplLaunchOptions.parse(Chunk("--user-name=operator", "--agent=bob") ++ flag),
          Right(
            options.copy(agentId = Some(AgentId("bob")), logLevel = level)
          )
        )
  }

  test("--log-level rejects invalid, missing and duplicate values") {
    for (args, reason) <- List(
        (
          Chunk("--log-level=private-value"),
          "Invalid --log-level value. Expected trace, debug, info, warning, error, fatal or off."
        ),
        (Chunk("--log-level"), "--log-level requires a value"),
        (Chunk("--log-level="), "--log-level requires a value"),
        (Chunk("--log-level", "--agent=bob"), "--log-level requires a value"),
        (Chunk("--log-level=debug", "--log-level=info"), "--log-level may appear only once")
      )
    do
      assertEquals(
        ReplLaunchOptions.parse(Chunk("--user-name=operator") ++ args),
        Left(s"$reason\n${ReplLaunchOptions.Usage}")
      )
  }

  test("--storage is rejected in both syntaxes without echoing the value") {
    for flag <- List(Chunk("--storage", "foo"), Chunk("--storage=foo")) do
      assertEquals(
        ReplLaunchOptions.parse(Chunk("--user-name=operator") ++ flag),
        Left(s"Unknown option '--storage'.\n${ReplLaunchOptions.Usage}")
      )
    assert(!ReplLaunchOptions.Usage.contains("--storage"))
  }

  test("removed UI flags are rejected rather than selecting a terminal client") {
    for mode <- List("auto", "plain", "line", "tui", "unknown") do
      for flag <- List(Chunk(s"--ui=$mode"), Chunk("--ui", mode)) do
        assertEquals(
          ReplLaunchOptions.parse(Chunk("--user-name=operator") ++ flag),
          Left(s"Unknown option '--ui'.\n${ReplLaunchOptions.Usage}")
        )
    assert(!ReplLaunchOptions.Usage.contains("--ui"))
  }

  test("unknown flags name the option without echoing its value and include valid usage") {
    for flag <- List(Chunk("--unknown=private-value"), Chunk("--unknown", "private-value"), Chunk("-x")) do {
      val option = if flag.head == "-x" then "-x" else "--unknown"
      assertEquals(
        ReplLaunchOptions.parse(Chunk("--user-name=operator") ++ flag),
        Left(s"Unknown option '$option'.\n${ReplLaunchOptions.Usage}")
      )
    }
  }

  test("missing values, duplicates and stray arguments explain the actual error") {
    for (args, reason) <- List(
        (Chunk.empty[String], "--user-name is required."),
        (Chunk("--user-name"), "--user-name requires a value"),
        (Chunk("--user-name", "--agent=bob"), "--user-name requires a value"),
        (Chunk("--user-name=a", "--agent="), "--agent requires a value"),
        (Chunk("--user-name=a", "--user-name=b"), "--user-name may appear only once"),
        (Chunk("--user-name=a", "stray"), "Unexpected argument 'stray'. Expected an option starting with --.")
      )
    do assertEquals(ReplLaunchOptions.parse(args), Left(s"$reason\n${ReplLaunchOptions.Usage}"))
  }

  test("removed provider/model flags and invalid, duplicate or missing arguments fail before loading files") {
    List(
      Chunk("--provider=oai"),
      Chunk("--model=old-model"),
      Chunk("--user-name"),
      Chunk("--user-name="),
      Chunk("--user-name", "--agent=eve"),
      Chunk("--user-name=a", "--user-name=b"),
      Chunk("--user-name=a", "--agent="),
      Chunk("--user-name=a", "--agent=bob", "--agent=eve"),
      Chunk("--user-name=a", "--ui=unknown"),
      Chunk("--user-name=a", "--storage="),
      Chunk("--user-name=a", "--unknown=value"),
      Chunk("--user-name=a", "stray")
    ).foreach(args => assert(ReplLaunchOptions.parse(args).isLeft, args.toString))
  }

  test("identity and both selections are validated; config is not a REPL option") {
    assertEquals(
      ReplLaunchOptions.parse(Chunk("--user-name=operator", "--agent=bob", "--transcriber-agent", "voice")),
      Right(options.copy(agentId = Some(AgentId("bob")), transcriberAgentId = Some(AgentId("voice"))))
    )
    for key <- List("--user-name", "--agent", "--transcriber-agent") do {
      val prefix = if key == "--user-name" then Chunk.empty else Chunk("--user-name=operator")
      for value <- List("", " ", "\t\n") do assert(ReplLaunchOptions.parse(prefix ++ Chunk(s"$key=$value")).isLeft)
      assert(ReplLaunchOptions.parse(prefix ++ Chunk(s"$key=a", s"$key=b")).isLeft)
      assert(ReplLaunchOptions.parse(prefix ++ Chunk(key)).isLeft)
    }
    assertEquals(
      ReplLaunchOptions.parse(Chunk("--user-name=operator", "--config=secret.json")),
      Left(s"Unknown option '--config'.\n${ReplLaunchOptions.Usage}")
    )
    assert(!ReplLaunchOptions.Usage.contains("--config"))
  }

  test("--server accepts explicit ws/wss endpoints in both syntaxes without changing local defaults") {
    for endpoint <- List("ws://127.0.0.1:8080/rpc", "wss://localhost:8443/rpc") do
      for flag <- List(Chunk("--server", endpoint), Chunk(s"--server=$endpoint")) do
        assertEquals(
          ReplLaunchOptions.parse(Chunk("--user-name=operator") ++ flag),
          Right(options.copy(server = Some(endpoint)))
        )
    assertEquals(options.server, None)
    assert(ReplLaunchOptions.parse(Chunk("--server=ws://localhost/rpc")).isLeft)
  }

  test("--server rejects invalid endpoints with a fixed error that never echoes credentials") {
    val reason = "Invalid --server endpoint. Expected ws/wss URI without credentials, query or fragment."
    for endpoint <- List(
        "https://localhost/rpc",
        "ws:///rpc",
        "not a uri",
        "ws://user:secret@localhost/rpc",
        "ws://localhost/rpc?user-name=secret",
        "ws://localhost/rpc#secret",
        "ws://localhost:99999/rpc",
        "ws://localhost:0/rpc"
      )
    do
      for flag <- List(Chunk("--server", endpoint), Chunk(s"--server=$endpoint")) do
        assertEquals(
          ReplLaunchOptions.parse(Chunk("--user-name=operator") ++ flag),
          Left(s"$reason\n${ReplLaunchOptions.Usage}")
        )
  }

  test("connected transcriber selection and config/storage are rejected before launch") {
    for arguments <- List(
        Chunk("--server=ws://localhost/rpc", "--transcriber-agent=voice"),
        Chunk("--transcriber-agent", "voice", "--server", "ws://localhost/rpc")
      )
    do
      assert(
        ReplLaunchOptions
          .parse(Chunk("--user-name=operator") ++ arguments)
          .left
          .toOption
          .get
          .contains("--transcriber-agent is unavailable")
      )
    for key <- List("--config", "--storage") do
      for flag <- List(Chunk(key, "secret"), Chunk(s"$key=secret")) do
        assertEquals(
          ReplLaunchOptions.parse(Chunk("--user-name=operator", "--server=ws://localhost/rpc") ++ flag),
          Left(s"Unknown option '$key'.\n${ReplLaunchOptions.Usage}")
        )
    assert(ReplLaunchOptions.parse(Chunk("--user-name=operator", "--server")).isLeft)
    assert(
      ReplLaunchOptions
        .parse(Chunk("--user-name=operator", "--server=ws://localhost/rpc", "--server=ws://localhost/rpc"))
        .isLeft
    )
  }

}
