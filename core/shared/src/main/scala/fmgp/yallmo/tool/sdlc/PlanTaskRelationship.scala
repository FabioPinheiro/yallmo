package fmgp.yallmo.tool.sdlc

import zio.json.{JsonCodec, jsonHint}

/** Meaning of a task-to-task reference. Only dependencies and containment must be acyclic, each independently. */
enum PlanTaskRelationship derives JsonCodec {
  @jsonHint("subtask") case Subtask
  @jsonHint("dependsOn") case DependsOn
  @jsonHint("leftoverOf") case LeftoverOf
}
