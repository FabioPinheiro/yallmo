package fmgp.yallmo.repl

import java.nio.file.Paths
import fmgp.yallmo.model.AgentId
import fmgp.yallmo.model.llm.{AudioInput, AudioFormat}
import fmgp.yallmo.provider.openai.OpenAiCodexConfig
import fmgp.yallmo.repl.terminal.TerminalMode
import munit.FunSuite
import zio.*
import zio.http.Client

final class ReplProviderSuite extends FunSuite {
  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  test("startup defaults preserve OAI and auto UI") {
    assertEquals(ReplLaunchOptions.parse(Chunk.empty), Right(ReplLaunchOptions()))
    assertEquals(ReplLaunchOptions().provider, ReplProviderSelection.Oai)
  }

  test("provider, model, storage and UI options compose in either argument form") {
    val options = ReplLaunchOptions.parse(
      Chunk(
        "--provider=chatgpt",
        "--model",
        "available-model",
        "--storage",
        "./sessions",
        "--ui=line"
      )
    )
    assertEquals(
      options,
      Right(
        ReplLaunchOptions(
          TerminalMode.Line,
          Some(Paths.get("./sessions")),
          ReplProviderSelection.ChatGpt,
          Some("available-model")
        )
      )
    )
    assertEquals(
      ReplLaunchOptions.parse(Chunk("--provider", "openai-codex", "--ui", "plain")).map(_.provider),
      Right(ReplProviderSelection.ChatGpt)
    )
    assertEquals(
      ReplLaunchOptions.parse(Chunk("--storage=./sessions", "--ui=plain")).map(_.mode),
      Right(TerminalMode.Plain)
    )
    assertEquals(
      ReplLaunchOptions.parse(Chunk("--provider=oai", "--model=local-model")).map(_.model),
      Right(Some("local-model"))
    )
  }

  test("invalid, duplicate and missing options fail before constructing providers") {
    List(
      Chunk("--provider=api"),
      Chunk("--provider=chatgpt", "--provider=oai"),
      Chunk("--model=one", "--model", "two"),
      Chunk("--model= "),
      Chunk("--model"),
      Chunk("--model", "--ui=plain"),
      Chunk("--storage="),
      Chunk("--storage"),
      Chunk("--storage=a", "--storage=b"),
      Chunk("--ui=unknown"),
      Chunk("--ui=auto", "--ui=plain"),
      Chunk("--unknown=value"),
      Chunk("stray")
    ).foreach(arguments => assert(ReplLaunchOptions.parse(arguments).isLeft, arguments.toString))
  }

  test("subscription composition needs neither OAI generation configuration nor credentials at startup") {
    val options = ReplLaunchOptions(provider = ReplProviderSelection.ChatGpt, model = Some("available-model"))
    val selected = run(
      ZIO.scoped(
        ReplProvider.layer(options).build.map(_.get[ReplProvider]).provideSomeLayer[Scope](Client.default)
      )
    )
    assertEquals(AgentId.value(selected.agent.id), "openai-codex")
    assertEquals(selected.model, "available-model")
  }

  test("subscription retains the existing default model when no override is supplied") {
    val selected = run(
      ZIO.scoped(
        ReplProvider
          .layer(ReplLaunchOptions(provider = ReplProviderSelection.ChatGpt))
          .build
          .map(_.get[ReplProvider])
          .provideSomeLayer[Scope](Client.default)
      )
    )
    assertEquals(selected.model, OpenAiCodexConfig.Model)
  }

  test("unconfigured voice fails locally without using subscription or modifying the draft") {
    val error = run(ReplProvider.unavailableTranscriber.transcribe(AudioInput(Chunk.empty, AudioFormat.Wav)).either)
    assert(error.isLeft)
    assert(error.swap.toOption.get.getMessage.contains("MY_MODEL_URL"))
    assert(error.swap.toOption.get.getMessage.contains("text/tool turns only"))
  }
}
