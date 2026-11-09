# Elements, Modifiers, and Components

Source: Laminar documentation: Tags & Elements, Modifiers, nesting, inContext, reusing elements, missing keys.

## Elements

Laminar provides HTML/SVG tag builders such as:

```scala
div(
  cls := "card",
  h2("Title"),
  p("Body")
)
```

An element is a real DOM element wrapped by a Laminar type. Its native DOM node is available as `.ref`.

## Modifiers

Most things passed to an element are modifiers:

- child elements,
- text nodes,
- attributes/properties,
- styles/classes,
- event listeners,
- reactive binders,
- lifecycle hooks.

Example:

```scala
button(
  cls := "primary",
  disabled := false,
  "Save"
)
```

## Component Pattern

Prefer small component functions:

```scala
def UserCard(user: User): HtmlElement =
  articleTag(
    cls := "user-card",
    h3(user.name),
    p(user.email)
  )
```

For reusable modifiers:

```scala
def primaryButtonMods: Mod[Button] =
  cls := "btn btn-primary"
```

For configurable components:

```scala
def IconButton(labelText: String, onClick: Observer[dom.MouseEvent]): HtmlElement =
  button(
    aria.label := labelText,
    onClick --> onClick,
    labelText
  )
```

## Children

Static nesting is direct:

```scala
div(
  h1("Dashboard"),
  section("Content")
)
```

Dynamic children use binders such as `child <-- signal` or `children <-- stream/signal`.

## Reusing Elements

A real DOM element can only be in one place at a time. Do not reuse the same created Laminar element instance in multiple parents. Use functions to create fresh elements:

```scala
def reusableItem(text: String) = li(text)
```

## inContext

Use `inContext` when a modifier needs the element being built, for example to access `.ref` or element-specific APIs.

Keep `inContext` limited; most logic should be declarative modifiers/binders.

## Missing Keys

Laminar's available tags/attrs/props/events come from Scala DOM Types. If a key is missing, consider:

- using native DOM interop carefully,
- defining a custom key in the project style,
- checking whether the version is outdated,
- contributing upstream where appropriate.

## Anti-patterns

- Manually appending/removing DOM nodes inside components.
- Reusing element instances instead of component functions.
- Large components mixing app state, network calls, and markup.
- Excessive `.ref` mutation instead of Laminar modifiers.
