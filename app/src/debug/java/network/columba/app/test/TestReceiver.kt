package network.columba.app.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import network.columba.app.BuildConfig
import network.columba.app.rns.api.model.DeliveryMethod

/**
 * Debug-only BroadcastReceiver that exposes the [TestController] surface
 * to `adb shell am broadcast`. All 17 manifest actions are routed; see
 * the `when` block below.
 *
 * Action contract (one action per row; reply lines under
 * [TestController.LOGCAT_TAG] tag, format `event=… key=…`):
 *
 * Any handler may also emit a catch-all
 * `launch_err type=<ExClass> msg=<…>` line if an unhandled exception
 * escapes a `scope.launch` — the harness should treat this as a hard
 * failure for whichever command was in flight.
 *
 *   network.columba.test.GET_DEST                     -> dest=<hex> | dest_err reason=not_ready
 *   network.columba.test.HAS_PATH       --es to       -> has_path to=<hex> result=0|1
 *   network.columba.test.SEND_DIRECT    --es to,text  -> msg_sent id=<hex> method=DIRECT
 *   network.columba.test.SEND_OPP       --es to,text  -> msg_sent id=<hex> method=OPPORTUNISTIC
 *   network.columba.test.SEND_PROP      --es to,text  -> msg_sent id=<hex> method=PROPAGATED
 *   network.columba.test.GET_MSG_STATE  --es id       -> msg_state id=<hex> state=<…> | msg_state_err reason=missing_id
 *   network.columba.test.GET_RX                       -> N×rx_msg source=drain lines + rx_drain count=N
 *
 * NOTE: `rx_msg` lines also stream live from the observer at message
 * arrival, tagged `source=stream`. A harness that only cares about
 * "any delivery" can match the bare `rx_msg from=… id=… content=…`
 * pattern (the `source=` token sits between `rx_msg` and `from=`).
 * To count exactly once, pin to one source via `rx_msg source=stream`
 * or `rx_msg source=drain`. All values are escape()'d — whitespace
 * becomes a Unicode Control-Picture sentinel so values are always
 * single tokens.
 *   network.columba.test.RX_CLEAR                     -> rx_cleared
 *   network.columba.test.ANNOUNCE                     -> announced dest=<hex> | announce_err …
 *   network.columba.test.LIST_INTERFACES              -> N×interface lines + interface_list_done count=N
 *   network.columba.test.DISABLE_ALL_INTERFACES       -> interfaces_disabled count=N applied=true
 *   network.columba.test.DISABLE_INTERFACE  --es name -> interface_disabled name=<…> id=<n> applied=true
 *   network.columba.test.ENABLE_INTERFACE   --es name -> interface_enabled  name=<…> id=<n> applied=true
 *   network.columba.test.ADD_TCP_CLIENT     --es name,host,port -> interface_added name=<…> id=<n> type=TCPClient … applied=true
 *   network.columba.test.REMOVE_INTERFACE   --es name -> interface_removed name=<…> id=<n> applied=true
 *   network.columba.test.SET_PROP_NODE      --es hex  -> prop_node_set hex=<…> | prop_node_err …
 *   network.columba.test.SYNC_PROP                    -> prop_sync_started state=<n> messages_received=<n>
 *   network.columba.test.SEND_IMAGE  --es to,text,path,fmt          -> img_sent id=<hex> | img_send_err …
 *   network.columba.test.SEND_FILE   --es to,text,path,name         -> file_sent id=<hex> | file_send_err …
 *   network.columba.test.SEND_AUDIO  --es to,text,path,codec        -> audio_sent id=<hex> | audio_send_err …
 *   network.columba.test.SEND_ICON   --es to,text,icon,fg,bg        -> icon_sent id=<hex> | icon_send_err …
 *
 * Dispatch happens off the main thread via [TestController]'s coroutine
 * scope, so we don't need [BroadcastReceiver.goAsync]; the broadcast
 * returns immediately and the controller logs the result whenever it's
 * ready. The harness blocks on the logcat reply, not on the broadcast.
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // ── Threat model + why there's no caller auth here ──
        //
        // BroadcastReceiver fundamentally has no reliable way to identify
        // the broadcaster from `onReceive`. `Binder.getCallingUid()` only
        // returns a meaningful UID inside an active Binder transaction;
        // broadcasts are dispatched via main-thread Handler messages with
        // no active transaction, so the API returns the receiver's OWN
        // UID. `intent.getPackage()` is sender-set and trivially spoofed.
        // `android:permission` with `protectionLevel="signature"` would
        // block foreign apps but ALSO blocks `adb shell am broadcast`
        // (verified empirically — shell UID does NOT bypass app-defined
        // signature permissions on modern Android).
        //
        // The receiver therefore IS exposed to every app on the device,
        // and any of them can drive the test surface. We accept this
        // because the surface ships ONLY in debug builds (this file
        // lives under `app/src/debug/`, never compiled into release):
        //   - The runtime assertion below crashes the app at receive time
        //     if this ever runs in a non-debug build, defense-in-depth
        //     against an accidental ProGuard / build-variant misconfig.
        //   - Debug builds are dev-only, run on dev-controlled devices.
        //   - To exploit, an attacker would need to install a malicious
        //     app on a dev's debug-build phone — at which point they
        //     already control the device.
        //
        // If a stronger threat model is ever needed (CI farm with shared
        // phones, etc.), this should migrate from BroadcastReceiver to a
        // bound Service. The Service's IBinder.onTransact() runs inside
        // the Binder transaction, so `Binder.getCallingUid()` works
        // there and a real shell-UID gate becomes possible.
        check(BuildConfig.DEBUG) {
            "TestReceiver must never run in release builds — this is a " +
                "debug-only test surface; non-debug invocation indicates " +
                "build-variant or ProGuard misconfiguration"
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

            "network.columba.test.SEND_LOCATION" -> {
                val to = intent.getStringExtra("to") ?: ""
                val json = intent.getStringExtra("json") ?: ""
                if (to.isEmpty() || json.isEmpty()) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "loc_send_err reason=missing_args to=$to bytes=${json.length}",
                    )
                } else {
                    TestController.handleSendLocation(app, to, json)
                }
            }

            "network.columba.test.SEND_IMAGE" -> {
                val to = intent.getStringExtra("to") ?: ""
                val text = intent.getStringExtra("text") ?: ""
                val path = intent.getStringExtra("path") ?: ""
                val fmt = intent.getStringExtra("fmt") ?: "png"
                if (to.isEmpty() || path.isEmpty()) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "img_send_err reason=missing_args to=$to path=$path",
                    )
                } else {
                    TestController.handleSendImage(app, to, text, path, fmt)
                }
            }

            "network.columba.test.SEND_FILE" -> {
                val to = intent.getStringExtra("to") ?: ""
                val text = intent.getStringExtra("text") ?: ""
                val path = intent.getStringExtra("path") ?: ""
                val name = intent.getStringExtra("name") ?: ""
                if (to.isEmpty() || path.isEmpty() || name.isEmpty()) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "file_send_err reason=missing_args to=$to path=$path name=$name",
                    )
                } else {
                    TestController.handleSendFile(app, to, text, path, name)
                }
            }

            "network.columba.test.SEND_AUDIO" -> {
                val to = intent.getStringExtra("to") ?: ""
                val text = intent.getStringExtra("text") ?: ""
                val path = intent.getStringExtra("path") ?: ""
                val codec = intent.getStringExtra("codec")?.toIntOrNull() ?: 0
                if (to.isEmpty() || path.isEmpty()) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "audio_send_err reason=missing_args to=$to path=$path",
                    )
                } else {
                    TestController.handleSendAudio(app, to, text, path, codec)
                }
            }

            "network.columba.test.SEND_ICON" -> {
                val to = intent.getStringExtra("to") ?: ""
                val text = intent.getStringExtra("text") ?: ""
                val icon = intent.getStringExtra("icon") ?: ""
                val fg = intent.getStringExtra("fg") ?: ""
                val bg = intent.getStringExtra("bg") ?: ""
                if (to.isEmpty() || icon.isEmpty() || fg.isEmpty() || bg.isEmpty()) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "icon_send_err reason=missing_args to=$to icon=$icon fg=$fg bg=$bg",
                    )
                } else {
                    TestController.handleSendIcon(app, to, text, icon, fg, bg)
                }
            }

            "network.columba.test.GET_MSG_STATE" -> {
                val id = intent.getStringExtra("id") ?: ""
                if (id.isEmpty()) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "msg_state_err reason=missing_id",
                    )
                } else {
                    TestController.handleGetMsgState(app, id)
                }
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
                if (name.isEmpty()) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "interface_disable_err reason=missing_name",
                    )
                } else {
                    TestController.handleSetInterfaceEnabled(app, name, enabled = false)
                }
            }

            "network.columba.test.ENABLE_INTERFACE" -> {
                val name = intent.getStringExtra("name") ?: ""
                if (name.isEmpty()) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "interface_enable_err reason=missing_name",
                    )
                } else {
                    TestController.handleSetInterfaceEnabled(app, name, enabled = true)
                }
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
                if (name.isEmpty()) {
                    Log.i(
                        TestController.LOGCAT_TAG,
                        "interface_remove_err reason=missing_name",
                    )
                } else {
                    TestController.handleRemoveInterface(app, name)
                }
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
        // Optional flag — when present + true, hands `tryPropagationOnFail`
        // through to the LXMF send so a failed DIRECT/OPPORTUNISTIC retries
        // as PROPAGATED (Sideband pattern). String form ("true"/"false") so
        // `adb shell am broadcast --es try_prop true` works without --ez.
        val tryProp = intent.getStringExtra("try_prop")?.equals("true", ignoreCase = true) == true
        if (to.isEmpty() || text.isEmpty()) {
            Log.i(
                TestController.LOGCAT_TAG,
                "msg_send_err method=$method reason=missing_extras to=$to text_len=${text.length}",
            )
            return
        }
        TestController.handleSend(app, method, to, text, tryPropagationOnFail = tryProp)
    }
}
