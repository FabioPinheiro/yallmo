# Laminar Frontend Architecture Patterns

Source: Laminar documentation sections plus practical Scala.js frontend architecture patterns.

## Suggested App Structure

```text
frontend/
  Main.scala                 app entrypoint/render
  routes/                    route ADTs, URL parser/formatter
  state/                     stores, app model, commands
  services/                  API clients, local storage, browser integrations
  components/                reusable UI components
  pages/                     route-level pages
  styles/                    CSS/assets
```

Adapt to the project's existing package layout.

## Unidirectional Data Flow

A scalable Laminar app often follows:

```text
DOM events -> commands/event streams -> state updates -> signals -> rendered UI
```

Use observers to send events upward and signals to pass state downward.

## Local vs Global State

Use local `Var`s for local UI state:

- input fields,
- dropdown open/closed,
- tab selection,
- temporary modal state.

Use app-level stores for shared state:

- authenticated user,
- current route,
- cached remote data,
- global notifications.

## Component API Pattern

Reusable components should accept values/signals and observers instead of directly touching global stores:

```scala
def UserSearch(
  querySignal: Signal[String],
  queryObserver: Observer[String],
  submitObserver: Observer[Unit]
): HtmlElement = ???
```

Pages can wire components to stores/services.

## State Model Pattern

Prefer explicit ADTs:

```scala
sealed trait AuthState
object AuthState {
  case object Unknown extends AuthState
  case object Anonymous extends AuthState
  final case class Authenticated(user: User) extends AuthState
}
```

Avoid booleans that cannot represent all states (`isLoading`, `hasError`, `userOpt`) when an ADT is clearer.

## Service Boundary Pattern

Wrap browser/network APIs behind Scala services:

```scala
trait UserApi {
  def loadUsers(): Future[List[User]] // or project effect type
}
```

Keep JSON decoding and HTTP error mapping out of view components.

## Performance Patterns

- Use `split` for keyed dynamic lists.
- Avoid recreating large static subtrees unnecessarily.
- Keep derived signals focused.
- Avoid huge global signals causing unrelated UI to update.
- Use stable keys for focus-preserving list updates.

## Testing Strategy

Depending on project tooling:

- test pure route parsers, validators, reducers/stores directly,
- test API clients with mocked fetch/server where available,
- test component behavior via Scala.js DOM test tools or browser tests,
- test critical flows end-to-end with Playwright/Cypress if configured.

## Anti-patterns

- One giant `Main.scala` with all state, routes, and views.
- Global event buses for everything.
- View components performing fetches on construction without lifecycle control.
- Storing every derived value in a `Var`.
- Manual DOM updates mixed with reactive Laminar bindings.
- Unkeyed list rendering for editable/reorderable lists.
