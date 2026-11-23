package fmgp.yallmo.skill

import fmgp.yallmo.model.YallmoError
import munit.ZSuite
import zio.ZIO

final class SkillServiceSuite extends ZSuite {
  testZ("empty service has no catalog or diagnostics and fails every read with UnknownSkill") {
    val service = SkillService.empty
    for {
      results <- ZIO.foreach(Vector("grilling", "", "../grilling"))(name => service.readSkill(name).either)
    } yield {
      assertEquals(service.catalog, Vector.empty)
      assertEquals(service.diagnostics, Vector.empty)
      assertEquals(
        results,
        Vector("grilling", "", "../grilling").map(name => Left(YallmoError.UnknownSkill(name)))
      )
    }
  }
}
