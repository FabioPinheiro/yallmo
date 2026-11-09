# Project Setup, Imports, and Rendering

Source: Laminar documentation: Dependencies, Imports, Rendering, Waiting for DOM to load.

## Dependencies

Laminar is a Scala.js frontend library and depends on Airstream.

Typical `build.sbt` dependency:

```scala
libraryDependencies += "com.raquo" %%% "laminar" % "<version>"
```

Laminar includes a compatible Airstream version. Only add Airstream explicitly if the project intentionally needs a newer compatible version:

```scala
libraryDependencies += "com.raquo" %%% "airstream" % "<version>"
```

Do not assume Laminar and Airstream versions must share one version variable.

## Imports

For Scala 3 examples, prefer:

```scala
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
```

For Scala 2 projects, expect:

```scala
import com.raquo.laminar.api.L._
import org.scalajs.dom
```

Alternative explicit style:

```scala
import com.raquo.laminar.api.{*, given}
// use L.div, L.child, A.Signal, etc.
```

Keep `dom.` prefix to distinguish native JS DOM types from Laminar types.

## Rendering

Creating a Laminar element creates its underlying real DOM element (`.ref`) immediately, but it is initially detached/unmounted.

Render the root element into an existing unmanaged DOM container:

```scala
object Main {
  def main(args: Array[String]): Unit = {
    val container = dom.document.querySelector("#app")

    val app = div(
      h1("Hello Laminar")
    )

    render(container, app)
  }
}
```

The container should normally be empty and not itself managed by Laminar.

## DOMContentLoaded

If the Scala.js script might run before the container exists, use `renderOnDomContentLoaded`:

```scala
object Main {
  def main(args: Array[String]): Unit = {
    lazy val container = dom.document.querySelector("#app")
    val app = div(h1("Hello world"))
    renderOnDomContentLoaded(container, app)
  }
}
```

Usually placing the script tag before `</body>` after the container is enough.

## RootNode

`render` returns a root node that can be unmounted/mounted:

```scala
val root = render(container, app)
root.unmount()
root.mount()
```

Use this mostly for app lifecycle/integration tests, not normal UI navigation.

## Review Rules

- Verify `%%%` is used for Scala.js dependencies.
- Verify the app renders into an existing HTML container.
- Avoid putting the Scala.js script inside the app container.
- Avoid manual DOM mounting/unmounting for normal app state; use reactive rendering.
