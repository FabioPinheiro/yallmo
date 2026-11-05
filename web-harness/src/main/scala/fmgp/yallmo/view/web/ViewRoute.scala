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
  case Scalamode extends ViewRoute("Scalamode")
  case Providers extends ViewRoute("Providers")

  def command: ControlAction = this match {
    case Sessions(_) => ControlAction.Sessions
    case Chat        => ControlAction.Chat
    case Messages    => ControlAction.Messages
    case Graph       => ControlAction.Graph
    case Agents      => ControlAction.AgentRegistry
    case Blobs       => ControlAction.Blobs
    case Scalamode   => ControlAction.Scalamode
    case Providers   => ControlAction.Providers
  }

  def href: String = this match {
    case Sessions(Some(id)) => SessionRoute.href(id)
    case Sessions(None)     => "#/sessions"
    case Chat               => "#/chat"
    case Messages           => "#/messages"
    case Graph              => "#/graph"
    case Agents             => "#/agents"
    case Blobs              => "#/blobs"
    case Scalamode          => "#/scalamode"
    case Providers          => "#/providers"
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
      ViewRoute.Blobs,
      ViewRoute.Scalamode,
      ViewRoute.Providers
    )

  def parse(hash: String): ViewRoute = hash match {
    case "#/chat"      => ViewRoute.Chat
    case "#/messages"  => ViewRoute.Messages
    case "#/graph"     => ViewRoute.Graph
    case "#/agents"    => ViewRoute.Agents
    case "#/blobs"     => ViewRoute.Blobs
    case "#/scalamode" => ViewRoute.Scalamode
    case "#/providers" => ViewRoute.Providers
    case _             => ViewRoute.Sessions(SessionRoute.parse(hash))
  }
}
