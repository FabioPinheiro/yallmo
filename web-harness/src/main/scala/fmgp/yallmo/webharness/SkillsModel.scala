package fmgp.yallmo.webharness

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.AgentId
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

private[webharness] final case class InspectionState[A](
    loading: Boolean = false,
    value: Option[A] = None,
    error: Option[String] = None
)

/** Explicit read-only requests. Invalidating a view or connection prevents late replies from publishing. */
private[webharness] final class InspectionModel[A](using ExecutionContext) {
  val state = Var(InspectionState[A]())
  private var revision = 0L

  def load(fetch: => Future[A], retainValue: Boolean = false): Unit = {
    revision += 1
    val request = revision
    val previous = if retainValue then state.now().value else None
    state.set(InspectionState(loading = true, value = previous))
    Try(fetch).fold(Future.failed, identity).onComplete {
      case Success(value) if request == revision => state.set(InspectionState(value = Some(value)))
      case Failure(error) if request == revision =>
        state.set(
          InspectionState(value = previous, error = Some(Option(error.getMessage).getOrElse("Inspection failed")))
        )
      case _ => ()
    }
  }

  def invalidate(clear: Boolean = true): Unit = {
    revision += 1
    state.update(s => if clear then InspectionState[A]() else s.copy(loading = false))
  }
}

private[webharness] enum SkillTab(val label: String) {
  case Instructions extends SkillTab("Instructions")
  case Documents extends SkillTab("Documents")
  case Metadata extends SkillTab("Metadata")
  case Availability extends SkillTab("Availability")
}

/** Page state survives navigation; content and availability are fetched only for the selected skill. */
private[webharness] final class SkillsModel(
    list: () => Future[SkillsList.Result],
    read: (String, Option[String]) => Future[SkillsRead.Result],
    available: String => Future[SkillsAvailability.Result],
    forAgent: AgentId => Future[AgentsSkills.Result]
)(using ExecutionContext) {
  val catalog = new InspectionModel[SkillsList.Result]
  val detail = new InspectionModel[SkillsRead.Result]
  val availability = new InspectionModel[SkillsAvailability.Result]
  val agentSkills = new InspectionModel[AgentsSkills.Result]
  val selected = Var(Option.empty[String])
  val document = Var(Option.empty[String])
  val tab = Var(SkillTab.Instructions)
  val query = Var("")
  val discovery = Var("all")
  val agent = Var(Option.empty[String])
  val page = Var(0)
  val collapsed = Var(false)
  val source = Var(false)
  val pageSize = 8

  val filtered: Signal[List[SkillSummary]] = catalog.state.signal
    .combineWith(query.signal, discovery.signal, agent.signal, agentSkills.state.signal)
    .map { case (state, search, policy, agentId, permitted) =>
      val words = search.trim.toLowerCase.split("\\s+").filter(_.nonEmpty)
      state.value.toList.flatMap(_.skills.toList).filter { skill =>
        words.forall(word => s"${skill.name} ${skill.description}".toLowerCase.contains(word)) &&
        (policy == "all" || (policy == "automatic") == !skill.disableModelInvocation) &&
        (agentId.isEmpty || permitted.value.exists(_.skills.exists(_.name == skill.name)))
      }
    }

  def reload(): Unit = {
    page.set(0)
    catalog.load(list())
    selected.now().foreach(name => detail.load(read(name, document.now()), retainValue = true))
    if tab.now() == SkillTab.Availability then loadAvailability()
    agent.now().foreach(id => agentSkills.load(forAgent(AgentId(id))))
  }

  def navigate(selection: Option[(String, Option[String])]): Unit = selection match {
    case None =>
      collapsed.set(false)
      selected.set(None)
      document.set(None)
      detail.invalidate()
      availability.invalidate()
    case Some((name, section)) =>
      val changed = !selected.now().contains(name)
      val documentChanged = document.now() != section
      if changed || documentChanged || detail.state.now().value.isEmpty then {
        selected.set(Some(name))
        document.set(section)
        if changed then {
          availability.invalidate()
          source.set(false)
        }
        if changed || documentChanged then
          tab.set(if section.nonEmpty then SkillTab.Documents else SkillTab.Instructions)
        detail.load(read(name, section), retainValue = !changed)
      }
  }

  def selectTab(value: SkillTab): Unit = {
    tab.set(value)
    if value == SkillTab.Availability && availability.state.now().value.isEmpty && !availability.state.now().loading
    then loadAvailability()
  }

  def loadAvailability(): Unit = selected.now().foreach(name => availability.load(available(name)))

  def filterAgent(id: String): Unit = {
    agent.set(Option(id).filter(_.nonEmpty))
    page.set(0)
    agentSkills.invalidate()
    agent.now().foreach(id => agentSkills.load(forAgent(AgentId(id))))
  }

  def suspend(clear: Boolean = false): Unit = {
    catalog.invalidate(clear)
    detail.invalidate(clear)
    availability.invalidate(clear)
    agentSkills.invalidate(clear)
  }
}

/** URI components keep nested document paths unambiguous and links round-trippable. */
private[webharness] object SkillLocation {
  import scala.scalajs.js.URIUtils.{decodeURIComponent, encodeURIComponent}

  def href(name: String, section: Option[String] = None): String =
    s"#/skills/${encodeURIComponent(name)}" + section.fold("")(path => s"?document=${encodeURIComponent(path)}")

  /** Resolve only known catalog documents; external URLs and paths escaping the skill remain untouched. */
  def documentLink(name: String, current: Option[String], sections: Set[String], link: String): Option[String] = {
    val path = link.takeWhile(char => char != '#' && char != '?')
    if path.isEmpty || path.startsWith("/") || path.contains(":") || path.contains("\\\\") then None
    else
      Try(decodeURIComponent(path)).toOption.flatMap { decoded =>
        val base = current.toVector.flatMap(_.split("/").dropRight(1))
        val normalized = decoded
          .split("/")
          .foldLeft(Option(base)) { (parts, part) =>
            parts.flatMap { existing =>
              part match {
                case "" | "." => Some(existing)
                case ".."     => Option.when(existing.nonEmpty)(existing.dropRight(1))
                case segment  => Some(existing :+ segment)
              }
            }
          }
          .map(_.mkString("/"))
        normalized.flatMap {
          case "SKILL.md"                              => Some(href(name))
          case document if sections.contains(document) => Some(href(name, Some(document)))
          case _                                       => None
        }
      }
  }

  def parse(hash: String): Option[(String, Option[String])] = {
    if !hash.startsWith("#/skills/") then None
    else
      Try {
        val parts = hash.stripPrefix("#/skills/").split("\\?", 2)
        val name = decodeURIComponent(parts(0))
        require(name.nonEmpty, "Missing skill name")
        val section = parts.lift(1).filter(_.startsWith("document=")).map(value => decodeURIComponent(value.drop(9)))
        name -> section
      }.toOption
  }
}
