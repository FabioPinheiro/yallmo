# NOTES


## The important boundaries:

 ┌────────────────────────┬─────────────────────────────────────────────────────────────────┐
 │ Component              │ Responsibility                                                  │
 ├────────────────────────┼─────────────────────────────────────────────────────────────────┤
 │ ServerApplication      │ Owns composition, storage lease and execution lifetime.         │
 ├────────────────────────┼─────────────────────────────────────────────────────────────────┤
 │ AgentCatalog           │ Lazily caches complete agents, adapters and providers.          │
 ├────────────────────────┼─────────────────────────────────────────────────────────────────┤
 │ ApplicationTools       │ Finds available tools and binds their dependencies.             │
 ├────────────────────────┼─────────────────────────────────────────────────────────────────┤
 │ AgentRunner            │ Runs the loop, validates tool requests and executes tools.      │
 ├────────────────────────┼─────────────────────────────────────────────────────────────────┤
 │ OrchestratorLive       │ Persists messages and manages sessions.                         │
 └────────────────────────┴─────────────────────────────────────────────────────────────────┘

Complete agent generations bundle a bound program, effective context and executable tools.
See [Service wiring](docs/SERVICE_WIRING.md) for construction, execution and lifetime details.


{
  "id": "sha256:333b060c5508104914432e922ba2b375113171f817894e71230f5b3afc0189be",
  "kind": "msg",
  "value": {
    "agentId": "Gemma",
    "parentId": "sha256:92264f56212f167c9ebca465bde2155a56802585fe37576bd5796b3b65b0aea7",
    "timestamp": "2026-10-31T21:42:30.644346045Z",
    "payload": {
      "ToolOutput": {
        "output": {
          "callId": "nBLIFtkbXXqjGdjXRdUtbrKSWf9Fp3cS",
          "name": "scalamode",
          "result": {
            "Executed": {
              "value": "{\"timestamp\":\"2026-10-31T21:42:30.414366795Z\",\"level\":\"INFO\",\"fiber\":\"zio-fiber-354882241\",\"message\":\"RpcWebSocket client connected connectionId=8d7b67ec-2493-4592-a2a2-65cdcf8beed6\",\"annotations\":{}}\n{\"timestamp\":\"2026-10-31T21:42:30.485686545Z\",\"level\":\"INFO\",\"fiber\":\"zio-fiber-354882241\",\"message\":\"RpcWebSocket client disconnected connectionId=8d7b67ec-2493-4592-a2a2-65cdcf8beed6\",\"annotations\":{}}\n{\"timestamp\":\"2026-10-31T21:42:30.608166587Z\",\"level\":\"ERROR\",\"fiber\":\"zio-fiber-1132656920\",\"message\":\"Scala execution failed\",\"cause\":\"fmgp.yallmo.fs.FsError$NotFound: NotFound(e)\\n\\tat <empty>.ScalaToolMain.run.fs.RpcClient.list(Main.scala:17)\\n\\tat <empty>.ScalaToolMain.run(Main.scala:20)\\n\\tat <empty>.ScalaToolMain.run(Main.scala:21)\\n\",\"annotations\":{}}"
            }
          },
          "logs": [],
          "exitCode": 1,
          "truncated": false,
          "compilationTime": 2458,
          "executionTime": 3292,
          "totalTime": 5763
        }
      }
    }
  }
}
