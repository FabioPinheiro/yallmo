package fmgp.yallmo.model

import munit.FunSuite
import zio.json.*

final class UserIdSuite extends FunSuite {
  test("users have distinct chat and website actors and a string codec") {
    val user = UserId("fabio")
    assertEquals(UserId.chatAgent(user), AgentId("user-fabio"))
    assertEquals(UserId.websiteAgent(user), AgentId("web-user-fabio"))
    assertEquals(user.toJson, "\"fabio\"")
    assertEquals(user.toJson.fromJson[UserId], Right(user))
    assertEquals(Map(user -> "owned").toJson.fromJson[Map[UserId, String]], Right(Map(user -> "owned")))
  }

  test("blank users are rejected at construction and JSON boundaries") {
    List("", " ", "\n\t").foreach { value =>
      assert(UserId.from(value).isLeft)
      assert(value.toJson.fromJson[UserId].isLeft)
      intercept[IllegalArgumentException](UserId(value))
    }
  }
}
