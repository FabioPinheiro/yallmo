# Domain Models and Scaladoc

Domain models are long-lived context. They should explain business meaning, not just data shape.

## When to Add or Improve Scaladoc

Add or improve Scaladoc when creating or substantially changing:

- Public or cross-module classes, traits, objects, enums, and case classes.
- Domain models persisted to storage or serialized over APIs.
- Models with fields whose meaning is not obvious from their names.
- ADTs where each case represents a business state or protocol state.
- Methods whose contract, side effects, validation, ordering, or error behavior matters.

Do not add noisy Scaladoc that repeats obvious names only. Useful Scaladoc records intent, invariants, lifecycle, and usage.

## Model Scaladoc Template

For a case class or model, usually document:

```scala
/**
 * Represents <business concept>.
 *
 * <Explain why this model exists, which layer owns it, and the lifecycle/state it captures.>
 *
 * @param id stable identifier used to correlate <where/how it is used>
 * @param status current lifecycle state; callers should <important usage rule>
 * @param createdAt instant when <event happened>; used for <sorting/auditing/expiry/etc.>
 */
final case class ExampleModel(
  id: ExampleId,
  status: ExampleStatus,
  createdAt: Instant
)
```

For each field, prefer documenting:

- Business meaning.
- Units and timezone for time/numeric values.
- Optionality: why it may be absent, not just that it is an `Option`.
- Invariants and valid ranges.
- Ownership/source of truth.
- Where it is consumed: persistence, API, UI, calculations, routing, ownership, permissions, etc.
- Whether callers may construct it directly or should use a smart constructor.

## ADT Scaladoc Template

```scala
/**
 * Lifecycle state for an import job.
 *
 * Values are persisted, so changing case names requires a migration/codecs update.
 */
sealed trait ImportStatus

object ImportStatus {
  /** Job was accepted but no worker has started processing it yet. */
  case object Pending extends ImportStatus

  /** Job completed and its output is available to downstream consumers. */
  case object Completed extends ImportStatus
}
```

## Method Scaladoc Template

Document methods when the contract is more important than the implementation:

```scala
/**
 * Resolves the effective configuration for a user.
 *
 * Precedence is: explicit request overrides, persisted user preferences, then workspace defaults.
 * Missing optional preferences are not treated as errors.
 *
 * @return the fully resolved configuration, safe to pass to job execution
 */
def resolveConfig(...): Either[ConfigError, EffectiveConfig]
```

## Good Model Design

- Use small value classes/opaque types/newtypes for important identifiers and constrained values.
- Prefer ADTs over strings for finite states.
- Keep transport DTOs separate from domain models when external shape differs from internal meaning.
- Keep persistence concerns out of pure domain models unless the model is explicitly a storage record.
- Use smart constructors for invariants that cannot be represented directly by the type system.
- Avoid boolean blindness: replace `isActive`, `sync = true`, etc. with domain states when combinations matter.

## Questions to Answer in Scaladoc

When future agents modify or use this model, they should be able to answer:

1. What real-world/domain concept does this represent?
2. Who creates it and who is allowed to mutate/copy it?
3. Which fields are identifiers, references, timestamps, flags, derived values, or source-of-truth values?
4. Which fields are persisted or serialized externally?
5. Which invariants must always hold?
6. What breaks if a field changes meaning or format?
7. Which services, APIs, or workflows rely on it?
