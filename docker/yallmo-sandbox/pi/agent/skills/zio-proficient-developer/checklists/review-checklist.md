# ZIO / ZIO HTTP Review Checklist

Use this checklist when reviewing or implementing ZIO/ZIO HTTP code.

## ZIO Core

- [ ] Effect types are precise (`UIO`, `IO[E, A]`, `Task`, `ZIO[R, E, A]`).
- [ ] Expected domain errors are ADTs, not exceptions/strings.
- [ ] Unexpected errors are not over-modeled in the typed error channel.
- [ ] Effects are not unsafely run inside services.
- [ ] Blocking/external IO is handled appropriately.

## Services and Layers

- [ ] Services are traits with cohesive responsibilities.
- [ ] Live implementations receive dependencies through constructors/layers.
- [ ] `ZLayer` wiring is at the boundary or composition module.
- [ ] Resourceful services use `ZLayer.scoped` / `Scope`.
- [ ] Test implementations/layers are easy to provide.

## Resources and Concurrency

- [ ] Resources have acquisition/release safety.
- [ ] Child fibers are supervised/scoped or avoided.
- [ ] Parallelism is bounded where needed.
- [ ] Queues are bounded unless there is a clear reason.
- [ ] Shared state uses `Ref`, STM, queues, hubs, or semaphores appropriately.

## ZIO Streams

- [ ] `ZStream[R, E, O]` environment and error types are precise.
- [ ] Large/unbounded streams are not consumed with `runCollect`.
- [ ] Resource-backed streams use scoped/resourceful constructors and release safely on failure/interruption.
- [ ] Transformations use pure operators where possible and `mapZIO` only for effects.
- [ ] Effectful per-element processing has bounded parallelism when needed.
- [ ] Back-pressure is preserved; buffering/queues are bounded and intentional.
- [ ] Chunk/batch processing is used where throughput matters.
- [ ] Stream errors are mapped/recovered deliberately and retries use bounded schedules.
- [ ] Reusable transformations are modeled as `ZPipeline` where appropriate.
- [ ] Reusable consumers/aggregations are modeled as `ZSink` where appropriate.
- [ ] `ZChannel` is used only for advanced cases not covered by streams/sinks/pipelines.

## ZIO HTTP

- [ ] Routes/handlers only adapt HTTP to application services.
- [ ] Request decoding and validation are explicit.
- [ ] Domain errors map consistently to HTTP statuses.
- [ ] Middleware handles cross-cutting concerns instead of duplication.
- [ ] Public DTOs/schemas/codecs are stable and intentional.
- [ ] Endpoint API is used when OpenAPI/client generation or contract-first style is desired.

## Client/External IO

- [ ] External HTTP calls are wrapped in a service trait.
- [ ] Non-2xx responses map to domain errors.
- [ ] Timeouts/retries are considered and bounded.
- [ ] URLs/secrets/config are not hardcoded.

## Tests and Observability

- [ ] Pure/domain logic has direct tests.
- [ ] Services are tested with fake/test layers.
- [ ] HTTP routes are tested with appropriate ZIO HTTP testkit/in-memory tools.
- [ ] Logs add context and avoid sensitive data.
- [ ] Metrics/tracing exist for important production workflows.

## Final Review Output

When reviewing, report:

1. Blocking correctness/resource-safety issues.
2. ZIO idiom issues.
3. Stream/back-pressure/resource-safety issues.
4. HTTP contract/API issues.
5. Test gaps.
6. Suggested refactor path.
