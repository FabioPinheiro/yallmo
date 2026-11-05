package fmgp.yallmo.tool.sdlc

import zio.json.JsonCodec

/** Ordered, lightweight checklist item within a task, not an independently addressable subtask.
  *
  * Completing steps does not change the enclosing task's state. Storage validates a nonblank, single-line plain-text
  * title (at most 160 characters) and an optional Markdown description (at most 2000 characters).
  */
final case class PlanTaskStep(
    done: Boolean,
    title: String,
    description: String = ""
) derives JsonCodec {

  /** Renders a Markdown checkbox, escaping the plain-text title and nesting the description under the list item. */
  def toMarkdown: String = {
    val escapedTitle = title.flatMap { character =>
      if "\\`*_{}[]<>!|&".contains(character) then s"\\$character" else character.toString
    }
    val checkbox = s"- [${if done then "x" else " "}] $escapedTitle"
    if description.trim.isEmpty then checkbox
    else {
      val nested = description.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1).map("  " + _).mkString("\n")
      s"$checkbox\n\n$nested"
    }
  }
}
