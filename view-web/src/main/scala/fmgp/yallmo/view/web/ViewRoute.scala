package fmgp.yallmo.view.web

/** Small hash router: ordinary links provide bookmarks and back/forward without a routing dependency. */
enum ViewRoute(val title: String) {
  case Sessions(id: Option[String]) extends ViewRoute("Sessions")
  case Messages extends ViewRoute("All messages")
  case Graph extends ViewRoute("Conversation graph")
  case Agents extends ViewRoute("Agents")
  case Blobs extends ViewRoute("Blobs")

  def href: String = this match {
    case Sessions(Some(id)) => SessionRoute.href(id)
    case Sessions(None)     => "#/sessions"
    case Messages           => "#/messages"
    case Graph              => "#/graph"
    case Agents             => "#/agents"
    case Blobs              => "#/blobs"
  }
}

object ViewRoute {
  val tabs: List[ViewRoute] =
    List(ViewRoute.Sessions(None), ViewRoute.Messages, ViewRoute.Graph, ViewRoute.Agents, ViewRoute.Blobs)

  def parse(hash: String): ViewRoute = hash match {
    case "#/messages" => ViewRoute.Messages
    case "#/graph"    => ViewRoute.Graph
    case "#/agents"   => ViewRoute.Agents
    case "#/blobs"    => ViewRoute.Blobs
    case _            => ViewRoute.Sessions(SessionRoute.parse(hash))
  }
}
