package fmgp.yallmo.repl

import fmgp.yallmo.model.AgentId
import java.nio.file.Paths
import munit.FunSuite
import zio.Chunk

final class ReplLaunchOptionsSuite extends FunSuite {
  private val options = ReplLaunchOptions(Paths.get("yallmo.json"))

  test("--config is required and config, agent and storage flags compose in either syntax") {
    assert(ReplLaunchOptions.parse(Chunk.empty).isLeft)
    assertEquals(ReplLaunchOptions.parse(Chunk("--config=yallmo.json")), Right(options))
    assertEquals(
      ReplLaunchOptions.parse(Chunk("--agent", "eve", "--storage=./sessions", "--config", "yallmo.json")),
      Right(options.copy(agentId = Some(AgentId("eve")), storageRoot = Some(Paths.get("./sessions"))))
    )
  }

  test("removed UI flags are rejected rather than selecting a terminal client") {
    for mode <- List("auto", "plain", "line", "tui", "unknown") do
      for flag <- List(Chunk(s"--ui=$mode"), Chunk("--ui", mode)) do
        assertEquals(
          ReplLaunchOptions.parse(Chunk("--config=yallmo.json") ++ flag),
          Left(s"Unknown option '--ui'.\n${ReplLaunchOptions.Usage}")
        )
    assert(!ReplLaunchOptions.Usage.contains("--ui"))
  }

  test("unknown flags name the option without echoing its value and include valid usage") {
    for flag <- List(Chunk("--unknown=private-value"), Chunk("--unknown", "private-value"), Chunk("-x")) do {
      val option = if flag.head == "-x" then "-x" else "--unknown"
      assertEquals(
        ReplLaunchOptions.parse(Chunk("--config=yallmo.json") ++ flag),
        Left(s"Unknown option '$option'.\n${ReplLaunchOptions.Usage}")
      )
    }
  }

  test("missing values, duplicates and stray arguments explain the actual error") {
    for (args, reason) <- List(
        (Chunk.empty[String], "--config is required."),
        (Chunk("--config"), "--config requires a value"),
        (Chunk("--config", "--agent=bob"), "--config requires a value"),
        (Chunk("--config=a", "--agent="), "--agent requires a value"),
        (Chunk("--config=a", "--storage"), "--storage requires a value"),
        (Chunk("--config=a", "--config=b"), "--config may appear only once"),
        (Chunk("--config=a", "stray"), "Unexpected argument 'stray'. Expected an option starting with --.")
      )
    do assertEquals(ReplLaunchOptions.parse(args), Left(s"$reason\n${ReplLaunchOptions.Usage}"))
  }

  test("removed provider/model flags and invalid, duplicate or missing arguments fail before loading files") {
    List(
      Chunk("--provider=oai"),
      Chunk("--model=old-model"),
      Chunk("--config"),
      Chunk("--config="),
      Chunk("--config", "--agent=eve"),
      Chunk("--config=a", "--config=b"),
      Chunk("--config=a", "--agent="),
      Chunk("--config=a", "--agent=bob", "--agent=eve"),
      Chunk("--config=a", "--ui=unknown"),
      Chunk("--config=a", "--storage="),
      Chunk("--config=a", "--unknown=value"),
      Chunk("--config=a", "stray")
    ).foreach(args => assert(ReplLaunchOptions.parse(args).isLeft, args.toString))
  }

}
