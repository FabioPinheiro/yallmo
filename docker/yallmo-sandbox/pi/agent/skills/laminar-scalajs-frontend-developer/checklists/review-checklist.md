# Laminar Scala.js Review Checklist

Use this checklist when implementing or reviewing Laminar frontend code.

## Project Setup

- [ ] Uses Scala.js dependency syntax `%%%` for Laminar.
- [ ] Laminar/Airstream versions are compatible.
- [ ] Imports match Scala version and project style.
- [ ] App renders into an existing unmanaged DOM container.

## Components and DOM

- [ ] Components are small functions returning fresh elements/modifiers.
- [ ] No reused element instances across parents/branches.
- [ ] Manual `.ref` usage is limited and justified.
- [ ] Semantic HTML and accessibility are considered.

## Reactive State

- [ ] `Signal` is used for current state.
- [ ] `EventStream` is used for discrete events.
- [ ] `Var` is used for real mutable state, not avoidable derived state.
- [ ] Derived values are computed from signals instead of duplicated.
- [ ] Observers are passed to child components for actions.

## Events and Forms

- [ ] Event handlers transform events cleanly before updating state.
- [ ] Forms prevent default submission when handled client-side.
- [ ] Controlled inputs synchronize `value <-- signal` and `onInput.mapToValue --> writer`.
- [ ] Event buses are scoped and not abused as global state.

## Dynamic Rendering

- [ ] Loading/error/empty states are modeled explicitly.
- [ ] Dynamic single children use `child <--`.
- [ ] Dynamic text uses `child.text <--`.
- [ ] Lists with stable identity use `split`.
- [ ] List keys are stable domain IDs, not array indices when order changes.

## Lifecycle and Memory

- [ ] Reactive bindings are owned by Laminar elements where possible.
- [ ] Manual subscriptions have owners/cleanup.
- [ ] Window/document listeners are cleaned up.
- [ ] JS widgets are initialized on mount and destroyed on unmount.

## Routing, Network, Interop

- [ ] Route model is explicit and centralized.
- [ ] Network calls are separated from pure view rendering.
- [ ] API/JSON/error handling lives in services, not markup.
- [ ] JS interop boundaries are typed and lifecycle-safe.

## Final Review Output

When reviewing, report:

1. Blocking correctness or memory-leak issues.
2. Reactive state/design issues.
3. UI/accessibility issues.
4. Performance/list rendering issues.
5. Suggested refactor path.
