---
name: zio-proficient-developer
description: "Use for Scala ZIO/ZIO HTTP work: effects, services/layers, typed errors, resources, concurrency, streams, HTTP routes/endpoints/clients, zio-json, and tests."
---

# ZIO Proficient Developer

Inspect project style and versions first. Prefer local conventions and do not invent APIs.

Read only relevant references:
- `references/01-core-effect-model.md`
- `references/02-services-layers-di.md`
- `references/03-errors-resources.md`
- `references/04-concurrency-streams.md`
- `references/05-http-routing-handlers.md`
- `references/06-http-endpoints-codecs.md`
- `references/07-http-client-server-config.md`
- `references/08-testing-observability.md`
- `references/09-design-patterns.md`
- `references/10-streams-deep-dive.md`
- `references/11-zstream-operations-patterns.md`
- `references/12-zsink-zpipeline-zchannel.md`
- `references/13-zio-json.md`
- `checklists/review-checklist.md`

Rules:
- Treat ZIO values as workflow descriptions; run effects only at boundaries.
- Keep business logic in services/domain code; decode/encode at HTTP/API edges.
- Use typed domain errors for expected failures; defects/Throwable for unexpected ones.
- Prefer `ZLayer` for construction/resource lifetime and high-level concurrency combinators before manual fibers.
- When sequencing ZIO effects, prefer `for` comprehensions over `flatMap` for readability.
- Preserve streaming back-pressure/resource safety; do not collect unbounded streams.
- Choose `Routes`/`Handler` vs declarative `Endpoint` intentionally.
- For zio-json, keep codecs near domain/API models, handle decode failures as boundary errors, and test non-trivial codecs.

Output compact examples, imports only when helpful, and mention required layers/error types when they matter.
