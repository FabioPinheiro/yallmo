package fmgp.yallmo.skill

/** A complete in-memory skill. Keeping it in the catalog does not load it into an LLM's context.
  *
  * @param instructions
  *   The body of SKILL.md, without YAML frontmatter.
  * @param disableModelInvocation
  *   Whether automatic model invocation should be disabled; explicit invocation can still use the skill.
  * @param sections
  *   Supporting Markdown contents keyed by file path relative to SKILL.md, for section-by-section context loading.
  */
final case class Skill(
    name: String,
    description: String,
    instructions: String,
    disableModelInvocation: Boolean = false,
    sections: Map[String, String] = Map.empty
) {

  /** Metadata-only XML advertisement; instructions and supporting sections never enter the catalog prompt. */
  def renderCatalogEntry: String =
    s"<skill>\n  <name>${Skill.escapeXml(name)}</name>\n  <description>${Skill.escapeXml(description)}</description>\n</skill>"
}

object Skill {

  /** Advertise visible skills in supplied order, without an empty wrapper when none can be advertised. */
  def renderCatalog(skills: Iterable[Skill]): String = {
    val entries = skills.iterator.filterNot(_.disableModelInvocation).map(_.renderCatalogEntry).toVector
    if entries.isEmpty then ""
    else
      entries
        .map(_.linesIterator.map("  " + _).mkString("\n"))
        .mkString("<available_skills>\n", "\n", "\n</available_skills>")
  }

  private def escapeXml(value: String): String = value.flatMap {
    case '&'  => "&amp;"
    case '<'  => "&lt;"
    case '>'  => "&gt;"
    case '"'  => "&quot;"
    case '\'' => "&apos;"
    case char => char.toString
  }
}
