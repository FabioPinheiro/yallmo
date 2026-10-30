package fmgp.yallmo.fs

import zio.json.JsonCodec

/** Expected filesystem and JSONL failures. Public errors contain no unrestricted filesystem handles. */
enum FsError derives JsonCodec {
  case InvalidPath(path: String, reason: String)
  case NotFound(path: String)
  case AlreadyExists(path: String)
  case DirectoryNotEmpty(path: String)
  case WrongType(path: String, expected: String)
  case InvalidArgument(message: String)
  case InvalidJson(path: String, line: Long, message: String)
  case IoFailure(path: String, message: String)
}
