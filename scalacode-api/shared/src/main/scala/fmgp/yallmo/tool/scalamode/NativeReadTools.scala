package fmgp.yallmo.tool.scalamode

import zio.IO
import zio.json.*

/** Narrow RPC contract; outputs are the selected native tool's text, not decoded/reinterpreted filesystem values. */
trait NativeReadTools extends NativeReadFile with NativeListFiles

// Separate views ensure a partially permitted binding advertises and compiles only its selected methods.
trait NativeReadFile {
  def readFile(path: String): IO[ReadToolError, String]
}
trait NativeListFiles {
  def listFiles(path: String): IO[ReadToolError, String]
}

/** Preserves the native failure kind and message, but never serializes a host exception/cause or stack trace. */
final case class ReadToolError(kind: String, message: String) derives JsonCodec
