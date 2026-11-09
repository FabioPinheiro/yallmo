# CSS, SVG, and UI Design

Source: Laminar documentation: CSS, CSS keyword helpers, unit/function helpers, vendor prefixes, CSS approaches, SVG syntax/imports, external SVGs, className and special keys.

## Classes

Static classes:

```scala
div(cls := "card selected")
```

Dynamic classes:

```scala
div(
  cls.toggle("selected") <-- selectedSignal
)
```

Use project conventions for class helpers, CSS modules, Tailwind-style utilities, or Scala CSS libraries.

## Styles

Static styles:

```scala
div(
  color := "red",
  padding := "1rem"
)
```

Reactive styles:

```scala
div(
  opacity <-- enabledSignal.map(if (_) "1" else "0.5")
)
```

Prefer classes for complex styling and styles for dynamic computed values.

## CSS Approaches

Laminar can work with multiple CSS approaches:

- plain CSS files,
- utility CSS frameworks,
- CSS modules via bundler tooling,
- Scala CSS libraries,
- inline styles for dynamic/small cases.

Choose the project's existing approach.

## Accessibility

Frontend work should include accessibility:

- semantic HTML where possible,
- labels for inputs,
- keyboard support for interactive elements,
- ARIA only when semantic HTML is insufficient,
- focus management for dialogs/menus,
- contrast and responsive design.

## SVG

Laminar supports SVG with special imports/namespaces. Check existing project style before adding SVG code.

Typical approach:

```scala
svg.svg(
  svg.viewBox := "0 0 24 24",
  svg.path(svg.d := "...")
)
```

Exact imports may differ. Do not mix HTML and SVG keys incorrectly.

## Component Styling Pattern

Keep component structure and styling readable:

```scala
def Alert(kind: AlertKind, message: String): HtmlElement =
  div(
    cls := s"alert alert-${kind.cssName}",
    role := "alert",
    message
  )
```

## Design Rules

- Prefer semantic HTML before custom div/button behavior.
- Keep class naming consistent.
- Bind dynamic classes/styles from signals instead of manual `.ref.classList` changes.
- Do not put large CSS strings inline unless the project does so intentionally.
- Keep visual components reusable and state-light.

## Anti-patterns

- Manual DOM class toggling when `cls <--`/`cls.toggle` can be used.
- Styling every element inline with magic values.
- Non-button clickable `div`s without keyboard accessibility.
- SVG imports/keys mixed incorrectly with HTML APIs.
