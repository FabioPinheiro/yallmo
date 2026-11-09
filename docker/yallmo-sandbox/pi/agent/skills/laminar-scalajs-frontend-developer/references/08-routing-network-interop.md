# Routing, Network Requests, and Interop

Source: Laminar documentation: Network Requests, URL Routing, Integrations With Other Libraries, Browser Compatibility, Special Cases.

## Routing Pattern

Represent current route as reactive state:

```scala
sealed trait Page
object Page {
  case object Home extends Page
  final case class User(id: String) extends Page
}

val pageVar = Var[Page](Page.Home)
```

Render from route signal:

```scala
div(
  child <-- pageVar.signal.map {
    case Page.Home     => HomePage()
    case Page.User(id) => UserPage(id)
  }
)
```

For real browser routing, integrate with URL/history APIs or the project's routing library. Keep parsing/formatting route URLs centralized.

## Routing Design Rules

- Route ADT should represent valid application pages.
- Keep URL parsing/formatting separate from page rendering.
- Keep navigation actions explicit.
- Synchronize browser history and app state carefully to avoid loops.

## Network Requests

Network calls are side effects. Keep them outside pure view construction when possible.

Pattern:

1. UI emits command events.
2. Command stream triggers request.
3. Request result updates state (`Var`/store).
4. View renders from state signal.

Example shape:

```scala
sealed trait LoadState[+A]
case object Loading extends LoadState[Nothing]
final case class Loaded[A](value: A) extends LoadState[A]
final case class Failed(message: String) extends LoadState[Nothing]

val stateVar = Var[LoadState[List[User]]](Loading)
```

Use project-standard HTTP client/fetch wrappers. Do not invent a new client stack if one exists.

## Effect Boundaries

Laminar itself is not an effect system. If the project uses cats-effect/ZIO on Scala.js, follow existing interop conventions. Otherwise, use Scala.js `Future`/JS promises/fetch wrappers deliberately and map results into event streams/state.

## JavaScript Interop

When using JS libraries:

- define typed facades if interaction is non-trivial,
- initialize in mount hooks,
- dispose in unmount hooks,
- keep JS-owned DOM isolated,
- translate JS callbacks into observers/event buses.

## Browser Compatibility

Check target browsers and bundler/polyfill setup before using modern Web APIs.

## Anti-patterns

- Starting network requests directly every time a component function is called.
- Duplicating route parsing in multiple components.
- Mutating browser history and route state independently without synchronization.
- Letting third-party JS mutate Laminar-managed DOM subtrees without a clear boundary.
