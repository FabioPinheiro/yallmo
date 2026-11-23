package fmgp.yallmo.skill

import fmgp.yallmo.model.{ResourceDiagnostic, YallmoError}
import zio.{IO, ZIO}

/** Server-owned, read-only snapshot of complete skills, immutable until restart. Loading a skill into this catalog does
  * not advertise or inject its instructions or sections into any agent context.
  */
trait SkillService {
  def catalog: Seq[Skill]
  def diagnostics: Seq[ResourceDiagnostic]

  /** Looks up the complete in-memory skill by its declared name, without filesystem I/O or LLM context injection. */
  def readSkill(name: String): IO[YallmoError, Skill]
}

object SkillService {
  val empty: SkillService = new SkillService {
    override val catalog: Seq[Skill] = Seq.empty
    override val diagnostics: Seq[ResourceDiagnostic] = Seq.empty
    override def readSkill(name: String): IO[YallmoError, Skill] = ZIO.fail(YallmoError.UnknownSkill(name))
  }
}
