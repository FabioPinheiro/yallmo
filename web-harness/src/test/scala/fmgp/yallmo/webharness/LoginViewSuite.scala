package fmgp.yallmo.webharness

import munit.FunSuite

final class LoginViewSuite extends FunSuite {
  test("session restoration accepts only a nonempty server username") {
    assertEquals(LoginView.username("""{"username":"Fabio"}"""), Some("Fabio"))
    for body <- List("{}", "null", "not-json", """{"username":""}""", """{"username":42}""") do
      assertEquals(LoginView.username(body), None)
  }
}
