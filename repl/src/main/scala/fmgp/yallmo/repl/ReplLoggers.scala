package fmgp.yallmo.repl

import fmgp.yallmo.orchestration.AgentRunner
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.UUID
import scala.util.Try
import zio.*

/** Terminal logs default to WARNING and obey `/log`; storage-root files independently default to INFO, routed by
  * AgentRunner.
  */
private[repl] object ReplLoggers {
  def console(write: String => Unit): ZLogger[String, Unit] =
    logger(line => _ => write(line))((level, context, _) =>
      level >= context.get(FiberRef.currentLogLevel).getOrElse(LogLevel.Warning)
    )

  def files(
      writeRepl: String => Unit,
      writeAgentRunner: (UUID, String) => Unit,
      minimumLevel: LogLevel = LogLevel.Info
  ): ZLogger[String, Unit] =
    logger { line => annotations =>
      annotations
        .get(AgentRunner.LogAnnotationKey)
        .flatMap(parseRunnerId)
        .fold(writeRepl(line))(writeAgentRunner(_, line))
    }((level, _, _) => level >= minimumLevel)

  def filesAt(universeRoot: Path, minimumLevel: LogLevel = LogLevel.Info): ZLogger[String, Unit] =
    files(
      append(replFilePath(universeRoot), _),
      (runnerId, line) => append(agentRunnerFilePath(universeRoot, runnerId), line),
      minimumLevel
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
