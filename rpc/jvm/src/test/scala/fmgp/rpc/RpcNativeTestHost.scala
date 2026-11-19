package fmgp.rpc

import zio.*

/** Trusted test child only: stdout is a private bootstrap pipe, stdin owns the lifetime. No listener in production JS.
  */
object RpcNativeTestHost extends ZIOAppDefault {
  override val bootstrap = Runtime.removeDefaultLoggers
  def run = ZIO.scoped {
    for {
      args <- getArgs
      bootstrap <-
        if args.contains("reply-before-close") then RpcReplyBeforeCloseHost.start()
        else if args.contains("absent-protocol") then RpcReplyBeforeCloseHost.start(protocol = None)
        else if args.contains("wrong-protocol") then RpcReplyBeforeCloseHost.start(protocol = Some("not-offered"))
        else if args.contains("oversized-message") then
          RpcReplyBeforeCloseHost.start(messages = Chunk(RpcSocket.Message.Text("x" * 1024)))
        else if args.contains("oversized-binary") then
          RpcReplyBeforeCloseHost.start(messages = Chunk(RpcSocket.Message.Binary(Chunk.fill(1024)(1.toByte))))
        else
          for {
            server <- RpcTestServer.start(RpcWebSocketClient.Limits(maxFrameBytes = 1024, maxChunkElements = 64))
            token <- server.rpc.registerScoped(RpcStreamFixtures.rpc.bind(new RpcStreamFixtures.Service))
          } yield (server.endpoint, token)
      _ <- Console.printLine(bootstrap._1)
      _ <- Console.printLine(bootstrap._2.exportSecret)
      _ <- Console.readLine.orElseSucceed("")
    } yield ()
  }
}
