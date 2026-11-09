# Events, EventBus, and Controlled Inputs

Source: Laminar documentation: Event System, DOM event listeners, EventBus, event processors, target extraction, controlled inputs.

## DOM Events

Register listeners with event props and observers:

```scala
button(
  "Click",
  onClick --> { _ => dom.console.log("clicked") }
)
```

Map events before sending:

```scala
button(
  "Save",
  onClick.mapTo(()) --> saveObserver
)
```

## Event Processors

Use event processors for behavior such as preventing default and stopping propagation when needed.

Common use cases:

- prevent default form submit page reload,
- stop click propagation inside nested controls,
- use capture phase deliberately,
- obtain typed event target.

Keep event processing explicit and local to the element that needs it.

## EventBus Pattern

Use `EventBus[A]` when you need an imperative event source or to connect multiple producers to a stream:

```scala
val submitBus = new EventBus[Unit]

val submitStream = submitBus.events

button(
  "Submit",
  onClick.mapTo(()) --> submitBus.writer
)
```

Do not use buses to avoid modeling state. For current values, use `Var`/`Signal`.

## Controlled Inputs

For inputs whose displayed value must follow application state, use controlled inputs.

Typical text input pattern:

```scala
val nameVar = Var("")

input(
  value <-- nameVar.signal,
  onInput.mapToValue --> nameVar.writer
)
```

This keeps DOM value and app state synchronized.

## Form Pattern

```scala
final case class LoginForm(email: String, password: String)

val emailVar = Var("")
val passwordVar = Var("")

val formSignal =
  emailVar.signal.combineWith(passwordVar.signal).map(LoginForm.apply)

form(
  onSubmit.preventDefault.mapTo(()) --> submitBus.writer,
  input(
    typ := "email",
    value <-- emailVar.signal,
    onInput.mapToValue --> emailVar.writer
  ),
  input(
    typ := "password",
    value <-- passwordVar.signal,
    onInput.mapToValue --> passwordVar.writer
  ),
  button(typ := "submit", "Login")
)
```

Adapt exact syntax to project/Laminar version.

## Target Extraction

Prefer Laminar helpers such as `mapToValue` for common input values. Use typed target extraction only when helpers are insufficient.

## Design Rules

- Separate event streams from state signals.
- Send child events upward via observers/callbacks.
- Keep validation as derived signals or explicit submit handling.
- Prevent default form submission intentionally.
- Avoid reading arbitrary DOM values via `.ref` when reactive bindings are available.

## Anti-patterns

- Mixing uncontrolled manual DOM mutation with controlled Laminar values.
- Updating many unrelated `Var`s from one low-level handler when a model `Var` would be clearer.
- Creating global event buses for local component events.
