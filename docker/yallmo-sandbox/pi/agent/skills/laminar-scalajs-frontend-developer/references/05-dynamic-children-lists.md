# Dynamic Children, Conditional Rendering, and Lists

Source: Laminar documentation: Reactive Data children, Conditional Rendering, Loading State, performant children rendering with `split`, mutable collections, inserters.

## Single Dynamic Child

Use `child <-- signalOfElement`:

```scala
div(
  child <-- userSignal.map {
    case Some(user) => UserCard(user)
    case None       => div("Please sign in")
  }
)
```

## Dynamic Text

Use `child.text <--` for text-like values:

```scala
span(child.text <-- countSignal.map(_.toString))
```

## Conditional Rendering

Represent UI states explicitly:

```scala
sealed trait PageState
object PageState {
  case object Loading extends PageState
  final case class Loaded(items: List[Item]) extends PageState
  final case class Failed(message: String) extends PageState
}

div(
  child <-- stateSignal.map {
    case PageState.Loading       => div("Loading...")
    case PageState.Loaded(items) => ItemList(items)
    case PageState.Failed(msg)   => div(cls := "error", msg)
  }
)
```

## Lists

Simple list rendering:

```scala
ul(
  children <-- itemsSignal.map(_.map(ItemView.apply))
)
```

This may recreate many DOM nodes. For identity/performance, use `split`.

## Performant List Rendering with split

Use `split` when list items have stable keys:

```scala
ul(
  children <-- itemsSignal.split(_.id) { (id, initialItem, itemSignal) =>
    li(
      keyAttr := id.toString,
      child.text <-- itemSignal.map(_.name)
    )
  }
)
```

Benefits:

- preserves DOM nodes for unchanged keyed items,
- updates only changed item views,
- improves focus/input preservation,
- better for animations and large lists.

Adapt exact key attribute/style to project conventions.

## Splitting Without Key

Avoid keyless splitting unless item identity does not matter. Prefer stable keys from domain IDs.

## Loading States

Model loading explicitly instead of using `Option` ambiguously when errors/reloads matter:

```scala
sealed trait RemoteData[+E, +A]
case object NotAsked extends RemoteData[Nothing, Nothing]
case object Loading extends RemoteData[Nothing, Nothing]
final case class Success[A](value: A) extends RemoteData[Nothing, A]
final case class Failure[E](error: E) extends RemoteData[E, Nothing]
```

## Rendering Custom Components

Component functions should return fresh elements and accept signals/observers when reactive.

```scala
def UserRow(userSignal: Signal[User], selected: Observer[UserId]): HtmlElement =
  li(
    child.text <-- userSignal.map(_.name),
    onClick.mapTo(()) --> { _ => selected.onNext(userSignal.now().id) } // prefer avoiding now if possible
  )
```

Avoid `now()` when the event stream can carry the value more directly.

## Anti-patterns

- Re-rendering large lists by mapping to full element lists when items have stable identity.
- Using array index as key when item order can change.
- Encoding loading/error/empty states as magic strings.
- Reusing child element instances across branches.
