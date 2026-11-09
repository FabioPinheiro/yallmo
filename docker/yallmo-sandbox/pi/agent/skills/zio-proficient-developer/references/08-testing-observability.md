# Testing and Observability

Sources: ZIO Test reference, test aspects, built-in test services, ZIO HTTP testkit, logging, metrics, tracing docs.

## ZIO Test Pattern

Use ZIO Test for effectful tests, services, test aspects, and deterministic test services.

Test layers let you replace production implementations:

```scala
val testRepo: ULayer[UserRepo] = ZLayer.succeed(new UserRepo {
  def find(id: UserId) = ZIO.succeed(testUser)
  def save(user: User) = ZIO.unit
})
```

Test application logic by providing test layers, not by mocking global singletons.

## Test Scope Pattern

For resourceful tests, rely on ZIO scopes/layers so resources are cleaned up.

Use aspects for:

- sequential vs parallel execution,
- timeouts,
- retries/flaky tests only when justified,
- environment-specific tests,
- before/after/around setup.

## HTTP Testing

ZIO HTTP testkit provides infrastructure for testing HTTP applications, including in-memory clients/servers/channels depending on the scenario.

Prefer levels:

1. Pure/domain tests for validation/business logic.
2. Service tests with test layers.
3. Route tests using in-memory HTTP test client/server.
4. Full integration tests for network/config/auth boundaries.

## Client Testing Pattern

For services that call external HTTP APIs:

- wrap them behind a service trait,
- test business code with a fake implementation,
- test the live HTTP implementation with test server/client tools.

## Observability

ZIO includes built-in support/facades for:

- logging,
- metrics (counter, gauge, histogram, summary, frequency),
- tracing concepts/integrations,
- supervision.

Design observability at boundaries and important workflow steps.

## Logging Rules

- Log at service boundaries or when adding context.
- Avoid reflexively logging every error and rethrowing; this creates duplicate noisy logs.
- Include correlation/request IDs when available.
- Do not log secrets or sensitive PII.

## Metrics Rules

Good candidates:

- request count/status/latency,
- external dependency latency/failure count,
- queue sizes,
- worker throughput,
- retry counts,
- domain events.

## Test Review Questions

- Are expected errors tested?
- Are resource finalizers tested where important?
- Are parallel/concurrent behaviors deterministic enough?
- Are HTTP status codes and response bodies tested?
- Are time-dependent behaviors using test clocks/schedules when possible?

## Anti-patterns

- Only testing via full HTTP integration tests.
- Sleeping in tests instead of using test clocks/synchronization.
- Depending on real external services in normal unit tests.
- Treating logs as the only observability strategy.
