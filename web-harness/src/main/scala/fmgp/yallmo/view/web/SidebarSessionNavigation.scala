package fmgp.yallmo.view.web

/** Only existing-session clicks are contextual. New conversation and Unsent draft still explicitly open Chat. */
private[web] final class SidebarSessionNavigation(
    route: () => ViewRoute,
    resumeChat: String => Unit,
    openChat: () => Unit,
    navigateGraph: String => Unit,
    closeSidebar: () => Unit
) {
  def select(id: String): Unit =
    if route() == ViewRoute.Graph then {
      navigateGraph(id)
      closeSidebar()
    } else {
      resumeChat(id)
      openChat()
    }
}
