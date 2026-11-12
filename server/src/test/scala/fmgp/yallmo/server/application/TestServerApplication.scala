package fmgp.yallmo.server.application

import fmgp.yallmo.model.{Agent, AgentId, YallmoError}
import fmgp.yallmo.model.llm.ProviderContinuation
import fmgp.yallmo.orchestration.SessionId
import fmgp.yallmo.orchestration.config.Config
import fmgp.yallmo.provider.openai.OpenAiCodexAuth
import java.nio.file.Path
import scala.collection.immutable.VectorMap
import zio.{Config as _, *}
import zio.http.Client

/** Configured in-memory (or temporary filesystem) backend for transport tests. */
private[server] object TestServerApplication {
  def make(
      agents: Chunk[Agent[Any]] = Chunk.empty,
      storageRoot: Option[Path] = None
  ): ZIO[Scope, YallmoError, ServerExecutionService] =
    for {
      client <- Client.default.build.orDie
      application <- ServerApplication.make(Config(), agents, storageRoot).provideEnvironment(client)
      scope <- ZIO.service[Scope]
      inFlight <- Ref.make(Set.empty[SessionId])
      continuations <- Ref.make(Map.empty[SessionId, (AgentId, Set[ProviderContinuation])])
      captures <- Ref.make(VectorMap.empty[SessionId, Path])
      catalog <- ProviderCatalog.make(Path.of("unused-provider-config.json"))
      providers <- ProviderAdministration.make(catalog, OpenAiCodexAuth.oauthFlow(client.get[Client]))
    } yield new ServerExecutionService(
      application,
      Path.of("."),
      providers,
      scope,
      inFlight,
      continuations,
      captures
    )
}
