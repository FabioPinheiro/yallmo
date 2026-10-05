package fmgp.yallmo.repl

import fmgp.yallmo.orchestration.AgentRunner
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.UUID
import scala.util.Try
import zio.*

/** Terminal output obeys `/log`; durable output is enabled only for a storage root and is routed by AgentRunner. */
private[repl] object ReplLoggers {
  def console(write: String => Unit): ZLogger[String, Unit] =
    logger(line => _ => write(line))((level, context, _) => level >= context.getOrDefault(FiberRef.currentLogLevel))

  def files(
      writeRepl: String => Unit,
      writeAgentRunner: (UUID, String) => Unit
  ): ZLogger[String, Unit] =
    logger { line => annotations =>
      annotations
        .get(AgentRunner.LogAnnotationKey)
        .flatMap(parseRunnerId)
        .fold(writeRepl(line))(writeAgentRunner(_, line))
    }((_, _, _) => true)

  def filesAt(universeRoot: Path): ZLogger[String, Unit] =
    files(
      append(replFilePath(universeRoot), _),
      (runnerId, line) => append(agentRunnerFilePath(universeRoot, runnerId), line)
    )

  def initialize(universeRoot: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(agentRunnerDirectory(universeRoot))
      Files.writeString(replFilePath(universeRoot), "", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }.ignore

  val console: ZLogger[String, Unit] = console(scala.Console.println)

  def replFilePath(universeRoot: Path): Path = universeRoot.resolve("logs/repl.log")

  def agentRunnerDirectory(universeRoot: Path): Path = universeRoot.resolve("logs/agent-runners")

  private def agentRunnerFilePath(universeRoot: Path, runnerId: UUID): Path =
    agentRunnerDirectory(universeRoot).resolve(s"$runnerId.log")

  private def logger(
      write: String => Map[String, String] => Unit
  )(enabled: (LogLevel, FiberRefs, Map[String, String]) => Boolean): ZLogger[String, Unit] =
    new ZLogger[String, Unit] {
      override def apply(
          trace: Trace,
          fiberId: FiberId,
          logLevel: LogLevel,
          message: () => String,
          cause: Cause[Any],
          context: FiberRefs,
          spans: List[LogSpan],
          annotations: Map[String, String]
      ): Unit =
        if enabled(logLevel, context, annotations) then
          write(ZLogger.default(trace, fiberId, logLevel, message, cause, context, spans, annotations))(annotations)
    }

  private def append(path: Path, line: String): Unit =
    try
      this.synchronized {
        Files.createDirectories(path.getParent)
        Files.writeString(path, s"$line\n", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      }
    catch case _: Exception => ()

  private def parseRunnerId(value: String): Option[UUID] = Try(UUID.fromString(value)).toOption
}
