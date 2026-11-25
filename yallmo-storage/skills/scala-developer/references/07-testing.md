# Scala Testing

## Test Strategy

- Read existing tests and mirror their style.
- Add focused unit tests for pure logic.
- Add integration tests when behavior depends on serialization, storage, HTTP, messaging, or framework wiring.
- Test public behavior, not private implementation details.
- Cover regressions with the smallest test that would fail before the fix.

## What to Test

For models and APIs:

- Smart constructor validation and invariant failures.
- Codec round trips and backward-compatible decoding.
- ADT exhaustive behavior and important cases.
- Optional fields: present, absent, defaults, invalid combinations.
- Time and ordering behavior using fixed clocks.

For services:

- Success path.
- Expected failures and error values.
- Resource cleanup / finalization where relevant.
- Concurrency limits or race-prone behavior if changed.

## Test Quality

- Use descriptive test names that describe behavior.
- Prefer fixtures/builders that reveal only relevant data.
- Avoid sleeps; use test clocks, latches, probes, or effect test utilities.
- Avoid over-mocking pure domain logic.
- Keep random/property tests reproducible with seed reporting when possible.

## Common Frameworks

- ScalaTest: match existing style (`AnyFunSuite`, `AnyWordSpec`, `should`, `must`, etc.).
- MUnit: use concise `test("...")` blocks and assertions.
- ZIO Test: keep effects typed and use test services/layers.
- Specs2/Weaver: follow local conventions.

Always run the focused test command if feasible.
