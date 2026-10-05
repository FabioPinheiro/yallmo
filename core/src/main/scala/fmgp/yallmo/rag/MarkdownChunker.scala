package fmgp.yallmo.rag

import zio.Chunk

/** Deterministically splits Markdown-like text at paragraph boundaries, with a bounded character overlap. */
object MarkdownChunker:
  def chunk(source: RagSource, config: ChunkingConfig): Chunk[RagChunk] =
    val paragraphs = source.text
      .replace("\r\n", "\n")
      .split("\\n\\s*\\n")
      .toIndexedSeq
      .flatMap(paragraph => splitLongParagraph(paragraph.trim, config.maxCharacters - config.overlapCharacters))
      .filter(_.nonEmpty)

    val texts = paragraphs.foldLeft(Vector.empty[String]) { (chunks, paragraph) =>
      chunks.lastOption match
        case Some(previous) if previous.length + 2 + paragraph.length <= config.maxCharacters =>
          chunks.updated(chunks.length - 1, s"$previous\n\n$paragraph")
        case Some(previous) => chunks :+ s"${overlap(previous, config.overlapCharacters)}$paragraph"
        case None           => Vector(paragraph)
    }

    Chunk.fromIterable(texts.zipWithIndex.map { case (text, index) =>
      RagChunk(s"${source.id}#${index + 1}", source.id, text, source.attributes)
    })

  private def splitLongParagraph(paragraph: String, maxCharacters: Int): IndexedSeq[String] =
    if paragraph.length <= maxCharacters then IndexedSeq(paragraph)
    else
      paragraph
        .split("(?<=\\s)")
        .foldLeft(Vector.empty[String]) { (parts, word) =>
          parts.lastOption match
            case Some(current) if current.length + word.length <= maxCharacters =>
              parts.updated(parts.length - 1, current + word)
            case _ if word.length > maxCharacters => parts ++ word.grouped(maxCharacters)
            case _                                => parts :+ word
        }
        .map(_.trim)

  private def overlap(text: String, overlapCharacters: Int): String =
    if overlapCharacters == 0 then ""
    else
      val start = math.max(0, text.length - overlapCharacters)
      val raw = text.substring(start)
      val boundary = raw.indexWhere(_.isWhitespace)
      val suffix = if boundary >= 0 then raw.substring(boundary + 1) else raw
      if suffix.isEmpty then "" else s"$suffix\n\n"
