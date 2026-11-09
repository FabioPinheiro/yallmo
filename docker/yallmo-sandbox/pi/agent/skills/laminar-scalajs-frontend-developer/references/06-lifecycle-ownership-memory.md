# Lifecycle, Ownership, and Memory Management

Source: Laminar documentation: Ownership, Memory Management, Element Lifecycle Hooks, mount/unmount, onMountCallback, onUnmountCallback, onMountBind, onMountInsert.

## Ownership Mental Model

Laminar uses Airstream ownership to manage subscriptions. Reactive bindings attached to Laminar elements are owned by those elements and cleaned up when appropriate.

This is a major reason to prefer Laminar binders over manual subscriptions.

## Mounting

An element is mounted when its underlying DOM node is in the document under a Laminar root. It is unmounted when removed.

Lifecycle hooks let you react to mounting/unmounting.

## Hooks

Common hook categories:

- `onMountCallback` — run code when mounted.
- `onUnmountCallback` — cleanup on unmount.
- `onMountUnmountCallback` — pair setup/cleanup.
- `onMountBind` — create a reactive binding that is active while mounted.
- `onMountInsert` — insert content on mount.

Use exact syntax from the project's Laminar version.

## Subscription Pattern

Prefer:

```scala
div(
  child.text <-- signal.map(_.toString)
)
```

Instead of manually subscribing.

If manual subscription is required for interop, bind it to owner/lifecycle:

```scala
div(
  onMountCallback { ctx =>
    // setup external subscription using ctx.owner if available in version/style
  },
  onUnmountCallback { _ =>
    // cleanup external resource
  }
)
```

## External JS Integration Pattern

When integrating JS libraries that mutate DOM or create resources:

1. Create the host element in Laminar.
2. Initialize JS library on mount.
3. Feed reactive updates through controlled APIs if possible.
4. Dispose/destroy on unmount.
5. Avoid letting JS library and Laminar both own the same DOM subtree unless designed for it.

## Memory Safety Rules

- Avoid global references to elements/components unless intentional.
- Avoid long-lived subscriptions without owners.
- Avoid event listeners added directly to `window`/`document` without cleanup.
- Use lifecycle hooks for timers, sockets, external widgets, and global listeners.

## Window/Document Events

Global events need lifecycle-aware registration. Prefer Laminar APIs for window/document events when available and scope them to the component/root.

## Anti-patterns

- Manual `signal.addObserver` without ownership/cleanup.
- Initializing JS widgets in component construction rather than on mount.
- Forgetting to destroy charts/editors/maps on unmount.
- Mutating Laminar-owned child DOM directly from external libraries.
