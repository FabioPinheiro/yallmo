package fmgp.yallmo.webharness

import com.raquo.airstream.ownership.ManualOwner
import fmgp.yallmo.model.AgentId
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout
import zio.Chunk
import zio.json.ast.Json

final class SkillsModelSuite extends FunSuite {
  private val scalaSkill = SkillSummary("scala-developer", "Scala design and tests", false, 1, None, None)
  private val manualSkill = SkillSummary("manual", "Explicit Scala workflow", true, 0, Some("Author"), Some("1"))
  private def result(skill: SkillSummary, content: String = "Instructions") =
    SkillsRead.Result(skill, content, Chunk("references/intro.md"), Json.Obj())

  private final class Harness {
    val lists = mutable.ArrayBuffer.empty[Promise[SkillsList.Result]]
    val reads = mutable.ArrayBuffer.empty[(String, Option[String], Promise[SkillsRead.Result])]
    val availability = mutable.ArrayBuffer.empty[(String, Promise[SkillsAvailability.Result])]
    val agents = mutable.ArrayBuffer.empty[(AgentId, Promise[AgentsSkills.Result])]
    val model = new SkillsModel(
      () => { val p = Promise[SkillsList.Result](); lists += p; p.future },
      (name, section) => { val p = Promise[SkillsRead.Result](); reads += ((name, section, p)); p.future },
      name => { val p = Promise[SkillsAvailability.Result](); availability += ((name, p)); p.future },
      id => { val p = Promise[AgentsSkills.Result](); agents += ((id, p)); p.future }
    )
  }

  test("catalog reload is read-only and does not fetch any skill content or availability") {
    val h = new Harness
    assert(h.lists.isEmpty)
    h.model.reload()
    assertEquals(h.lists.size, 1)
    assert(h.reads.isEmpty)
    assert(h.availability.isEmpty)
    assert(h.agents.isEmpty)
    h.lists.head.success(SkillsList.Result(Chunk(scalaSkill, manualSkill), Chunk.empty))
    tick().map { _ =>
      assertEquals(h.model.catalog.state.now().value.map(_.skills.size), Some(2))
      assert(!h.model.catalog.state.now().loading)
    }
  }

  test("switching skills and documents discards out-of-order content replies") {
    val h = new Harness
    h.model.navigate(Some(scalaSkill.name -> None))
    h.model.navigate(Some(manualSkill.name -> None))
    h.reads.head._3.success(result(scalaSkill, "Old"))
    h.reads.last._3.success(result(manualSkill, "New"))
    tick().flatMap { _ =>
      assertEquals(h.model.detail.state.now().value.map(_.content), Some("New"))
      h.model.navigate(Some(manualSkill.name -> Some("references/intro.md")))
      assertEquals(h.model.tab.now(), SkillTab.Documents)
      assertEquals(h.reads.last._2, Some("references/intro.md"))
      h.reads.last._3.success(result(manualSkill, "Document"))
      tick().map { _ => assertEquals(h.model.detail.state.now().value.map(_.content), Some("Document")) }
    }
  }

  test("availability is lazy, cached for a selection and invalidated on skill change") {
    val h = new Harness
    h.model.navigate(Some(scalaSkill.name -> None))
    assert(h.availability.isEmpty)
    h.model.selectTab(SkillTab.Availability)
    h.model.selectTab(SkillTab.Availability)
    assertEquals(h.availability.size, 1)
    h.availability.head._2.success(SkillsAvailability.Result(Chunk.empty))
    tick().map { _ =>
      h.model.selectTab(SkillTab.Metadata)
      h.model.selectTab(SkillTab.Availability)
      assertEquals(h.availability.size, 1)
      h.model.navigate(Some(manualSkill.name -> None))
      assertEquals(h.model.availability.state.now().value, None)
      h.model.selectTab(SkillTab.Availability)
      assertEquals(h.availability.map(_._1).toList, List(scalaSkill.name, manualSkill.name))
    }
  }

  test("search, discovery and effective agent filters compose without fetching full content") {
    val h = new Harness
    val owner = new ManualOwner
    var filtered = List.empty[SkillSummary]
    h.model.filtered.foreach(entries => filtered = entries)(using owner)
    h.model.reload()
    h.lists.head.success(SkillsList.Result(Chunk(scalaSkill, manualSkill), Chunk.empty))
    tick().flatMap { _ =>
      assertEquals(filtered.size, 2)
      h.model.query.set("SCALA tests")
      assertEquals(filtered.map(_.name), List(scalaSkill.name))
      h.model.query.set("")
      h.model.discovery.set("manual")
      assertEquals(filtered.map(_.name), List(manualSkill.name))
      h.model.discovery.set("all")
      h.model.filterAgent("developer")
      assertEquals(filtered, Nil)
      h.agents.head._2.success(AgentsSkills.Result(Chunk(scalaSkill), true))
      tick().map { _ =>
        assertEquals(filtered.map(_.name), List(scalaSkill.name))
        assert(h.reads.isEmpty)
        owner.killSubscriptions()
      }
    }
  }

  test("disconnect invalidates reads but preserves navigation and search") {
    val h = new Harness
    h.model.query.set("Scala")
    h.model.navigate(Some(scalaSkill.name -> None))
    h.model.reload()
    h.model.suspend(clear = true)
    h.lists.head.success(SkillsList.Result(Chunk(scalaSkill), Chunk.empty))
    h.reads.foreach(_._3.success(result(scalaSkill)))
    tick().map { _ =>
      assertEquals(h.model.catalog.state.now().value, None)
      assertEquals(h.model.detail.state.now().value, None)
      assertEquals(h.model.selected.now(), Some(scalaSkill.name))
      assertEquals(h.model.query.now(), "Scala")
    }
  }

  test("returning to the catalog expands it and clears the selected document") {
    val h = new Harness
    h.model.collapsed.set(true)
    h.model.navigate(Some(scalaSkill.name -> Some("references/intro.md")))
    h.model.navigate(None)
    assert(!h.model.collapsed.now())
    assertEquals(h.model.selected.now(), None)
    assertEquals(h.model.document.now(), None)
    assertEquals(h.model.detail.state.now().value, None)
  }

  test("inspection errors are visible, never implicitly retried and explicit reload recovers") {
    val model = new InspectionModel[String]
    model.load(throw new RuntimeException("Disconnected"))
    tick().flatMap { _ =>
      assertEquals(model.state.now().error, Some("Disconnected"))
      assert(!model.state.now().loading)
      model.load(Future.successful("Recovered"))
      tick().map { _ =>
        assertEquals(model.state.now().value, Some("Recovered"))
        assertEquals(model.state.now().error, None)
      }
    }
  }

  test("skill and nested document links round-trip and malformed encodings are harmless") {
    val location = SkillLocation.href("scala-developer", Some("references/intro + examples?.md"))
    assertEquals(SkillLocation.parse(location), Some("scala-developer" -> Some("references/intro + examples?.md")))
    assertEquals(ViewRoute.parse(location), ViewRoute.Skills)
    assertEquals(SkillLocation.parse("#/skills"), None)
    assertEquals(SkillLocation.parse("#/skills/"), None)
    assertEquals(SkillLocation.parse("#/skills/%ZZ"), None)
  }

  test("relative Markdown links resolve only to loaded skill documents, including parent paths") {
    val sections = Set("references/intro.md", "checklists/review.md")
    assertEquals(
      SkillLocation.documentLink("scala-developer", None, sections, "references/intro.md#examples"),
      Some(SkillLocation.href("scala-developer", Some("references/intro.md")))
    )
    assertEquals(
      SkillLocation.documentLink("scala-developer", Some("references/intro.md"), sections, "../checklists/review.md"),
      Some(SkillLocation.href("scala-developer", Some("checklists/review.md")))
    )
    assertEquals(
      SkillLocation.documentLink("scala-developer", Some("references/intro.md"), sections, "../SKILL.md"),
      Some(SkillLocation.href("scala-developer"))
    )
    List(
      "../../outside.md",
      "https://example.com/intro.md",
      "//example.com/intro.md",
      "missing.md",
      "javascript:alert(1)"
    ).foreach { link =>
      assertEquals(SkillLocation.documentLink("scala-developer", None, sections, link), None)
    }
  }

  test("document reload retains navigation data without showing old content as a new result") {
    val model = new InspectionModel[String]
    model.load(Future.successful("Main"))
    tick().flatMap { _ =>
      val document = Promise[String]()
      model.load(document.future, retainValue = true)
      assertEquals(model.state.now().value, Some("Main"))
      assert(model.state.now().loading)
      document.failure(new RuntimeException("Missing document"))
      tick().map { _ =>
        assertEquals(model.state.now().value, Some("Main"))
        assertEquals(model.state.now().error, Some("Missing document"))
      }
    }
  }

  private def tick(): Future[Unit] = {
    val done = Promise[Unit]()
    setTimeout(0)(done.success(()))
    done.future
  }
}
