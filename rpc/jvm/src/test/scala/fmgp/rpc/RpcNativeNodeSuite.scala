package fmgp.rpc

import java.nio.charset.StandardCharsets
import munit.FunSuite
import zio.*

/** Real native Node -> zio-http interoperability; portable client state is exercised separately on both platforms. */
class RpcNativeNodeSuite extends FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(35, "seconds")

  test("native Node negotiates stable-only protocol, unary UTF-8, binary stop-and-wait and cancellation") {
    val script = """
      |const fs = require('node:fs');
      |const [endpoint, ticket] = fs.readFileSync(0, 'utf8').trim().split('\n');
      |const [major,minor] = process.versions.node.split('.').map(Number);
      |if (major < 22 || (major === 22 && minor < 4) || typeof WebSocket !== 'function') process.exit(2);
      |const ws = new WebSocket(endpoint, ['yallmo.rpc.v2', 'yallmo.ticket.' + ticket]);
      |ws.binaryType = 'arraybuffer';
      |const id = '9007199254740993'; // Deliberately outside Number's exact range.
      |let bytes = 0, chunks = 0;
      |const timer = setTimeout(() => process.exit(3), 10000);
      |const send = obj => ws.send(JSON.stringify(obj));
      |ws.onerror = () => process.exit(4);
      |ws.onopen = () => {
      |  if (ws.protocol !== 'yallmo.rpc.v2') process.exit(5);
      |  send({id:'1',service:'test.stream.files',version:1,method:'ping',arguments:['hé🙂']});
      |};
      |ws.onmessage = event => {
      |  if (event.data instanceof ArrayBuffer) {
      |    const view = new DataView(event.data);
      |    if (view.getBigInt64(4).toString() !== id || view.getUint32(0) !== 0x52500242) process.exit(6);
      |    const payload = new Int8Array(event.data, 12);
      |    for (const byte of payload) if (byte !== bytes++ - 128) process.exit(7);
      |    chunks++;
      |    send({id,stream:2,op:'pull'});
      |  } else {
      |    const reply = JSON.parse(event.data);
      |    if (reply.id === '1') {
      |      if (reply.result !== 'hé🙂') process.exit(8);
      |      send({id,stream:2,op:'open',service:'test.stream.files',version:1,method:'readBytes',arguments:['x'],binary:true});
      |    } else if (reply.op === 'opened') {
      |      send({id,stream:2,op:'pull'});
      |    } else if (reply.op === 'end') {
      |      if (bytes !== 256 || chunks < 2) process.exit(9);
      |      send({id:'9007199254740994',stream:2,op:'open',service:'test.stream.files',version:1,method:'readBytes',arguments:['x'],binary:true});
      |    } else if (reply.id === '9007199254740994' && reply.op === 'cancelled') {
      |      ws.close(); clearTimeout(timer); console.log('native-rpc-ok');
      |    } else process.exit(10);
      |  }
      |};
      |// Replace the open branch for the second stream: cancel before any pull.
      |const receive = ws.onmessage;
      |ws.onmessage = event => {
      |  if (typeof event.data === 'string') {
      |    const reply = JSON.parse(event.data);
      |    if (reply.id === '9007199254740994' && reply.op === 'opened') {
      |      send({id:reply.id,stream:2,op:'cancel'}); return;
      |    }
      |  }
      |  receive(event);
      |};
      |""".stripMargin
    val effect = ZIO.scoped {
      for {
        server <- RpcTestServer.start(RpcWebSocketClient.Limits(maxFrameBytes = 256, maxChunkElements = 64))
        token <- server.rpc.register(RpcStreamFixtures.rpc.bind(new RpcStreamFixtures.Service))
        process <- ZIO.acquireRelease(ZIO.attemptBlocking {
          val builder = new ProcessBuilder("node", "-e", script)
          builder.environment().remove("NODE_OPTIONS")
          builder.environment().remove("NODE_PATH")
          builder.redirectError(ProcessBuilder.Redirect.DISCARD)
          builder.start()
        })(process => ZIO.attemptBlocking { process.destroyForcibly(); () }.orDie)
        _ <- ZIO.attemptBlocking {
          val input = process.getOutputStream
          input.write(s"${server.endpoint}\n${token.exportSecret}\n".getBytes(StandardCharsets.US_ASCII))
          input.close()
        }
        exit <- ZIO.attemptBlockingInterrupt(process.waitFor())
        output <- ZIO.attemptBlocking(new String(process.getInputStream.readNBytes(100), StandardCharsets.UTF_8))
      } yield (exit, output.trim)
    }
    val result = Unsafe.unsafe { _ ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new Exception("native integration deadline"))(20.seconds))
        .getOrThrowFiberFailure()
    }
    assertEquals(result, (0, "native-rpc-ok"))
  }
}
