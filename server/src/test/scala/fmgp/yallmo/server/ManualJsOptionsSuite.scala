package fmgp.yallmo.server

import fmgp.yallmo.server.application.ManualJsScalamode
import java.nio.file.Path
import munit.FunSuite
import zio.*

final class ManualJsOptionsSuite extends FunSuite {
  test("server launch requires only durable storage and config, with no JS path options") {
    val defaults = MainServer.parse(List("--storage", ".", "--config", "test.json")).toOption.get
    assertEquals(defaults.storage, Path.of("."))
    assertEquals(defaults.config, Path.of("test.json"))
  }

  test("server rejects removed JS root and Node executable options in spaced and equals forms") {
    for
      flag <- List("--scalamode-js-root", "--scalamode-node")
      option <- List(List(flag, "/trusted/path"), List(s"$flag=/trusted/path"), List(flag))
    do {
      val result = MainServer.parse(List("--storage", ".", "--config", "test.json") ++ option)
      assert(result.swap.toOption.exists(_.startsWith("Unknown server option\n")))
      assert(!result.swap.toOption.get.split("\n").last.contains("scalamode"))
    }
  }

  test("unprovisioned trusted artifacts are unavailable with sanitized reason") {
    val result = Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(
          ZIO.scoped(ManualJsScalamode.provision(Path.of("/private/missing-artifacts")))
        )
        .getOrThrowFiberFailure()
    }
    assertEquals(result.executor, None)
    assert(!result.capabilities.jsNodeAvailable)
    assert(!result.capabilities.reason.contains("/private"))
    assert(!ManualJsScalamode.disabled.capabilities.jsNodeAvailable)
  }
}
