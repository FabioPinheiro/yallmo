package fmgp.yallmo.orchestration

import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.model.MessagePayload.AudioAttachment
import fmgp.yallmo.model.{Agent, AgentId, AudioAgent, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason, ProviderGenerateOptions}
import fmgp.yallmo.orchestration.config.{AgentConfig, Config, OAuthCredentialsConfig, ProviderConfig, ProviderId}
import fmgp.yallmo.provider.oai.{OAIAgent, OAILlmProvider}
import fmgp.yallmo.provider.openai.{ChatGPTAgent, OpenAiCodexAuth, OpenAiCodexClient}
import munit.FunSuite
import zio.{Config as _, *}
import zio.http.*

import java.nio.charset.StandardCharsets
import java.util.Base64

final class RuntimeInstancesSuite extends FunSuite {
  private val oaiId = ProviderId.unsafe("local")
  private val chatId = ProviderId.unsafe("account-1")
  private val otherChatId = ProviderId.unsafe("account-2")
  private val bob = AgentId("bob")
  private val eve = AgentId("eve")
  private val charlie = AgentId("charlie")
  private val request = AgentTurnRequest(
    "request-model",
    Chunk(MessagePayload.User("Hello"), MessagePayload.Instructions("Be concise")),
    options = ProviderGenerateOptions(responseMimeType = None)
  )

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def fakeClient(respond: Request => Task[Response]): Client =
    Client.fromDriver(new ZClient.Driver[Any, Scope, Throwable] {
      override def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): Task[Response] = {
        val _ = (sslConfig, proxy)
        respond(Request(version = version, method = method, url = url, headers = headers, body = body))
      }

      override def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1]
      )(using Trace, Scope =:= Scope): ZIO[Env1, Throwable, Response] = {
        val _ = (version, url, headers, app)
        ZIO.dieMessage("Unexpected WebSocket request")
      }
    })

  private val noNetwork = fakeClient(_ => ZIO.dieMessage("Initialization must not send HTTP requests"))

  test("agent ID listing includes configured and supplied agents without initializing invalid definitions") {
    val supplied = new Agent[Any] {
      val id = AgentId("supplied")
      def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage("Listing must not execute agents")
    }
    val invalid = AgentId("uninitialized")
    val result = run(for {
      registry <- RuntimeInstances
        .make(
          config.copy(agents = config.agents.updated(invalid, AgentConfig("IntentionallyUnsupported"))),
          Chunk(supplied)
        )
        .provideEnvironment(ZEnvironment(noNetwork))
      before <- registry.listAgentIds
      lookup <- registry.getAgent(invalid).either
      after <- registry.listAgentIds
    } yield (before, lookup, after))
    assertEquals(result._1.map(AgentId.value), Chunk("bob", "charlie", "eve", "supplied", "uninitialized"))
    assert(result._2.isLeft)
    assertEquals(result._3, result._1)
  }

  test("user registration is concurrent, idempotent and visible without creating an executable agent") {
    val id = AgentId("remote-user")
    val current = runtime()
    run {
      for {
        before <- current.isUser(id)
        _ <- ZIO.foreachParDiscard(1 to 40)(_ => current.registerUser(id))
        _ <- ZIO.foreachParDiscard(1 to 20)(_ => current.validateAuthor(id))
        _ <- current.registerUser(id)
        known <- current.isUser(id)
        listed <- current.listAgentIds
        agent <- current.getAgent(id).either
        audio <- current.getAudioAgent(id).either
        unknown <- current.validateAuthor(AgentId("unknown")).either
      } yield {
        assert(!before)
        assert(known)
        assertEquals(listed.count(_ == id), 1)
        assertEquals(agent, Left(YallmoError.UnknownAgent(id)))
        assertEquals(audio, Left(YallmoError.UnknownAgent(id)))
        assertEquals(unknown, Left(YallmoError.UnknownAgent(AgentId("unknown"))))
      }
    }
  }

  test("user registration rejects configured and caller-supplied IDs without overwriting agents") {
    val supplied = new Agent[Any] {
      val id = AgentId("supplied")
      def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage("Collision validation must not execute supplied agents")
    }
    run {
      for {
        current <- RuntimeInstances.make(config, Chunk(supplied)).provideEnvironment(ZEnvironment(noNetwork))
        before <- current.getAgent(bob)
        collisions <- ZIO.foreachPar(Chunk(bob, eve, supplied.id))(current.registerUser(_).either)
        after <- current.getAgent(bob)
        preserved <- current.getAgent(supplied.id)
        kinds <- ZIO.foreach(Chunk(bob, eve, supplied.id, AgentId("unknown")))(current.isUser)
        _ <- current.registerUser(AgentId("new-user"))
        listed <- current.listAgentIds
      } yield {
        assert(collisions.forall(_.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig])))
        assert(before eq after)
        assert(preserved eq supplied)
        assert(kinds.forall(!_))
        assertEquals(listed.map(AgentId.value), Chunk("bob", "charlie", "eve", "new-user", "supplied"))
      }
    }
  }

  private def credentials(account: String, expiresAtMillis: Long = Long.MaxValue): OAuthCredentialsConfig = {
    val payload = Base64.getUrlEncoder.withoutPadding.encodeToString(
      s"""{"https://api.openai.com/auth":{"chatgpt_account_id":"$account"}}""".getBytes(StandardCharsets.UTF_8)
    )
    OAuthCredentialsConfig(s"header.$payload.signature", s"refresh-$account", expiresAtMillis)
  }

  private def config: Config = Config(
    agents = Map(
      bob -> AgentConfig("OAIAgent", Seq(oaiId)),
      eve -> AgentConfig("ChatGPTAgent", Seq(chatId)),
      charlie -> AgentConfig("ChatGPTAgent", Seq(chatId))
    ),
    providers = Map(
      oaiId -> ProviderConfig.OAI("http://localhost:12434/v1/"),
      chatId -> ProviderConfig.ChatGPT(Some(credentials("first"))),
      otherChatId -> ProviderConfig.ChatGPT(Some(credentials("second")))
    )
  )

  private def subscriptionResponse: Response = Response.text(
    "data: " + ujson.write(
      ujson.Obj(
        "type" -> "response.done",
        "response" -> ujson.Obj(
          "status" -> "completed",
          "output" -> ujson.Arr(
            ujson.Obj("type" -> "message", "content" -> ujson.Arr(ujson.Obj("type" -> "output_text", "text" -> "Hi")))
          )
        )
      )
    ) + "\n\n"
  )

  private def runtime(definition: Config = config, client: Client = noNetwork): RuntimeInstances[Any] =
    run(RuntimeInstances.make(definition).provideEnvironment(ZEnvironment(client)))

  test("both agents and providers initialize lazily and repeated effects reuse cached instances") {
    val definition = config.copy(providers =
      config.providers.updated(
        otherChatId,
        ProviderConfig.ChatGPT(endpoint = Some("https://untrusted.invalid"))
      )
    )
    val current = runtime(definition)
    val lookup = current.getAgent(bob)
    run {
      for {
        oai <- current.getProvider(oaiId)
        sameOai <- current.getProvider(oaiId)
        agent <- lookup
        sameAgent <- lookup
        chat <- current.getProvider(chatId)
        sameChat <- current.getProvider(chatId)
        chatAgent <- current.getAgent(eve)
        sameChatAgent <- current.getAgent(eve)
      } yield {
        assert(oai.isInstanceOf[OAILlmProvider])
        assert(oai eq sameOai)
        assert(agent.isInstanceOf[OAIAgent])
        assert(agent eq sameAgent)
        assert(chat.isInstanceOf[OpenAiCodexClient])
        assert(chat eq sameChat)
        assert(chatAgent.isInstanceOf[ChatGPTAgent])
        assert(chatAgent eq sameChatAgent)
        assertEquals(agent.id, bob)
        assertEquals(chatAgent.id, eve)
      }
    }
  }

  test("checked audio lookup is local, cached, typed, and rejects unsupported and unknown agents") {
    val current = runtime()
    run {
      for {
        audio <- current.getAudioAgent(bob)
        cached <- current.getAudioAgent(bob)
        ordinary <- current.getAgent(bob)
        unsupported <- current.getAudioAgent(eve).either
        unknown <- current.getAudioAgent(AgentId("missing")).either
        custom = new AudioAgent[String] {
          val id = AgentId("custom-audio")
          def turn(request: AgentTurnRequest): ZIO[String, YallmoError, AgentTurnResponse] =
            ZIO
              .service[String]
              .map(text => AgentTurnResponse(Chunk(MessagePayload.Assistant(text)), LlmStopReason.Stop))
        }
        registered <- RuntimeInstances.fromAgents(Chunk(custom))
        resolved <- registered.getAudioAgent(custom.id)
        result <- resolved.turn(request).provideEnvironment(ZEnvironment("from environment"))
      } yield {
        assert(audio eq cached)
        assert(audio eq ordinary)
        assert(
          unsupported.swap.toOption.exists(error =>
            error.isInstanceOf[YallmoError.InvalidConfig] && error.getMessage.contains("eve")
          )
        )
        assertEquals(unknown, Left(YallmoError.UnknownAgent(AgentId("missing"))))
        assert(resolved eq custom)
        assertEquals(result.output, Chunk(MessagePayload.Assistant("from environment")))
      }
    }
  }

  test("unsupported audio fails as a typed modality error before authentication or network access") {
    val current = runtime(config.copy(providers = Map(chatId -> ProviderConfig.ChatGPT())))
    val audioRequest = request.copy(input =
      Chunk(
        AudioAttachment(fmgp.yallmo.model.BlobId.fresh(), fmgp.yallmo.model.AudioFormat.Wav),
        MessagePayload.Instructions("Describe")
      )
    )
    val failure = run(current.getAgent(eve).flatMap(_.turn(audioRequest)).either)
    assert(
      failure.swap.toOption.exists(error =>
        error.isInstanceOf[YallmoError.InvalidConfig] && error.getMessage.contains("audio")
      )
    )
  }

  test("text decision agents pass an audio error marker to inference and still decode the response") {
    val codec = new AgentDecisionCodec {
      val instructions = "Decide"
      def decode(response: String, staticContext: StaticContext[?]): IO[YallmoError, AgentDecision] =
        ZIO.succeed(AgentDecision.FinalAnswer(response))
    }
    val client = fakeClient(incoming =>
      incoming.body.asString.flatMap(body =>
        ZIO.attempt {
          val wire = ujson.read(body)
          assertEquals(wire("messages")(0)("content").str, "System\n\nDecide")
          assertEquals(
            wire("messages")(1)("content").str,
            "SYSTEM ERROR: decision agents do not support audio input\n\nTask: Describe"
          )
          Response.text("""{"choices":[{"message":{"content":"Decision"},"finish_reason":"stop"}]}""")
        }
      )
    )
    val current = runtime(client = client)
    val turn = run(current.getProvider(oaiId).flatMap {
      case connection: OAILlmProvider =>
        LlmDecisionAgent(bob, codec, model = Some("decision-model"))
          .next(
            AgentTask("Describe"),
            StaticContext("System"),
            RunContext(Chunk(AudioAttachment(fmgp.yallmo.model.BlobId.fresh(), fmgp.yallmo.model.AudioFormat.Wav)))
          )
          .provideEnvironment(ZEnvironment[fmgp.yallmo.model.llm.LlmProvider](connection))
      case _ => ZIO.dieMessage("Expected OAI provider")
    })
    assertEquals(turn.assistant, Some(MessagePayload.Assistant("Decision")))
    assertEquals(turn.decision, AgentDecision.FinalAnswer("Decision"))
  }

  test("concurrent mixed lookups return one instance per ID without deadlocking") {
    val current = runtime()
    run {
      (for {
        lookups <- ZIO
          .foreachPar(1 to 40)(_ => current.getAgent(eve))
          .zipPar(ZIO.foreachPar(1 to 40)(_ => current.getAgent(charlie)))
          .zipPar(ZIO.foreachPar(1 to 40)(_ => current.getProvider(chatId)))
        direct <- current.getAgent(eve)
      } yield {
        val (firstAgents, otherAgents, providers) = lookups
        assert(firstAgents.forall(_ eq direct))
        assert(otherAgents.forall(_ eq otherAgents.head))
        assert(!(direct eq otherAgents.head))
        assert(providers.forall(_ eq providers.head))
      }).timeoutFail(new Exception("Lookup deadlock"))(5.seconds)
    }
  }

  test("two registries over the same immutable configuration have independent caches") {
    val definition = config
    val first = runtime(definition)
    val second = runtime(definition)
    run {
      for {
        initial <- first.getAgent(bob)
        independent <- second.getAgent(bob)
        provider <- first.getProvider(oaiId)
        otherProvider <- second.getProvider(oaiId)
      } yield {
        assert(!(initial eq independent))
        assert(!(provider eq otherProvider))
      }
    }
  }

  test("missing IDs, unsupported agents, provider cardinality and type mismatches return typed failures") {
    val current = runtime()
    val missing = ProviderId.unsafe("missing")
    val invalidAgents = Seq(
      AgentConfig("unknown", Seq(oaiId)),
      AgentConfig("OAIAgent"),
      AgentConfig("ChatGPTAgent"),
      AgentConfig("OAIAgent", Seq(oaiId, chatId)),
      AgentConfig("OAIAgent", Seq(oaiId, oaiId)),
      AgentConfig("OAIAgent", Seq(missing)),
      AgentConfig("OAIAgent", Seq(chatId)),
      AgentConfig("ChatGPTAgent", Seq(oaiId))
    )
    run {
      for {
        unknown <- current.getAgent(AgentId("unknown")).either
        unknownProvider <- current.getProvider(missing).either
        invalid <- ZIO.foreach(invalidAgents)(definition =>
          runtime(config.copy(agents = Map(bob -> definition))).getAgent(bob).either
        )
        valid <- current.getAgent(bob)
      } yield {
        assertEquals(unknown, Left(YallmoError.UnknownAgent(AgentId("unknown"))))
        assert(unknownProvider.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
        assert(invalid.forall(_.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig])))
        assertEquals(valid.id, bob)
      }
    }
  }

  test("provider map keys define independent cached instances even for identical definitions") {
    val definition = ProviderConfig.OAI("http://localhost")
    val current = runtime(config.copy(providers = Map(oaiId -> definition, chatId -> definition)))
    run {
      for {
        first <- current.getProvider(oaiId)
        second <- current.getProvider(chatId)
        sameFirst <- current.getProvider(oaiId)
        sameSecond <- current.getProvider(chatId)
      } yield {
        assert(!(first eq second))
        assert(first eq sameFirst)
        assert(second eq sameSecond)
      }
    }
  }

  test("unsafe/malformed endpoints fail locally without echoing secrets") {
    val badEndpoints = Seq(
      "",
      "file:///tmp/model",
      "http://",
      "http://user:secret@localhost/v1",
      "http://localhost/v1?key=secret",
      "http://localhost/v1#secret",
      "http://[secret"
    )
    run {
      for {
        invalid <- ZIO.foreach(badEndpoints)(endpoint =>
          runtime(config.copy(providers = Map(oaiId -> ProviderConfig.OAI(endpoint)))).getProvider(oaiId).either
        )
        untrusted <- runtime(
          config.copy(providers =
            Map(
              chatId -> ProviderConfig.ChatGPT(
                Some(credentials("first")),
                Some("https://untrusted.invalid/?secret")
              )
            )
          )
        ).getProvider(chatId).either
      } yield {
        assert(invalid.forall(_.isLeft))
        assert(invalid.flatMap(_.swap.toOption).forall(error => !error.getMessage.contains("secret")))
        assert(untrusted.isLeft)
        assert(!untrusted.swap.toOption.get.getMessage.contains("secret"))
      }
    }
  }

  test("OAIAgent uses the captured client and configured endpoint, forwarding request-level settings") {
    val client = fakeClient(incoming =>
      incoming.body.asString.flatMap { body =>
        ZIO.attempt {
          assertEquals(incoming.url.toString, "http://localhost:12434/v1/chat/completions")
          assertEquals(ujson.read(body)("model").str, request.model)
          assertEquals(ujson.read(body)("messages").arr.last("content").str, "Be concise")
          Response.text("""{"choices":[{"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}""")
        }
      }
    )
    val current = runtime(client = client)
    val result = run(current.getAgent(bob).flatMap(_.turn(request)))
    assertEquals(result.output, Chunk(MessagePayload.Assistant("Hi")))
  }

  test("configured OAI convenience generation requires an explicit model") {
    val current = runtime()
    val result = run {
      current.getProvider(oaiId).flatMap {
        case provider: OAILlmProvider => provider.inferenceWithText("Hello", ProviderGenerateOptions(), None).either
        case _                        => ZIO.dieMessage("Expected OAI provider")
      }
    }
    assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
  }

  test("logged-out ChatGPT initializes without credentials and fails only on inference") {
    val current = runtime(config.copy(providers = Map(chatId -> ProviderConfig.ChatGPT())))
    run {
      for {
        agent <- current.getAgent(eve)
        failure <- agent.turn(request).either
        same <- current.getAgent(eve)
      } yield {
        assertEquals(agent.id, eve)
        assert(agent eq same)
        assert(failure.swap.toOption.exists(_.isInstanceOf[YallmoError.AuthenticationFailure]))
      }
    }
  }

  test("ChatGPT profiles use independent configured tokens and preserve named agent identities") {
    val definition = config.copy(agents = config.agents.updated(charlie, AgentConfig("ChatGPTAgent", Seq(otherChatId))))
    run {
      for {
        seen <- Ref.make(Vector.empty[(String, String)])
        client = fakeClient(incoming =>
          incoming.body.asString.flatMap { body =>
            ZIO.attempt {
              assertEquals(incoming.url.toString, OpenAiCodexClient.CodexResponsesUrl)
              assertEquals(ujson.read(body)("model").str, request.model)
              assertEquals(ujson.read(body)("instructions").str, "Be concise")
            } *> seen
              .update(
                _ :+ (
                  incoming.headers.get("chatgpt-account-id").getOrElse(""),
                  incoming.headers.get("Authorization").getOrElse("")
                )
              )
              .as(subscriptionResponse)
          }
        )
        current <- RuntimeInstances.make(definition).provideEnvironment(ZEnvironment(client))
        agents <- current.getAgent(eve).zip(current.getAgent(charlie))
        _ <- agents._1.turn(request).zipPar(agents._2.turn(request))
        sent <- seen.get
      } yield {
        assertEquals(agents._1.id, eve)
        assertEquals(agents._2.id, charlie)
        assertEquals(
          sent.toSet,
          Set(
            "first" -> s"Bearer ${credentials("first").accessToken}",
            "second" -> s"Bearer ${credentials("second").accessToken}"
          )
        )
      }
    }
  }

  test("live login replaces shared cached agents without touching other profiles or in-flight requests") {
    run((for {
      started <- Promise.make[Nothing, Unit]
      release <- Promise.make[Nothing, Unit]
      seen <- Ref.make(Vector.empty[String])
      client = fakeClient(incoming => {
        val token = incoming.headers.get("Authorization").getOrElse("")
        seen.update(_ :+ token) *>
          (if token == s"Bearer ${credentials("first").accessToken}" then started.succeed(()) *> release.await
           else ZIO.unit)
            .as(subscriptionResponse)
      })
      current <- RuntimeInstances.make(config).provideEnvironment(ZEnvironment(client))
      _ <- current.registerUser(AgentId("human"))
      old <- current.getAgent(eve)
      oldSibling <- current.getAgent(charlie)
      local <- current.getAgent(bob)
      untouched <- current.getProvider(otherChatId)
      active <- old.turn(request).fork
      _ <- started.await
      _ <- current.updateChatGptCredentials(chatId, credentials("new-login"))
      fresh <- current.getAgent(eve)
      sibling <- current.getAgent(charlie)
      _ <- fresh.turn(request)
      _ <- sibling.turn(request)
      _ <- release.succeed(())
      oldResult <- active.join
      sent <- seen.get
      sameLocal <- current.getAgent(bob)
      sameOther <- current.getProvider(otherChatId)
      sameFresh <- current.getAgent(eve)
      user <- current.isUser(AgentId("human"))
    } yield {
      assert(!(fresh eq old))
      assert(!(sibling eq oldSibling))
      assert(sameFresh eq fresh)
      assert(sameLocal eq local)
      assert(sameOther eq untouched)
      assert(user)
      assertEquals(
        sent,
        Vector(
          s"Bearer ${credentials("first").accessToken}",
          s"Bearer ${credentials("new-login").accessToken}",
          s"Bearer ${credentials("new-login").accessToken}"
        )
      )
      assertEquals(oldResult.output, Chunk(MessagePayload.Assistant("Hi")))
    }).timeoutFail(new Exception("Live login deadlock"))(5.seconds))
  }

  test("live credentials also activate cold/logged-out profiles and invalid updates leave caches intact") {
    val current = runtime(config.copy(providers = config.providers.updated(chatId, ProviderConfig.ChatGPT())))
    run(for {
      loggedOut <- current.getAgent(eve)
      failure <- loggedOut.turn(request).either
      _ <- current.updateChatGptCredentials(chatId, credentials("logged-in"))
      first <- current.getProvider(chatId)
      agent <- current.getAgent(eve)
      invalid <- current.updateChatGptCredentials(chatId, credentials("bad").copy(accessToken = "bad token")).either
      wrong <- current.updateChatGptCredentials(oaiId, credentials("bad")).either
      missing <- current.updateChatGptCredentials(ProviderId.unsafe("unknown"), credentials("bad")).either
      after <- current.getProvider(chatId)
      same <- current.getAgent(eve)
    } yield {
      assert(failure.isLeft)
      assert(!(loggedOut eq agent))
      assert(List(invalid, wrong, missing).forall(_.isLeft))
      assert(after eq first)
      assert(same eq agent)
    })
  }

  test("a delayed old token refresh cannot overwrite a newer login generation") {
    run((for {
      refreshing <- Promise.make[Nothing, Unit]
      release <- Promise.make[Nothing, Unit]
      seen <- Ref.make(Vector.empty[String])
      client = fakeClient(incoming =>
        if incoming.url.toString == OpenAiCodexAuth.TokenUrl then
          (refreshing.succeed(()) *> release.await).as(
            Response.text(
              ujson.write(
                ujson.Obj(
                  "access_token" -> credentials("old-rotated").accessToken,
                  "refresh_token" -> "old-rotated-refresh",
                  "expires_in" -> 3600
                )
              )
            )
          )
        else seen.update(_ :+ incoming.headers.get("Authorization").getOrElse("")).as(subscriptionResponse)
      )
      current <- RuntimeInstances
        .make(
          config
            .copy(providers = config.providers.updated(chatId, ProviderConfig.ChatGPT(Some(credentials("first", 0L)))))
        )
        .provideEnvironment(ZEnvironment(client))
      old <- current.getAgent(eve)
      active <- old.turn(request).fork
      _ <- refreshing.await
      _ <- current.updateChatGptCredentials(chatId, credentials("new-login"))
      fresh <- current.getAgent(eve)
      _ <- fresh.turn(request)
      _ <- release.succeed(())
      _ <- active.join
      _ <- fresh.turn(request)
      sent <- seen.get
    } yield {
      assertEquals(
        sent,
        Vector(
          s"Bearer ${credentials("new-login").accessToken}",
          s"Bearer ${credentials("old-rotated").accessToken}",
          s"Bearer ${credentials("new-login").accessToken}"
        )
      )
    }).timeoutFail(new Exception("Login must not wait on an old refresh"))(5.seconds))
  }

  test("credential replacement preserves a dependent agent's already-rooted filesystem capability") {
    val root = java.nio.file.Files.createTempDirectory("runtime-login-fs-")
    try {
      val definition = config.copy(agents =
        config.agents.updated(
          eve,
          AgentConfig(
            "ChatGPTAgent",
            Seq(chatId),
            filesystem = Some(fmgp.yallmo.orchestration.config.FileSystemConfig(root.toString))
          )
        )
      )
      val current = runtime(definition)
      run(for {
        before <- current.getAgentWithFilesystem(eve)
        _ <- ZIO.attemptBlocking(java.nio.file.Files.delete(root))
        _ <- current.updateChatGptCredentials(chatId, credentials("new-login"))
        after <- current.getAgentWithFilesystem(eve)
      } yield {
        assert(!(before._1 eq after._1))
        assert(before._2.get eq after._2.get)
      })
    } finally java.nio.file.Files.deleteIfExists(root)
  }

  test("agents sharing a ChatGPT provider refresh once on first inference and reuse rotated credentials") {
    val definition =
      config.copy(providers = Map(chatId -> ProviderConfig.ChatGPT(Some(credentials("first", 0)))))
    val fresh = credentials("first")
    run {
      for {
        refreshes <- Ref.make(0)
        turns <- Ref.make(0)
        client = fakeClient(incoming =>
          if incoming.url.toString == OpenAiCodexAuth.TokenUrl then
            incoming.body.asString.flatMap { body =>
              ZIO.attempt {
                assert(body.contains("grant_type=refresh_token"))
                assert(body.contains("refresh_token=refresh-first"))
              } *> refreshes.update(_ + 1) *> ZIO.yieldNow.as(
                Response.text(
                  ujson.write(
                    ujson.Obj(
                      "access_token" -> fresh.accessToken,
                      "refresh_token" -> "rotated-refresh",
                      "expires_in" -> 3600
                    )
                  )
                )
              )
            }
          else
            ZIO.attempt {
              assertEquals(incoming.headers.get("Authorization"), Some(s"Bearer ${fresh.accessToken}"))
            } *> turns.update(_ + 1).as(subscriptionResponse)
        )
        current <- RuntimeInstances.make(definition).provideEnvironment(ZEnvironment(client))
        agents <- current.getAgent(eve).zipPar(current.getAgent(charlie))
        before <- refreshes.get
        _ <- ZIO.foreachPar(1 to 20)(i => (if i % 2 == 0 then agents._1 else agents._2).turn(request))
        _ <- agents._1.turn(request)
        after <- refreshes.get
        turnCount <- turns.get
      } yield {
        assertEquals(before, 0)
        assertEquals(after, 1)
        assertEquals(turnCount, 21)
      }
    }
  }

  test("already-created agents retain their identity and per-turn environment without an HTTP client") {
    val custom = new Agent[String] {
      override val id: AgentId = AgentId("custom")
      override def turn(request: AgentTurnRequest): ZIO[String, YallmoError, AgentTurnResponse] =
        ZIO.service[String].map(text => AgentTurnResponse(Chunk(MessagePayload.Assistant(text)), LlmStopReason.Stop))
    }
    run {
      for {
        current <- RuntimeInstances.fromAgents(Chunk(custom))
        resolved <- current.getAgent(custom.id)
        result <- resolved.turn(request).provideEnvironment(ZEnvironment("from environment"))
        mixed <- RuntimeInstances.make(config, Chunk(custom)).provideEnvironment(ZEnvironment(noNetwork))
        mixedCustom <- mixed.getAgent(custom.id)
        native <- mixed.getAgent(bob)
        mixedResult <- mixedCustom.turn(request).provideEnvironment(ZEnvironment("mixed environment"))
        unknown <- current.getAgent(bob).either
      } yield {
        assert(resolved eq custom)
        assertEquals(result.output, Chunk(MessagePayload.Assistant("from environment")))
        assert(mixedCustom eq custom)
        assertEquals(native.id, bob)
        assertEquals(mixedResult.output, Chunk(MessagePayload.Assistant("mixed environment")))
        assertEquals(unknown, Left(YallmoError.UnknownAgent(bob)))
      }
    }
  }

  test("duplicate already-created IDs and collisions with configured IDs are rejected") {
    val custom = new Agent[Any] {
      override val id: AgentId = bob
      override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage("Duplicate validation must not invoke agents")
    }
    run {
      for {
        duplicate <- RuntimeInstances.fromAgents(Chunk(custom, custom)).either
        collision <- RuntimeInstances.make(config, Chunk(custom)).provideEnvironment(ZEnvironment(noNetwork)).either
      } yield {
        assert(duplicate.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
        assert(collision.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
      }
    }
  }

  test("orchestrators share configured and already-created agents through the same registry") {
    val human = new Agent[Any] {
      override val id: AgentId = AgentId("human")
      override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.succeed(AgentTurnResponse(Chunk(MessagePayload.User("human input")), LlmStopReason.Stop))
    }
    run {
      for {
        current <- RuntimeInstances.make(config, Chunk(human)).provideEnvironment(ZEnvironment(noNetwork))
        first <- OrchestratorLive.inMemory(current)
        second <- OrchestratorLive.inMemory(current)
        assistant <- first.getAgent(eve)
        sameAssistant <- second.getAgent(eve)
        resolvedHuman <- first.getAgent(human.id)
        // Keep compatibility coverage for the deprecated single-turn API.
        humanTurn <- (second.turn(human.id, request): @scala.annotation.nowarn("cat=deprecation"))
        session <- first.createSession(SessionId.unsafe("mixed"), Chunk(human.id, eve))
        persisted <- first.append(session.id, eve, Chunk(MessagePayload.Assistant("saved")))
      } yield {
        assert(assistant eq sameAssistant)
        assert(resolvedHuman eq human)
        assertEquals(humanTurn.output, Chunk(MessagePayload.User("human input")))
        assertEquals(persisted.messages.map(_.agentId), Chunk(eve))
      }
    }
  }

  test("runtime request boundary strips caller-supplied tools and rejects forbidden provider calls") {
    import fmgp.yallmo.orchestration.config.AgentCapabilities
    val client = fakeClient(incoming =>
      incoming.body.asString.map { body =>
        val json = ujson.read(body)
        assert(json.obj.get("tools").forall(_.arr.isEmpty))
        assertEquals(json("tool_choice").str, "none")
        assertEquals(json("parallel_tool_calls").bool, false)
        Response.text(
          """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call","type":"function","function":{"name":"shell","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        )
      }
    )
    val c = config.copy(agents =
      Map(bob -> AgentConfig("OAIAgent", Seq(oaiId), capabilities = AgentCapabilities(tools = Some(Set.empty))))
    )
    val r = request.copy(
      tools = Chunk(
        fmgp.yallmo.model
          .FunctionToolDefinition(
            ToolKey.unsafe("shell"),
            "commands",
            fmgp.yallmo.model.llm.JsonSchema.ObjectSchema(Map.empty)
          )
      ),
      toolChoice = fmgp.yallmo.model.ToolChoice.Required,
      parallelToolCalls = true
    )
    val outcome = run(runtime(c, client).getAgent(bob).flatMap(_.turn(r)).either)
    assert(outcome.isLeft)
    assert(outcome.swap.toOption.get.getMessage.contains("capability policy"))
  }

}
