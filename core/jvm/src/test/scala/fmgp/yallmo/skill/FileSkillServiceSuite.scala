package fmgp.yallmo.skill

import fmgp.yallmo.fs.FsError
import fmgp.yallmo.model.{ResourceDiagnostic, YallmoError}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import zio.*

final class FileSkillServiceSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withRoot(test: Path => Unit): Unit = {
    // Canonicalize the trusted temp root (e.g. /var is a symlink on macOS).
    val root = Files.createTempDirectory("file-skill-service-").toRealPath()
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def document(
      name: String,
      description: String = "Practical grilling advice",
      body: String = "# Grill\nUse heat."
  ): String =
    s"---\nname: $name\ndescription: $description\ndisable-model-invocation: true\n---\n$body\n"

  private def write(root: Path, directory: String, text: String): Path = {
    val folder = Files.createDirectories(root.resolve("skills").resolve(directory))
    Files.writeString(folder.resolve("SKILL.md"), text)
  }

  test("complete skills are cached until restart without further filesystem reads") {
    withRoot { root =>
      val file = write(root, "folder", document("grilling"))
      val service = run(FileSkillService.make(Some(root)))
      val original = Skill("grilling", "Practical grilling advice", "# Grill\nUse heat.\n", true)
      assertEquals(service.catalog, Vector(original))
      assertEquals(service.diagnostics, Vector.empty)
      assertEquals(run(service.readSkill("grilling")), original)
      Files.writeString(file, document("renamed", "Edited description", "# Fresh body"))
      assertEquals(service.catalog, Vector(original))
      assertEquals(run(service.readSkill("grilling")), original)
      assertEquals(run(service.readSkill("renamed").either), Left(YallmoError.UnknownSkill("renamed")))
      val restarted = run(FileSkillService.make(Some(root)))
      val updated = Skill("renamed", "Edited description", "# Fresh body\n", true)
      assertEquals(restarted.catalog, Vector(updated))
      assertEquals(run(restarted.readSkill("renamed")), updated)
      assertEquals(run(restarted.readSkill("grilling").either), Left(YallmoError.UnknownSkill("grilling")))
      Files.delete(file)
      assertEquals(run(service.readSkill("grilling")), original)
    }
  }

  test("supporting Markdown sections are cached by path relative to SKILL.md") {
    withRoot { root =>
      write(root, "grilling", document("grilling"))
      val references = Files.createDirectories(root.resolve("skills/grilling/references/nested"))
      val section = Files.writeString(references.resolve("temperature.md"), "# Heat\r\nUse a thermometer.\r\n")
      Files.writeString(root.resolve("skills/grilling/README.md"), "Overview")
      Files.write(root.resolve("skills/grilling/image.png"), Array(0xff.toByte))
      val service = run(FileSkillService.make(Some(root)))
      val expected =
        Map("references/nested/temperature.md" -> "# Heat\r\nUse a thermometer.\r\n", "README.md" -> "Overview")
      assertEquals(service.diagnostics, Vector.empty)
      assertEquals(service.catalog.head.sections, expected)
      Files.delete(section)
      assertEquals(run(service.readSkill("grilling")).sections, expected)
    }
  }

  test("section failures reject only the affected skill and never follow section symlinks") {
    withRoot { root =>
      write(root, "good", document("good"))
      write(root, "oversized", document("oversized"))
      Files.writeString(root.resolve("skills/oversized/section.md"), "x" * (FileSkillService.MaxFileBytes + 1))
      write(root, "invalid", document("invalid"))
      Files.write(root.resolve("skills/invalid/section.md"), Array(0xc3.toByte, 0x28.toByte))
      write(root, "linked", document("linked"))
      val target = Files.writeString(root.resolve("outside.md"), "Do not load")
      val references = Files.createDirectories(root.resolve("skills/linked/references"))
      Files.createSymbolicLink(references.resolve("section.md"), target)
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(service.catalog.map(_.name), Vector("good"))
      assertEquals(service.diagnostics.map(_.entry).toSet, Set("oversized", "invalid", "linked"))
      assert(service.diagnostics.forall(_.reason.startsWith("Cannot load sections:")))
    }
  }

  test("section traversal is bounded by entry count and nesting depth") {
    withRoot { root =>
      write(root, "many", document("many"))
      (1 to FileSkillService.MaxEntries).foreach { index =>
        Files.writeString(root.resolve(s"skills/many/section-$index.md"), "Section")
      }
      write(root, "deep", document("deep"))
      val nested = (1 to 33).foldLeft(root.resolve("skills/deep"))((path, _) => path.resolve("nested"))
      Files.createDirectories(nested)
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(service.catalog, Vector.empty)
      assertEquals(service.diagnostics.size, 2)
      assert(service.diagnostics.exists(_.reason.contains("exceeds 256 entries")))
      assert(service.diagnostics.exists(_.reason.contains("nesting exceeds 32")))
    }
  }

  test("body extraction preserves whitespace and handles an empty body") {
    withRoot { root =>
      val body = "\n  # Keep indentation\n\n---\nTrailing spaces  \n"
      write(root, "body", document("body", body = body).stripSuffix("\n"))
      write(root, "empty", "---\nname: empty\ndescription: No body\n---")
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(run(service.readSkill("body")).instructions, body)
      assertEquals(run(service.readSkill("empty")).instructions, "")
      assertEquals(service.diagnostics, Vector.empty)
    }
  }

  test("missing storage or skills directory yields an empty catalog without creating it") {
    withRoot { root =>
      for service <- List(run(FileSkillService.make(None)), run(FileSkillService.make(Some(root)))) do {
        assertEquals(service.catalog, Vector.empty)
        assertEquals(service.diagnostics, Vector.empty)
        assertEquals(run(service.readSkill("grilling").either), Left(YallmoError.UnknownSkill("grilling")))
      }
      assert(!Files.exists(root.resolve("skills")))
    }
  }

  test("metadata is validated safely and failures are diagnostic without suppressing valid neighbors") {
    withRoot { root =>
      val invalid = Vector(
        "# No frontmatter",
        "---\nname: missing-description\n---\nBody",
        "---\ndescription: Missing name\n---\nBody",
        document("../escape"),
        document("123"),
        document("bad", "false"),
        document("bad", "''"),
        "---\nname: [bad\ndescription: broken\n---\n",
        "---\nname: bad\nname: again\ndescription: duplicate\n---\n",
        "---\nname: !!java.lang.String bad\ndescription: unsafe\n---\n",
        "---\nname: &name bad\ndescription: *name\n---\n",
        document("bad").replace("true", "perhaps"),
        document("bad", "x" * 1025),
        document("a" * 65),
        "---\nname: deep\ndescription: nested\nextra: " + ("[" * 25) + ("a" + "]" * 25) + "\n---\n"
      )
      invalid.zipWithIndex.foreach { case (text, index) => write(root, s"bad-$index", text) }
      write(root, "good", document("valid"))
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(service.catalog.map(_.name), Vector("valid"))
      assertEquals(service.diagnostics.size, invalid.size)
      assert(service.diagnostics.forall(_.reason.startsWith("Invalid SKILL.md frontmatter")))
    }
  }

  test("standard YAML and CRLF preserve the body without frontmatter") {
    withRoot { root =>
      val raw =
        "---\r\nname: 'grilling'\r\ndescription: |\r\n  Grill safely.\r\n  Use a thermometer.\r\nlicense: MIT\r\n---\r\n# Instructions\r\n"
      write(root, "folder", raw)
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(
        service.catalog,
        Vector(Skill("grilling", "Grill safely.\nUse a thermometer.\n", "# Instructions\r\n"))
      )
      assertEquals(
        run(service.readSkill("grilling")),
        Skill("grilling", "Grill safely.\nUse a thermometer.\n", "# Instructions\r\n")
      )
      write(root, "on", document("on", "yes").replace("true", "True"))
      val rebuilt = run(FileSkillService.make(Some(root)))
      assertEquals(rebuilt.catalog.find(_.name == "on"), Some(Skill("on", "yes", "# Grill\nUse heat.\n", true)))
    }
  }

  test("first sorted directory wins duplicates; declared names resolve independently and catalog sorts by name") {
    withRoot { root =>
      write(root, "z-last", document("alpha", body = "loser"))
      write(root, "a-first", document("alpha", body = "winner"))
      write(root, "b-folder", document("zulu"))
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(service.catalog.map(_.name), Vector("alpha", "zulu"))
      assertEquals(run(service.readSkill("alpha")), Skill("alpha", "Practical grilling advice", "winner\n", true))
      assertEquals(service.diagnostics, Vector(ResourceDiagnostic("z-last", "Duplicate name 'alpha'; using 'a-first'")))
      assertEquals(run(service.readSkill("a-first").either), Left(YallmoError.UnknownSkill("a-first")))
    }
  }

  test("discovery is exactly one level; standalone files and nested categories are never discovered") {
    withRoot { root =>
      write(root, "category/nested", document("nested"))
      write(root, "direct", document("direct"))
      Files.writeString(root.resolve("skills/SKILL.md"), document("root-file"))
      Files.writeString(root.resolve("skills/standalone.md"), document("standalone"))
      Files.createDirectory(root.resolve("skills/empty"))
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(service.catalog.map(_.name), Vector("direct"))
      assertEquals(service.diagnostics.size, 4)
      for name <- List("unknown", "../direct", "direct/SKILL.md", root.toString, "", "direct/../direct") do
        assertEquals(run(service.readSkill(name).either), Left(YallmoError.UnknownSkill(name)))
    }
  }

  test("any immediate skills-directory symlink fails the whole discovery with a diagnostic") {
    withRoot { root =>
      write(root, "direct", document("direct"))
      val external = Files.createDirectory(root.resolve("external"))
      val externalFile = Files.writeString(external.resolve("SKILL.md"), document("external"))
      for target <- List(external, externalFile, Path.of("direct"), Path.of("missing")) do {
        val link = Files.createSymbolicLink(root.resolve("skills/link"), target)
        val service = run(FileSkillService.make(Some(root)))
        assertEquals(service.catalog, Vector.empty)
        assertEquals(
          service.diagnostics,
          Vector(
            ResourceDiagnostic(
              "skills",
              s"Cannot discover skills: ${FsError.InvalidPath(".", "Symbolic links are forbidden")}"
            )
          )
        )
        assertEquals(run(service.readSkill("direct").either), Left(YallmoError.UnknownSkill("direct")))
        Files.delete(link)
      }
    }
  }

  test("SKILL.md symlinks are rejected at startup; later replacements cannot affect cached skills") {
    withRoot { root =>
      val direct = write(root, "direct", document("direct"))
      val external = Files.createDirectory(root.resolve("external"))
      Files.writeString(external.resolve("SKILL.md"), document("external"))
      Files.createDirectory(root.resolve("skills/linked-file"))
      Files.createSymbolicLink(root.resolve("skills/linked-file/SKILL.md"), external.resolve("SKILL.md"))
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(service.catalog.map(_.name), Vector("direct"))
      assertEquals(
        service.diagnostics,
        Vector(
          ResourceDiagnostic(
            "linked-file",
            s"Cannot read SKILL.md: Skill file 'linked-file/SKILL.md' could not be read: ${FsError.InvalidPath("linked-file/SKILL.md", "Symbolic links are forbidden")}"
          )
        )
      )
      Files.delete(direct)
      Files.createSymbolicLink(direct, external.resolve("SKILL.md"))
      assertEquals(run(service.readSkill("direct")), service.catalog.head)
      val restarted = run(FileSkillService.make(Some(root)))
      assertEquals(restarted.catalog, Vector.empty)
      assertEquals(restarted.diagnostics.size, 2)
    }
  }

  test("a symlinked skills root is diagnosed and never scanned") {
    withRoot { root =>
      val external = Files.createDirectory(root.resolve("external"))
      Files.createSymbolicLink(root.resolve("skills"), external)
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(service.catalog, Vector.empty)
      assertEquals(service.diagnostics.size, 1)
    }
  }

  test("startup file and frontmatter reads are bounded and invalid UTF-8 is diagnosed") {
    withRoot { root =>
      val file = write(root, "good", document("good"))
      write(root, "too-large", document("large", body = "x" * FileSkillService.MaxFileBytes))
      write(
        root,
        "metadata-too-large",
        "---\n#" + "x" * FileSkillService.MaxMetadataChars + "\nname: large\ndescription: big\n---\n"
      )
      val badUtf8 = write(root, "invalid-utf8", document("bad"))
      Files.write(badUtf8, Array(0xc3.toByte, 0x28.toByte))
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(service.catalog.map(_.name), Vector("good"))
      assertEquals(service.diagnostics.size, 3)
      val cached = service.catalog.head
      val header = document("good", body = "")
      val exactLimit = document("good", body = "x" * (FileSkillService.MaxFileBytes - header.length))
      Files.writeString(file, exactLimit)
      val rebuilt = run(FileSkillService.make(Some(root)))
      assertEquals(rebuilt.catalog.head.instructions.length, FileSkillService.MaxFileBytes - header.length + 1)
      Files.writeString(file, exactLimit + "x")
      assertEquals(run(FileSkillService.make(Some(root))).catalog, Vector.empty)
      Files.write(file, Array(0xc3.toByte, 0x28.toByte))
      assertEquals(run(FileSkillService.make(Some(root))).catalog, Vector.empty)
      assertEquals(run(service.readSkill("good")), cached)
      assert(
        service.diagnostics.exists(diagnostic =>
          diagnostic.entry == "invalid-utf8" && diagnostic.reason.contains("Invalid UTF-8")
        )
      )
      assert(
        service.diagnostics.exists(diagnostic =>
          diagnostic.entry == "too-large" && diagnostic.reason.contains("size limit")
        )
      )
    }
  }

  test("directory overflow rejects the entire scan rather than selecting an unstable subset") {
    withRoot { root =>
      write(root, "good", document("good"))
      (1 to FileSkillService.MaxEntries).foreach(index => Files.createDirectory(root.resolve(s"skills/entry-$index")))
      val service = run(FileSkillService.make(Some(root)))
      assertEquals(service.catalog, Vector.empty)
      assertEquals(
        service.diagnostics,
        Vector(
          ResourceDiagnostic(
            "skills",
            s"Cannot discover skills: ${FsError.InvalidArgument("Directory exceeds 256 entries")}"
          )
        )
      )
      assertEquals(run(service.readSkill("good").either), Left(YallmoError.UnknownSkill("good")))
    }
  }
}
