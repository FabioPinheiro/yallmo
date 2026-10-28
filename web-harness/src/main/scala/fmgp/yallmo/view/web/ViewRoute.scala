package fmgp.yallmo.view.web

import fmgp.yallmo.clientharness.ControlAction

/** Small hash router: ordinary links provide bookmarks and back/forward without a routing dependency. */
enum ViewRoute(val title: String) {
  case Sessions(id: Option[String]) extends ViewRoute("Sessions")
  case Chat extends ViewRoute("Chat")
  case Messages extends ViewRoute("All messages")
  case Graph extends ViewRoute("Conversation graph")
  case Agents extends ViewRoute("Agents")
  case Blobs extends ViewRoute("Blobs")

  def command: ControlAction = this match {
    case Sessions(_) => ControlAction.Sessions
    case Chat        => ControlAction.Chat
    case Messages    => ControlAction.Messages
    case Graph       => ControlAction.Graph
    case Agents      => ControlAction.AgentRegistry
    case Blobs       => ControlAction.Blobs
  }

  def href: String = this match {
    case Sessions(Some(id)) => SessionRoute.href(id)
    case Sessions(None)     => "#/sessions"
    case Chat               => "#/chat"
    case Messages           => "#/messages"
    case Graph              => "#/graph"
    case Agents             => "#/agents"
    case Blobs              => "#/blobs"
  }
}

object ViewRoute {
  val tabs: List[ViewRoute] =
    List(
      ViewRoute.Chat,
      ViewRoute.Sessions(None),
      ViewRoute.Messages,
      ViewRoute.Graph,
      ViewRoute.Agents,
      ViewRoute.Blobs
    )

  def parse(hash: String): ViewRoute = hash match {
    case "#/chat"     => ViewRoute.Chat
    case "#/messages" => ViewRoute.Messages
    case "#/graph"    => ViewRoute.Graph
    case "#/agents"   => ViewRoute.Agents
    case "#/blobs"    => ViewRoute.Blobs
    case _            => ViewRoute.Sessions(SessionRoute.parse(hash))
  }
}
