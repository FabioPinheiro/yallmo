# Reactive Data with Airstream

Source: Laminar documentation: Reactive Data, Attributes and Properties, Event Streams and Signals, Binding Observables, Binders.

## Core Types

Laminar uses Airstream for reactive data:

- `Signal[A]` — time-varying value with current value.
- `EventStream[A]` — discrete events over time, no current value.
- `Var[A]` — mutable reactive state with a `Signal[A]` and writer/updater APIs.
- `Observer[A]` — sink for values/events.
- `EventBus[A]` — imperative event source with stream and writer.

## Var Pattern

Use `Var` for local component/application state:

```scala
val countVar = Var(0)

button(
  "Increment",
  onClick --> (_ => countVar.update(_ + 1))
)

div(
  "Count: ",
  child.text <-- countVar.signal.map(_.toString)
)
```

## Binding Attributes and Properties

Static:

```scala
input(value := "initial")
```

Reactive:

```scala
input(
  value <-- nameSignal
)
```

Use properties for DOM state where appropriate, not just attributes.

## Binding Text and Children

Text:

```scala
span(child.text <-- userSignal.map(_.name))
```

Single child:

```scala
div(child <-- maybeUserSignal.map {
  case Some(user) => UserView(user)
  case None       => span("No user")
})
```

Multiple children:

```scala
ul(children <-- itemsSignal.map(_.map(ItemView.apply)))
```

For performance and identity, prefer `split` for lists; see dynamic children reference.

## EventStream vs Signal

Use `EventStream` for:

- button clicks,
- submitted forms,
- WebSocket messages,
- one-off commands.

Use `Signal` for:

- current route,
- current user/session,
- form field values,
- computed UI state.

## Observer Pattern

Pass observers into components to keep child components reusable:

```scala
def SaveButton(saveBus: Observer[Unit]) =
  button("Save", onClick.mapTo(()) --> saveBus)
```

## Derived State

Prefer derived signals over duplicating state:

```scala
val fullNameSignal =
  firstNameVar.signal.combineWith(lastNameVar.signal).map { case (first, last) =>
    s"$first $last"
  }
```

## Review Rules

- Do not store derived state in separate `Var`s unless needed.
- Do not use `Var` as a global dumping ground.
- Use `Signal` for current state and `EventStream` for events.
- Keep observers/event buses scoped to their owner/component where possible.
- Avoid side effects inside pure `.map` transformations; use observers/effects at boundaries.
