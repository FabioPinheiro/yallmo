package fmgp.yallmo.tool.utpc

import fmgp.yallmo.model.YallmoError
import munit.ZSuite
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

final class UTPCHttpProviderImpSuite extends ZSuite {
  private val manual = """{"utcp_version":"1.0.0","manual_version":"1.0.0","tools":[]}"""
  private val routes = Routes(
    Method.GET / "manual" -> handler(Response.json(manual)),
    Method.GET / "echo" -> handler { (request: Request) =>
      Response.json(
        Map(
          "query" -> request.url.queryParams.map.toJson,
          "authorization" -> request.headers.get("Authorization").getOrElse(""),
          "key" -> request.headers.get("X-Api-Key").getOrElse(""),
          "cookie" -> request.headers.get("Cookie").getOrElse("")
        ).toJson
      )
    },
    Method.POST / "echo" / string("id") -> handler { (id: String, request: Request) =>
      request.body.asString.orDie.map(body =>
        Response.json(
          Map(
            "id" -> id,
            "body" -> body,
            "trace" -> request.headers.get("X-Trace").getOrElse(""),
            "query" -> request.url.queryParams.map.toJson,
            "contentType" -> request.headers.get("Content-Type").getOrElse("")
          ).toJson
        )
      )
    },
    Method.POST / "token" -> handler { (request: Request) =>
      request.body.asString.orDie.map { body =>
        if body.contains("grant_type=client_credentials") && body.contains("client_secret=secret") then
          Response.json("""{"access_token":"test-token","token_type":"Bearer"}""")
        else Response.status(Status.Unauthorized)
      }
    },
    Method.GET / "text" -> handler(Response.text("hello")),
    Method.GET / "invalid" -> handler(Response.json("not json")),
    Method.GET / "large" -> handler(Response.text("x" * 100)),
    Method.GET / "fail" -> handler(Response.status(Status.TooManyRequests)),
    Method.GET / "wait" -> handler(ZIO.never)
  )

  private def withServer[A](run: (Client, String) => Task[A]): Task[A] = ZIO.scoped {
    for {
      server <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(100.millis)).build
      port <- Server.install(routes).provideEnvironment(server)
      environment <- Client.default.build
      result <- run(environment.get[Client], s"http://127.0.0.1:$port")
    } yield result
  }

  private def auth(fields: (String, String)*): Option[Json.Obj] =
    Some(Json.Obj(Chunk.fromIterable(fields.map { case (key, value) => key -> Json.Str(value) })))

  private def decoded(value: Json): Map[String, String] = value.as[Map[String, String]].toOption.get

  testZ("discovers an RFC manual and maps Open-Meteo-style arguments without losing fixed parameters") {
    withServer { (client, base) =>
      val provider = new UTPCHttpProviderImp(client)
      for {
        found <- provider.discover(UTPCHttpTransport("manual", s"$base/manual"))
        result <- provider.call(
          UTPCHttpTransport("weather", s"$base/echo?forecast_days=1"),
          Json.Obj(
            "latitude" -> Json.Num(37.0187),
            "current" -> Json.Str("temperature_2m,rain")
          )
        )
        query = decoded(result)("query").fromJson[Map[String, Seq[String]]].toOption.get
        templated <- provider.call(
          UTPCHttpTransport("query", s"$base/echo?search={search}"),
          Json.Obj("search" -> Json.Str("a&b=c"))
        )
      } yield {
        assertEquals(found.tools, Seq.empty)
        assertEquals(query("forecast_days"), Seq("1"))
        assertEquals(query("latitude"), Seq("37.0187"))
        assertEquals(query("current"), Seq("temperature_2m,rain"))
        assertEquals(
          decoded(templated)("query").fromJson[Map[String, Seq[String]]],
          Right(Map("search" -> Seq("a&b=c")))
        )
      }
    }
  }

  testZ("encodes URL arguments and maps JSON body, headers and remaining query arguments") {
    withServer { (client, base) =>
      val provider = new UTPCHttpProviderImp(client)
      val transport = UTPCHttpTransport(
        "create",
        s"$base/echo/{id}",
        "POST",
        bodyField = Some("data"),
        headerFields = Some(Seq("X-Trace"))
      )
      for {
        result <- provider.call(
          transport,
          Json.Obj(
            "id" -> Json.Str("a b"),
            "data" -> Json.Obj("message" -> Json.Str("hello")),
            "X-Trace" -> Json.Str("trace-1"),
            "search" -> Json.Str("a&b=c")
          )
        )
        echo = decoded(result)
      } yield {
        assertEquals(echo("id"), "a b")
        assertEquals(echo("body").fromJson[Map[String, String]], Right(Map("message" -> "hello")))
        assertEquals(echo("trace"), "trace-1")
        assertEquals(echo("contentType"), "application/json")
        assertEquals(echo("query").fromJson[Map[String, Seq[String]]], Right(Map("search" -> Seq("a&b=c"))))
      }
    }
  }

  testZ("resolves variables and authenticates with API key, Basic and OAuth2 client credentials") {
    withServer { (client, base) =>
      val provider = new UTPCHttpProviderImp(client, Map("KEY" -> "secret"))
      val transport = UTPCHttpTransport("auth", s"$base/echo")
      for {
        keyed <- provider.call(transport.copy(auth = auth("auth_type" -> "api_key", "api_key" -> "${KEY}")), Json.Obj())
        basic <- provider.call(
          transport.copy(auth = auth("auth_type" -> "basic", "username" -> "user", "password" -> "$KEY")),
          Json.Obj()
        )
        oauth <- provider.call(
          transport.copy(auth =
            auth(
              "auth_type" -> "oauth2",
              "token_url" -> s"$base/token",
              "client_id" -> "client",
              "client_secret" -> "$KEY"
            )
          ),
          Json.Obj()
        )
      } yield {
        assertEquals(decoded(keyed)("key"), "secret")
        assertEquals(decoded(basic)("authorization"), "Basic dXNlcjpzZWNyZXQ=")
        assertEquals(decoded(oauth)("authorization"), "Bearer test-token")
      }
    }
  }

  testZ("returns text, reports HTTP/JSON errors and bounds response bytes and duration") {
    withServer { (client, base) =>
      val provider = new UTPCHttpProviderImp(client)
      for {
        text <- provider.call(UTPCHttpTransport("text", s"$base/text"), Json.Obj())
        failed <- provider.call(UTPCHttpTransport("fail", s"$base/fail"), Json.Obj()).either
        invalid <- provider.call(UTPCHttpTransport("invalid", s"$base/invalid"), Json.Obj()).either
        large <- new UTPCHttpProviderImp(client, maxResponseBytes = 8)
          .call(UTPCHttpTransport("large", s"$base/large"), Json.Obj())
          .either
        timed <- new UTPCHttpProviderImp(client, requestTimeout = 200.millis)
          .call(UTPCHttpTransport("wait", s"$base/wait"), Json.Obj())
          .either
      } yield {
        assertEquals(text, Json.Str("hello"))
        assert(failed.left.exists(_.isInstanceOf[YallmoError.HttpFailure]))
        assert(invalid.left.exists(_.isInstanceOf[YallmoError.ProtocolFailure]))
        assert(large.left.exists(_.getMessage.contains("byte limit")))
        assert(timed.left.exists(_.getMessage.contains("timed out")))
      }
    }
  }

  testZ("rejects unsafe origins, fixed-query overrides, missing variables and header injection") {
    withServer { (client, base) =>
      val provider = new UTPCHttpProviderImp(client)
      for {
        origin <- provider
          .call(UTPCHttpTransport("bad", "https://{host}/echo"), Json.Obj("host" -> Json.Str("localhost")))
          .either
        insecure <- provider.call(UTPCHttpTransport("bad", "http://example.com/echo"), Json.Obj()).either
        conflict <- provider
          .call(UTPCHttpTransport("bad", s"$base/echo?fixed=1"), Json.Obj("fixed" -> Json.Num(2)))
          .either
        missing <- provider
          .call(
            UTPCHttpTransport("bad", s"$base/echo", headers = Some(Map("X-Key" -> "${YALLMO_UTPC_MISSING_TEST_KEY}"))),
            Json.Obj()
          )
          .either
        header <- provider
          .call(
            UTPCHttpTransport("bad", s"$base/echo", headers = Some(Map("X-Key" -> "value\r\nInjected: yes"))),
            Json.Obj()
          )
          .either
      } yield Seq(origin, insecure, conflict, missing, header).foreach(result => assert(result.isLeft))
    }
  }
}
