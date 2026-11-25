# Scala Implementation / Review Checklist

Use this at the end of implementation or during review.

## Project Fit

- [ ] Followed the project Scala version and style.
- [ ] Inspected nearby code before changing patterns.
- [ ] Kept the change as small as practical.
- [ ] No unrelated formatting or rewrites.

## Types and APIs

- [ ] Public signatures are explicit and meaningful.
- [ ] Important domain concepts use named types/ADTs, not raw primitives.
- [ ] Expected failures are represented intentionally.
- [ ] Pattern matches are exhaustive or intentionally guarded.
- [ ] No unsafe `get`/`head`/map apply unless justified.

## Models and Scaladoc

- [ ] New/changed domain models have useful Scaladoc when future context matters.
- [ ] Case class fields document purpose, optionality, invariants, units/timezones, and important consumers.
- [ ] Persisted/serialized field changes consider migrations and compatibility.
- [ ] ADT cases explain business meaning when not obvious.

## Effects and Resources

- [ ] Effects are visible in types and not run accidentally.
- [ ] Blocking work is isolated from CPU/event-loop pools.
- [ ] Resources have clear ownership and are closed/finalized.
- [ ] Parallelism is bounded where external systems or large data are involved.

## Tests and Validation

- [ ] Added/updated focused tests for behavior changes.
- [ ] Codec/storage/API compatibility tested if relevant.
- [ ] Run formatting/compile/tests where feasible. > sbt 'compile;scalafmt;test
- [ ] Reported validation commands and results.

## Skill Improvement

- [ ] Considered whether this global Scala skill should be improved from new project knowledge or repeated feedback.
