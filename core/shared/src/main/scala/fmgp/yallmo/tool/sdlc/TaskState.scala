package fmgp.yallmo.tool.sdlc

import zio.json.{JsonCodec, jsonHint}

/** Task lifecycle; archive visibility is independent of completion. */
enum TaskState derives JsonCodec {
  @jsonHint("open") case Open
  @jsonHint("doing") case Doing
  @jsonHint("done") case Done
  @jsonHint("dropped") case Dropped
}
