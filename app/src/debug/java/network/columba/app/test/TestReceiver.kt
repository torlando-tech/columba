package network.columba.app.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import network.columba.app.reticulum.protocol.DeliveryMethod

/**
 * Debug-only BroadcastReceiver that exposes the [TestController] surface
 * to `adb shell am broadcast`.
 *
 * Action contract (one action per row; reply lines under
 * [TestController.LOGCAT_TAG] tag, format `event=… key=…`):
 *
 *   network.columba.test.GET_DEST                     -> dest=<hex>
 *   network.columba.test.HAS_PATH       --es to       -> has_path to=<hex> result=0|1
 *   network.columba.test.SEND_DIRECT    --es to,text  -> msg_sent id=<hex> method=DIRECT
 *   network.columba.test.SEND_OPP       --es to,text  -> msg_sent id=<hex> method=OPPORTUNISTIC
 *   network.columba.test.SEND_PROP      --es to,text  -> msg_sent id=<hex> method=PROPAGATED
 *   network.columba.test.GET_MSG_STATE  --es id       -> msg_state id=<hex> state=<…>
 *   network.columba.test.GET_RX                       -> N×rx_msg lines + rx_drain count=N
 *   network.columba.test.RX_CLEAR                     -> rx_cleared
 *
 * Stage 2+ actions (interfaces / propagation) are not yet routed —
 * harness will receive nothing for them until added.
 *
 * Dispatch happens off the main thread via [TestController]'s coroutine
 * scope, so we don't need [BroadcastReceiver.goAsync]; the broadcast
 * returns immediately and the controller logs the result whenever it's
 * ready. The harness blocks on the logcat reply, not on the broadcast.
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        Log.i(TestController.LOGCAT_TAG, "rx_broadcast action=$action")
        when (action) {
            "network.columba.test.GET_DEST" ->
                TestController.handleGetDest(app)

            "network.columba.test.HAS_PATH" -> {
                val to = intent.getStringExtra("to") ?: ""
                TestController.handleHasPath(app, to)
            }

            "network.columba.test.SEND_DIRECT" ->
                dispatchSend(app, intent, DeliveryMethod.DIRECT)

            "network.columba.test.SEND_OPP" ->
                dispatchSend(app, intent, DeliveryMethod.OPPORTUNISTIC)

            "network.columba.test.SEND_PROP" ->
                dispatchSend(app, intent, DeliveryMethod.PROPAGATED)

            "network.columba.test.GET_MSG_STATE" -> {
                val id = intent.getStringExtra("id") ?: ""
                TestController.handleGetMsgState(app, id)
            }

            "network.columba.test.GET_RX" ->
                TestController.handleGetRx(app)

            "network.columba.test.RX_CLEAR" ->
                TestController.handleRxClear(app)

            "network.columba.test.ANNOUNCE" ->
                TestController.handleAnnounce(app)

            "network.columba.test.LIST_INTERFACES" ->
                TestController.handleListInterfaces(app)

            "network.columba.test.DISABLE_ALL_INTERFACES" ->
                TestController.handleDisableAllInterfaces(app)

            "network.columba.test.DISABLE_INTERFACE" -> {
                val name = intent.getStringExtra("name") ?: ""
                TestController.handleSetInterfaceEnabled(app, name, enabled = false)
            }

            "network.columba.test.ENABLE_INTERFACE" -> {
                val name = intent.getStringExtra("name") ?: ""
                TestController.handleSetInterfaceEnabled(app, name, enabled = true)
            }

            "network.columba.test.ADD_TCP_CLIENT" -> {
                val name = intent.getStringExtra("name") ?: ""
                val host = intent.getStringExtra("host") ?: ""
                val port = intent.getStringExtra("port")?.toIntOrNull() ?: -1
                if (name.isEmpty() || host.isEmpty() || port !in 1..65535) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "interface_add_err reason=missing_or_invalid_extras " +
                            "name=$name host=$host port=$port",
                    )
                } else {
                    TestController.handleAddTcpClient(app, name, host, port)
                }
            }

            "network.columba.test.REMOVE_INTERFACE" -> {
                val name = intent.getStringExtra("name") ?: ""
                TestController.handleRemoveInterface(app, name)
            }

            else ->
                Log.i(TestController.LOGCAT_TAG, "rx_broadcast_unknown action=$action")
        }
    }

    private fun dispatchSend(
        app: Context,
        intent: Intent,
        method: DeliveryMethod,
    ) {
        val to = intent.getStringExtra("to") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        if (to.isEmpty() || text.isEmpty()) {
            Log.i(
                TestController.LOGCAT_TAG,
                "msg_send_err method=$method reason=missing_extras to=$to text_len=${text.length}",
            )
            return
        }
        TestController.handleSend(app, method, to, text)
    }
}
