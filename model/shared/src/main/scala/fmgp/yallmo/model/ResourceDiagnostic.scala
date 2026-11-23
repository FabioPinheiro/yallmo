package fmgp.yallmo.model

/** Operator-facing discovery warning; never includes file contents. */
final case class ResourceDiagnostic(entry: String, reason: String)
