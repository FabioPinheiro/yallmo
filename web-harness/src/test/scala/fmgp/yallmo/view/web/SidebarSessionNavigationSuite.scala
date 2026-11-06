package fmgp.yallmo.view.web

import munit.FunSuite
import scala.collection.mutable.ListBuffer

final class SidebarSessionNavigationSuite extends FunSuite {
  test("Graph session clicks only navigate the graph and close the sidebar, including repeated selections") {
    val effects = ListBuffer.empty[String]
    val navigation = new SidebarSessionNavigation(
      () => ViewRoute.Graph,
      id => effects += s"resume:$id",
      () => effects += "open-chat",
      id => effects += s"graph:$id",
      () => effects += "close-sidebar"
    )
    navigation.select("existing")
    navigation.select("existing")
    navigation.select("empty")
    assertEquals(
      effects.toList,
      List("graph:existing", "close-sidebar", "graph:existing", "close-sidebar", "graph:empty", "close-sidebar")
    )
  }

  test("every other route preserves existing-session Chat resume behavior; route is read at click time") {
    var route = ViewRoute.Graph
    val effects = ListBuffer.empty[String]
    val navigation = new SidebarSessionNavigation(
      () => route,
      id => effects += s"resume:$id",
      () => effects += "open-chat",
      id => effects += s"graph:$id",
      () => effects += "close-sidebar"
    )
    ViewRoute.values.filterNot(_ == ViewRoute.Graph).foreach { current =>
      route = current
      effects.clear()
      navigation.select("existing")
      assertEquals(effects.toList, List("resume:existing", "open-chat"))
    }
    route = ViewRoute.Graph
    effects.clear()
    navigation.select("existing")
    assertEquals(effects.toList, List("graph:existing", "close-sidebar"))
  }
}
