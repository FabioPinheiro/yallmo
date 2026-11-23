package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.llm.AgentTurnRequest
import fmgp.yallmo.provider.SkillRequestProjectionSuite

final class OpenAiCodexSkillRequestSuite extends SkillRequestProjectionSuite {
  protected def wire(request: AgentTurnRequest): ujson.Obj =
    OpenAiCodexTurnMapper.requestBody(request).toOption.get
}
