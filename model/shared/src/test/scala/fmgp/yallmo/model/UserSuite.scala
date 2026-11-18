package fmgp.yallmo.model

import munit.FunSuite
import zio.json.*

final class UserSuite extends FunSuite {
  private val hash =
    "pbkdf2-sha256$600000$5071be5d35e6a8a3274c320fd9630622$c9a2eec774d1b4f2a71879335dc71e7ebf655d762385791a69b407f34a4e92e1"

  test("User round-trips with mandatory id and secretHash") {
    val user = User(UserId("Alice"), hash)
    assertEquals(user.toJson, s"""{"id":"Alice","secretHash":"$hash"}""")
    assertEquals(user.toJson.fromJson[User], Right(user))
    assertEquals(user.toJsonAST.flatMap(_.as[User]), Right(user))
  }

  test("User rejects missing, null or non-string secretHash and removed plaintext fields") {
    val invalid = List(
      "{}",
      s"""{"secretHash":"$hash"}""",
      s"""{"id":" ","secretHash":"$hash"}""",
      """{"id":"Alice"}""",
      """{"id":"Alice","secretHash":null}""",
      """{"id":"Alice","secretHash":42}""",
      """{"secret":"test-secret"}""",
      s"""{"id":"Alice","secretHash":"$hash","secret":"test-secret"}""",
      s"""{"id":"Alice","secretHash":"$hash","secret":null}""",
      s"""{"id":"Alice","secretHash":"$hash","unexpected":true}"""
    )
    invalid.foreach { json =>
      assert(json.fromJson[User].isLeft)
      assert(json.fromJson[zio.json.ast.Json].toOption.get.as[User].isLeft)
    }
  }
}
