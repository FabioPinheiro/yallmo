package fmgp.yallmo.model

import scala.annotation.targetName

extension (agentId: AgentId)
  @targetName("agentIdValue")
  def value: String = AgentId.value(agentId)

extension (functionCallId: FunctionCallId)
  @targetName("functionCallIdValue")
  def value: String = FunctionCallId.value(functionCallId)
