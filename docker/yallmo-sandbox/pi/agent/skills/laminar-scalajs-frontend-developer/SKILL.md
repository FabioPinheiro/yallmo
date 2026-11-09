---
name: laminar-scalajs-frontend-developer
description: Use for Scala.js/Laminar/Airstream frontend design, implementation, refactoring, review, routing, network requests, lifecycle/ownership, CSS/SVG, forms, and tests.
---

# Laminar Scala.js Frontend Developer

Inspect project versions and conventions first. Prefer local patterns and do not invent APIs.

Read only relevant references:
- `references/01-project-setup-rendering.md`
- `references/02-elements-modifiers-components.md`
- `references/03-reactive-data-airstream.md`
- `references/04-events-forms-inputs.md`
- `references/05-dynamic-children-lists.md`
- `references/06-lifecycle-ownership-memory.md`
- `references/07-css-svg-ui-design.md`
- `references/08-routing-network-interop.md`
- `references/09-architecture-patterns.md`
- `checklists/review-checklist.md`

Rules:
- Keep UI as pure component functions returning Laminar elements/modifiers where possible.
- Use Airstream (`Signal`, `EventStream`, `Var`, `Observer`, `EventBus`) for state/data flow; avoid manual DOM mutation except interop.
- Prefer controlled inputs when app state must mirror form state.
- Use lifecycle hooks/ownership for subscriptions and external resources.
- Use `children <-- ...split(...)` where list identity/performance matters.
- Separate view rendering from routing, network, effects, and app state.

Code style: Scala 3 examples usually use `import com.raquo.laminar.api.L.{*, given}` and `import org.scalajs.dom` when native DOM types are referenced.
