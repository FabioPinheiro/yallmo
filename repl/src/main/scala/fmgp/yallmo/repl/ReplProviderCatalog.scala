package fmgp.yallmo.repl

import fmgp.yallmo.orchestration.config.{Config, OAuthCredentialsConfig, ProviderConfig, ProviderId}
import fmgp.yallmo.provider.openai.OpenAiCodexClient
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.util.Try

/** File-backed provider management, independent of terminal input and runtime provider instances.
  *
  * Every operation reads the explicit file afresh. Writes are serialized within this service, not across factories or
  * processes: another writer racing between read and atomic replacement can still lose an update. Callers must
  * coordinate external writers. Existing files must be regular, non-symlink files in a trusted directory. Missing files
  * are not initialized here; startup owns initialization. No environment, network or authentication is used.
  */
final class ReplProviderCatalog private (path: Path, lock: Semaphore) {
  import ReplProviderCatalog.*

  /** All configured providers, sorted by their stored IDs, with credential-safe display values only. */
  def list: IO[Error, Vector[Entry]] =
    read.map(snapshot =>
      snapshot.config.providers.toVector.sortBy(pair => ProviderId.value(pair._1)).map { case (id, definition) =>
        summarize(id, definition)
      }
    )

  /** Loads displayable configuration, never the credential-bearing ProviderConfig or serialized config. */
  def load(id: ProviderId): IO[Error, Option[Entry]] =
    read.map(_.config.providers.get(id).map(summarize(id, _)))

  /** Adds just one provider entry to the latest JSON tree. Validation/duplicates never write anything.
    *
    * This effect is lazy: abandoning a UI form without running add has no side effects. Once started, the blocking save
    * completes atomically even if the calling fiber is interrupted. ChatGPT creation accepts no credentials or endpoint
    * override; its absent endpoint selects the adapter's trusted default.
    */
  def add(id: String, definition: Creation): IO[Error, Entry] =
    for {
      providerId <- ZIO.fromEither(ProviderId.from(id).left.map(_ => Error.InvalidId))
      provider <- ZIO.fromEither(toProvider(definition))
      entry <- lock.withPermit {
        for {
          snapshot <- read
          _ <- ZIO.fail(Error.DuplicateId).when(snapshot.config.providers.contains(providerId))
          providers = snapshot.json.fields
            .collectFirst { case ("providers", value: Json.Obj) => value }
            .getOrElse(Json.Obj())
          providerJson <- ZIO.fromEither(provider.toJsonAST.left.map(_ => Error.InvalidConfig))
          updatedProviders = Json.Obj(providers.fields :+ (id -> providerJson))
          fields = snapshot.json.fields.filterNot(_._1 == "providers") :+ ("providers" -> updatedProviders)
          _ <- save(Json.Obj(fields))
        } yield summarize(providerId, provider)
      }
    } yield entry

  /** Validates a login target without exposing its stored credentials or starting authentication. */
  def validateLoginTarget(id: ProviderId): IO[CredentialError, Entry] =
    for {
      snapshot <- read.mapError(CredentialError.Catalog(_))
      provider <- ZIO.fromEither(chatGptTarget(snapshot, id))
    } yield summarize(id, provider)

  /** Updates only credentials on an existing trusted ChatGPT profile in the latest tree.
    *
    * Uses the same lock and private atomic persistence as add; external writers still require coordination. A deleted,
    * converted or untrusted target is never recreated or given credentials. Callers must keep cancellation/timeouts
    * outside the commit phase so an atomic save cannot be reported as cancelled.
    */
  def updateChatGptCredentials(id: ProviderId, credentials: OAuthCredentialsConfig): IO[CredentialError, Entry] =
    for {
      stored <- ZIO.fromEither(validatedCredentials(credentials))
      entry <- lock.withPermit {
        for {
          snapshot <- read.mapError(CredentialError.Catalog(_))
          provider <- ZIO.fromEither(chatGptTarget(snapshot, id))
          credentialJson <- ZIO.fromEither(stored.toJsonAST.left.map(_ => CredentialError.InvalidCredentials))
          updated = Json.Obj(snapshot.json.fields.map {
            case ("providers", providers: Json.Obj) =>
              "providers" -> Json.Obj(providers.fields.map {
                case (key, value: Json.Obj) if key == ProviderId.value(id) =>
                  val fields = value.fields.map {
                    case ("credentials", _) => "credentials" -> credentialJson
                    case field              => field
                  }
                  key -> Json.Obj(
                    if fields.exists(_._1 == "credentials") then fields else fields :+ ("credentials" -> credentialJson)
                  )
                case field => field
              })
            case field => field
          })
          _ <- save(updated).mapError(CredentialError.Catalog(_))
        } yield summarize(id, provider.copy(credentials = Some(stored)))
      }
    } yield entry

  private def read: IO[Error, Snapshot] =
    for {
      text <- ZIO
        .attemptBlocking {
          requireSafeTarget()
          Files.readString(path, UTF_8)
        }
        .mapError(_ => Error.ReadFailed)
      snapshot <- ZIO.fromEither {
        for {
          json <- text.fromJson[Json].left.map(_ => Error.InvalidConfig)
          obj <- json match {
            case value: Json.Obj if uniqueKeys(value) => Right(value)
            case _                                    => Left(Error.InvalidConfig)
          }
          config <- text.fromJson[Config].left.map(_ => Error.InvalidConfig)
          _ <- Either.cond(
            config.userName.trim.nonEmpty && config.defaultAgentId.forall(config.agents.contains) &&
              config.agents.values.forall(_.providerIds.forall(config.providers.contains)),
            (),
            Error.InvalidConfig
          )
        } yield Snapshot(obj, config)
      }
    } yield snapshot

  private def requireSafeTarget(): Unit =
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      throw new IllegalArgumentException("Configuration must be a regular non-symlink file")

  private def save(json: Json.Obj): IO[Error, Unit] =
    ZIO
      .attemptBlocking {
        requireSafeTarget()
        // Some providers ignore initial attributes, so require POSIX support before creating the private temporary file.
        if !Files.getFileStore(path.getParent).supportsFileAttributeView("posix") then
          throw new UnsupportedOperationException("Private POSIX persistence is unavailable")
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val temporary = Files.createTempFile(path.getParent, ".yallmo-providers-", ".json", permissions)
        var published = false
        try {
          Files.writeString(temporary, json.toJsonPretty + "\n", UTF_8)
          requireSafeTarget()
          Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          published = true
        } finally if !published then Files.deleteIfExists(temporary)
      }
      .mapError(_ => Error.SaveFailed)
      .uninterruptible
}

object ReplProviderCatalog {

  /** Creation intentionally has no OAuth or secret fields, nor a ChatGPT endpoint override. */
  enum Creation {
    case OAI(endpoint: String)
    case ChatGPT
  }

  enum ProviderType {
    case OAI, ChatGPT
  }

  /** Presence only, not a claim that credentials are current or usable. */
  enum CredentialStatus {
    case NotApplicable, LoggedOut, Configured
  }

  /** Safe values for menus/details. id is for lookup; use displayId when rendering untrusted configured IDs. */
  final case class Entry(
      id: ProviderId,
      displayId: String,
      providerType: ProviderType,
      endpoint: String,
      credentials: CredentialStatus
  ) {
    override def toString: String = s"Entry($displayId,$providerType,$endpoint,$credentials)"
  }

  /** Fixed errors deliberately omit input values, decoder diagnostics, filesystem exceptions and config contents. */
  enum Error {
    case InvalidId, InvalidEndpoint, DuplicateId, InvalidConfig, ReadFailed, SaveFailed
  }

  /** Login/update errors are separate so existing creation-only callers keep their exhaustive error handling. */
  enum CredentialError {
    case MissingProvider, NotChatGPT, InvalidCredentials
    case Catalog(error: Error)
  }

  /** Captures only a path and a per-instance lock. Does not read/create the file. */
  def make(path: Path): UIO[ReplProviderCatalog] =
    Semaphore.make(1).map(new ReplProviderCatalog(path.toAbsolutePath.normalize, _))

  private final case class Snapshot(json: Json.Obj, config: Config)

  private def chatGptTarget(snapshot: Snapshot, id: ProviderId): Either[CredentialError, ProviderConfig.ChatGPT] =
    snapshot.config.providers.get(id) match {
      case None                                   => Left(CredentialError.MissingProvider)
      case Some(provider: ProviderConfig.ChatGPT) =>
        Either.cond(
          provider.endpoint.forall(_.trim.stripSuffix("/") == OpenAiCodexClient.CodexResponsesUrl),
          provider,
          CredentialError.Catalog(Error.InvalidEndpoint)
        )
      case Some(_) => Left(CredentialError.NotChatGPT)
    }

  private def validatedCredentials(value: OAuthCredentialsConfig): Either[CredentialError, OAuthCredentialsConfig] =
    Either.cond(
      List(value.accessToken, value.refreshToken).forall(token =>
        token.nonEmpty && !token.exists(char => char.isWhitespace || unsafeCharacter(char))
      ) && value.expiresAtMillis >= 0,
      value,
      CredentialError.InvalidCredentials
    )

  private def toProvider(creation: Creation): Either[Error, ProviderConfig] = creation match {
    case Creation.OAI(endpoint) =>
      Either.cond(httpUri(endpoint).isDefined, ProviderConfig.OAI(endpoint), Error.InvalidEndpoint)
    case Creation.ChatGPT => Right(ProviderConfig.ChatGPT())
  }

  private def summarize(id: ProviderId, definition: ProviderConfig): Entry = definition match {
    case ProviderConfig.OAI(endpoint) =>
      Entry(
        id,
        displayText(ProviderId.value(id)),
        ProviderType.OAI,
        displayEndpoint(endpoint),
        CredentialStatus.NotApplicable
      )
    case ProviderConfig.ChatGPT(credentials, endpoint) =>
      Entry(
        id,
        displayText(ProviderId.value(id)),
        ProviderType.ChatGPT,
        displayEndpoint(endpoint.getOrElse(OpenAiCodexClient.CodexResponsesUrl)),
        if credentials.isDefined then CredentialStatus.Configured else CredentialStatus.LoggedOut
      )
  }

  private def httpUri(endpoint: String): Option[URI] =
    Try(new URI(endpoint)).toOption.filter(uri =>
      Set("http", "https").contains(Option(uri.getScheme).getOrElse("").toLowerCase(java.util.Locale.ROOT)) &&
        Option(uri.getHost).exists(_.nonEmpty) && !endpoint.exists(unsafeCharacter)
    )

  private def displayEndpoint(endpoint: String): String =
    httpUri(endpoint).fold("<invalid endpoint>") { uri =>
      // Rebuild from parsed components: no URL userinfo, query, fragment, or decoder error can reach the terminal.
      val host = uri.getHost
      val port = if uri.getPort == -1 then "" else s":${uri.getPort}"
      displayText(s"${uri.getScheme}://$host$port${Option(uri.getRawPath).getOrElse("")}")
    }

  private def unsafeCharacter(char: Char): Boolean =
    Character.isISOControl(char) || Set(
      Character.FORMAT.toInt,
      Character.LINE_SEPARATOR.toInt,
      Character.PARAGRAPH_SEPARATOR.toInt
    )
      .contains(Character.getType(char))

  private def displayText(value: String): String = value.map(char => if unsafeCharacter(char) then '?' else char)

  private def uniqueKeys(json: Json): Boolean = json match {
    case Json.Obj(fields) => fields.map(_._1).distinct.size == fields.size && fields.forall(pair => uniqueKeys(pair._2))
    case Json.Arr(values) => values.forall(uniqueKeys)
    case _                => true
  }
}
