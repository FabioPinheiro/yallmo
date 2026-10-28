package fmgp.yallmo.view.web

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.Uint8Array

/** Native capture boundary. Recording setup begins synchronously in the user's click; JS owns tracks/worklet cleanup.
  */
private[web] trait VoiceRecorder {
  def available: Boolean
  def start(
      started: () => Unit,
      completed: (Array[Byte], Boolean) => Unit,
      failed: String => Unit
  ): VoiceRecorder.Handle
}

private[web] object VoiceRecorder {
  trait Handle {
    def stop(): Unit
    def cancel(): Unit
  }

  @js.native
  @JSGlobal("yallmoVoiceRecorder")
  private object Browser extends js.Object {
    def available(): Boolean = js.native
    def start(callbacks: js.Object): NativeHandle = js.native
  }
  @js.native
  private trait NativeHandle extends js.Object {
    def stop(): Unit = js.native
    def cancel(): Unit = js.native
  }

  val browser: VoiceRecorder = new VoiceRecorder {
    def available: Boolean =
      js.typeOf(js.Dynamic.global.yallmoVoiceRecorder) != "undefined" && Browser.available()

    def start(started: () => Unit, completed: (Array[Byte], Boolean) => Unit, failed: String => Unit): Handle = {
      val callbacks = js.Dynamic.literal(
        onStarted = (() => started()): js.Function0[Unit],
        onComplete = (
            (bytes: Uint8Array, limited: Boolean) =>
              completed(Array.tabulate[Byte](bytes.length)(index => bytes(index).toByte), limited)
        ): js.Function2[Uint8Array, Boolean, Unit],
        onError = ((message: String) => failed(message)): js.Function1[String, Unit]
      )
      val handle = Browser.start(callbacks)
      new Handle {
        def stop(): Unit = handle.stop()
        def cancel(): Unit = handle.cancel()
      }
    }
  }
}
