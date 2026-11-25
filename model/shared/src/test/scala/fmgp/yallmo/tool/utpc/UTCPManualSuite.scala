package fmgp.yallmo.tool.utpc

import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

final class UTCPManualSuite extends FunSuite {
  private val fixture = """{
    "utcp_version":"1.0.0", "manual_version":"1.1.0", "x-owner":"demo",
    "tools":[{
      "name":"get_weather", "description":"Current weather",
      "inputs":{"type":"object","properties":{"location":{"type":["string","null"]}},"required":["location"]},
      "outputs":{"$ref":"#/$defs/weather","$defs":{"weather":{"type":"object"}}},
      "tags":["weather"], "average_response_size":256, "x-tool":true,
      "tool_transport":{
        "transport_type":"http", "name":"weather_api_http", "url":"https://example.com/weather/{location}",
        "http_method":"GET", "content_type":"application/json",
        "auth":{"auth_type":"api_key","api_key":"${API_KEY}","var_name":"X-API-Key"},
        "headers":{"Accept":"application/json"}, "body_field":"data", "header_fields":["request_id"],
        "auth_tools":{"auth_type":"basic","username":"${USER}","password":"${PASSWORD}"}, "x-http":42
      }
    }]
  }"""

  test("RFC manual and HTTP descriptor retain schemas, authentication and extensions") {
    val manual = fixture.fromJson[UTCPManual].toOption.get
    val tool = manual.tools.head
    val http = tool.httpTransport.toOption.get
    assertEquals(manual.manualVersion, "1.1.0")
    assertEquals(tool.averageResponseSize, Some(256))
    assertEquals(http.headers, Some(Map("Accept" -> "application/json")))
    assertEquals(http.auth.flatMap(_.fields.toMap.get("api_key")), Some(Json.Str("${API_KEY}")))
    assertEquals(manual.extensions.get("x-owner"), Some(Json.Str("demo")))
    assertEquals(tool.extensions.get("x-tool"), Some(Json.Bool(true)))
    assertEquals(http.extensions.get("x-http"), Some(Json.Num(42)))
    assertEquals(manual.toJson.fromJson[UTCPManual], Right(manual))
    assertEquals(http.toJson.fromJson[UTPCHttpTransport], Right(http))
  }

  test("non-HTTP transports and boolean schemas are preserved, not executed") {
    val tool = UTCPManual.Tool(
      "custom",
      "",
      Json.Bool(true),
      Json.Bool(false),
      Json.Obj("transport_type" -> Json.Str("custom"), "payload" -> Json.Arr(Json.Num(1)))
    )
    val manual = UTCPManual("1.0.0", "1.0.0", Seq(tool))
    assertEquals(manual.toJson.fromJson[UTCPManual], Right(manual))
    assert(tool.httpTransport.isLeft)
  }

  test("HTTP defaults and absent tool metadata need no extra configuration") {
    val http = """{"transport_type":"http","name":"api","url":"https://example.com"}"""
      .fromJson[UTPCHttpTransport]
      .toOption
      .get
    assertEquals(http.httpMethod, "GET")
    assertEquals(http.contentType, "application/json")
    assertEquals(http.auth, None)
    val tool =
      UTCPManual.Tool("ping", "", Json.Bool(true), Json.Bool(true), http.toJson.fromJson[Json.Obj].toOption.get)
    assertEquals(tool.toJson.fromJson[UTCPManual.Tool], Right(tool))
  }

  test("rejects missing fields, malformed schemas, duplicate tool names and non-HTTP descriptors") {
    assert("""{"utcp_version":"1.0.0","manual_version":"1.0.0"}""".fromJson[UTCPManual].isLeft)
    assert(fixture.replace("\"average_response_size\":256", "\"average_response_size\":-1").fromJson[UTCPManual].isLeft)
    assert(
      fixture
        .replace(
          "\"outputs\":{\"$ref\":\"#/$defs/weather\",\"$defs\":{\"weather\":{\"type\":\"object\"}}}",
          "\"outputs\":42"
        )
        .fromJson[UTCPManual]
        .isLeft
    )
    val manual = fixture.fromJson[UTCPManual].toOption.get
    assert(manual.copy(tools = manual.tools ++ manual.tools).toJson.fromJson[UTCPManual].isLeft)
    assert("""{"transport_type":"cli","name":"test","url":"https://example.com"}""".fromJson[UTPCHttpTransport].isLeft)
    assert(
      """{"utcp_version":"1.0.0","utcp_version":"2.0.0","manual_version":"1.0.0","tools":[]}"""
        .fromJson[UTCPManual]
        .isLeft
    )
  }
}
