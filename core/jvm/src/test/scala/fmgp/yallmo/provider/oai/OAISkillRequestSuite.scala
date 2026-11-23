package fmgp.yallmo.provider.oai

import fmgp.yallmo.model.llm.AgentTurnRequest
import fmgp.yallmo.provider.SkillRequestProjectionSuite

final class OAISkillRequestSuite extends SkillRequestProjectionSuite {
  protected def wire(request: AgentTurnRequest): ujson.Obj =
    OAIChatCompletionsMapper.turnRequestBody(request.model, request)
}
