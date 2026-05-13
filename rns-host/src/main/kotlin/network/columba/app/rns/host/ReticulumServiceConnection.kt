package network.columba.app.rns.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.ipc.IRnsBackend
import network.columba.app.rns.ipc.RnsBackendClient

/**
 * UI-side connection driver for the `:reticulum`-process [ReticulumService].
 *
 * Wraps the Android [ServiceConnection] lifecycle into a coroutine-friendly
 * [Flow] that emits a connected [RnsBackend] once the binder is live. Each
 * `onServiceConnected` callback rebuilds the [RnsBackendClient] off the new
 * binder so re-binds (process restarts, ANR recoveries) surface as fresh
 * emissions to downstream collectors. `onServiceDisconnected` does NOT
 * complete the flow — Android delivers a follow-up `onServiceConnected`
 * automatically, so a transient process loss looks like a single backend
 * outage rather than a stream tear-down.
 *
 * The Hilt module in `:app` consumes this in A.10 to provide a
 * `Flow<RnsBackend>` (or a single-shot bound instance, depending on the
 * binding semantics chosen there) to the UI's sub-interface providers.
 *
 * For Phase A.7 the underlying [ReticulumService.onBind] still returns the
 * legacy local [binder.ReticulumServiceBinder] (a liveness handle, not an
 * `IRnsBackend.Stub`). Until A.8 swaps that binder for the AIDL stub,
 * [bind] will short-circuit with `IllegalStateException` on first emit —
 * which is fine because [ReticulumServiceConnection] is not wired into
 * `ReticulumModule` until A.10.
 */
object ReticulumServiceConnection {
    private const val TAG = "ReticulumServiceConn"

    /** Action for the explicit-Intent binding. Mirrors [ReticulumService.ACTION_START]
     *  in shape: a constant `:reticulum`-process action. The bind path uses
     *  an explicit component name, so the action is informational metadata.
     */
    const val ACTION_BIND = "network.columba.app.rns.host.BIND"

    /**
     * Build the explicit [Intent] used by [Context.bindService]. Targets the
     * `:reticulum`-process [ReticulumService] by class — manifest merger pulls
     * the declaration into `:app` so the resolver can locate it.
     */
    fun intentFor(context: Context): Intent =
        Intent(context, ReticulumService::class.java).apply {
            action = ACTION_BIND
        }

    /**
     * Bind to [ReticulumService] and emit a connected [RnsBackend] each time
     * the service becomes ready. The flow terminates only when the collector
     * is cancelled; transient `onServiceDisconnected` events are tolerated
     * because Android delivers a follow-up `onServiceConnected` on recovery.
     *
     * @param context any [Context] (typically the application context) — used
     *   for [Context.bindService] and [Context.unbindService].
     * @param scope coroutine scope passed through to [RnsBackendClient] for
     *   its observer plumbing. Should outlive the binding (typically the
     *   `ApplicationScope`).
     */
    fun bind(context: Context, scope: CoroutineScope): Flow<RnsBackend> = callbackFlow {
        var connectJob: Job? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val remote = service?.let { IRnsBackend.Stub.asInterface(it) }
                if (remote == null) {
                    Log.w(TAG, "onServiceConnected delivered a binder that is not IRnsBackend")
                    return
                }
                connectJob?.cancel()
                connectJob = scope.launch {
                    try {
                        val client = RnsBackendClient(scope)
                        client.connect(remote)
                        trySend(client)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to connect RnsBackendClient", e)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Service disconnected; awaiting reconnect")
                connectJob?.cancel()
                connectJob = null
            }

            override fun onNullBinding(name: ComponentName?) {
                Log.w(TAG, "onNullBinding from $name — backend not yet AIDL-capable")
            }
        }

        val bound =
            try {
                context.bindService(intentFor(context), connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                Log.e(TAG, "bindService denied by permissions", e)
                false
            }

        if (!bound) {
            Log.e(TAG, "bindService returned false — channel will not emit")
        }

        awaitClose {
            connectJob?.cancel()
            try {
                context.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // The service was never successfully bound or already unbound. Safe to ignore.
            }
        }
    }
}
