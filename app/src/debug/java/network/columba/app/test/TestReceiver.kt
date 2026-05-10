package network.columba.app.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import android.util.Log
import network.columba.app.reticulum.protocol.DeliveryMethod

/**
 * Debug-only BroadcastReceiver that exposes the [TestController] surface
 * to `adb shell am broadcast`. All 17 manifest actions are routed; see
 * the `when` block below.
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
 *   network.columba.test.ANNOUNCE                     -> announced dest=<hex> | announce_err …
 *   network.columba.test.LIST_INTERFACES              -> N×interface lines + interface_list_done count=N
 *   network.columba.test.DISABLE_ALL_INTERFACES       -> interfaces_disabled count=N applied=true
 *   network.columba.test.DISABLE_INTERFACE  --es name -> interface_set_enabled name=<…> enabled=false applied=true
 *   network.columba.test.ENABLE_INTERFACE   --es name -> interface_set_enabled name=<…> enabled=true applied=true
 *   network.columba.test.ADD_TCP_CLIENT     --es name,host,port -> interface_added name=<…> id=<n> type=TCPClient … applied=true
 *   network.columba.test.REMOVE_INTERFACE   --es name -> interface_removed name=<…> applied=true
 *   network.columba.test.SET_PROP_NODE      --es hex  -> prop_node_set hex=<…> | prop_node_err …
 *   network.columba.test.SYNC_PROP                    -> prop_sync_started state=<n> messages_received=<n>
 *
 * Dispatch happens off the main thread via [TestController]'s coroutine
 * scope, so we don't need [BroadcastReceiver.goAsync]; the broadcast
 * returns immediately and the controller logs the result whenever it's
 * ready. The harness blocks on the logcat reply, not on the broadcast.
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // Caller-UID gate. The receiver MUST stay `exported="true"` so that
        // `adb shell am broadcast` (shell UID 2000) can drive it, but that
        // also exposes it to every installed third-party app. Filter here
        // instead of via `android:permission` (which blocks the shell UID
        // on modern Android too — verified empirically). Allowed callers:
        //   - SHELL_UID (2000): adb shell, the only intended driver
        //   - ROOT_UID (0): rooted-phone shell or `adb root`
        //   - our own UID: defensive — same-process self-broadcast (if any)
        // Anything else: silently drop with a one-line audit log.
        val callingUid = Binder.getCallingUid()
        val ownUid = Process.myUid()
        if (callingUid != Process.SHELL_UID &&
            callingUid != Process.ROOT_UID &&
            callingUid != ownUid
        ) {
            Log.w(
                TestController.LOGCAT_TAG,
                "rx_broadcast_rejected action=$action calling_uid=$callingUid",
            )
            return
        }
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

            "network.columba.test.SET_PROP_NODE" -> {
                val hex = intent.getStringExtra("hex") ?: ""
                TestController.handleSetPropNode(app, hex)
            }

            "network.columba.test.SYNC_PROP" ->
                TestController.handleSyncProp(app)

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
