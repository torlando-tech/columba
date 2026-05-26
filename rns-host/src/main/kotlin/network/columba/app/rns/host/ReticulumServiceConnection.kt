package network.columba.app.rns.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
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
 * [Flow] that emits a connected [RnsBackend] once the binder is live, plus a
 * sentinel `null` whenever the binder dies. Each `onServiceConnected` callback
 * rebuilds the [RnsBackendClient] off the new binder so re-binds (process
 * restarts, ANR recoveries) surface as fresh emissions to downstream
 * collectors. `onServiceDisconnected` does NOT complete the flow — Android
 * delivers a follow-up `onServiceConnected` automatically — but it DOES emit
 * `null` so that [network.columba.app.rns.host.ipc.BoundRnsBackend]'s
 * `awaitBound()` callers suspend through the gap instead of forwarding a
 * call to a dead binder (DeadObjectException → BackendNotReady at the call
 * site). This is the punch-list-item-10 fix: without the null sentinel,
 * Apply & Restart's `rnsCore.initialize(config)` immediately after the new
 * `:reticulum` is started fires against the previous (dead) binder reference
 * still cached in the `StateFlow<RnsBackend?>`.
 *
 * Consumed by [network.columba.app.rns.host.ipc.BoundRnsBackend] (A.10),
 * which `stateIn`s this nullable flow into a `StateFlow<RnsBackend?>` and
 * hands the same reference to each `BoundRns*` sub-wrapper for republishing.
 * [network.columba.app.rns.host.di.ProcessAwareBackendModule] decides per
 * process whether to instantiate `BoundRnsBackend` (UI / TEST) or hand back
 * the flavor-local backend (`:reticulum`).
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
     * the service becomes ready, or `null` whenever the binder dies. The flow
     * terminates only when the collector is cancelled; transient
     * `onServiceDisconnected` events are tolerated because Android delivers
     * a follow-up `onServiceConnected` on recovery, but the null sentinel
     * forces `awaitBound()` callers to wait for that recovery rather than
     * invoking a dead binder reference.
     *
     * @param context any [Context] (typically the application context) — used
     *   for [Context.bindService] and [Context.unbindService].
     * @param scope coroutine scope passed through to [RnsBackendClient] for
     *   its observer plumbing. Should outlive the binding (typically the
     *   `ApplicationScope`).
     */
    fun bind(context: Context, scope: CoroutineScope): Flow<RnsBackend?> = callbackFlow {
        // connectJob is mutated from the main-thread connection callbacks AND
        // the binder-thread death recipient, so it needs the same atomic
        // happens-before treatment as the link refs below (getAndSet swaps the
        // job and cancels the previous one in one step).
        val connectJob = AtomicReference<Job?>(null)
        // The binder we currently hold a death link on, plus its recipient, so
        // the link can be dropped on reconnect/teardown (avoids leaking
        // recipients across rebinds). AtomicReference, not plain var: written on
        // the main-thread connection callbacks but read from the binder-thread
        // death recipient's identity guard, so it needs a happens-before edge.
        val linkedBinder = AtomicReference<IBinder?>(null)
        val deathRecipient = AtomicReference<IBinder.DeathRecipient?>(null)

        fun unlinkDeath() {
            val binder = linkedBinder.getAndSet(null)
            val recipient = deathRecipient.getAndSet(null)
            if (binder != null && recipient != null) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
            }
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    Log.w(TAG, "onServiceConnected delivered a null binder")
                    return
                }
                val remote = IRnsBackend.Stub.asInterface(service)
                if (remote == null) {
                    Log.w(TAG, "onServiceConnected delivered a binder that is not IRnsBackend")
                    return
                }
                // Link a death recipient to the new binder. binderDied() fires
                // promptly off a binder thread — often before the main-thread
                // onServiceDisconnected lands, which lags on low-end devices (the
                // Sentry COLUMBA-AZ repro device: 2 cores, low memory). It only
                // emits null so awaitBound() callers suspend through the gap and
                // stop invoking the dead binder; the rebind itself stays owned by
                // onServiceDisconnected/onBindingDied to avoid a double-rebind.
                unlinkDeath()
                val recipient = IBinder.DeathRecipient {
                    // Identity guard: ignore a stale fire for a binder we've
                    // already replaced on a newer onServiceConnected.
                    if (linkedBinder.get() === service) {
                        Log.w(TAG, "binderDied — :reticulum process died; emitting null")
                        connectJob.getAndSet(null)?.cancel()
                        trySend(null)
                    }
                }
                // Publish the refs BEFORE linkToDeath: if the binder dies in the
                // window around registration, the recipient's identity guard must
                // already see this binder, or the null emission is silently
                // dropped — defeating the fast path for the very COLUMBA-AZ race.
                linkedBinder.set(service)
                deathRecipient.set(recipient)
                try {
                    service.linkToDeath(recipient, 0)
                } catch (e: RemoteException) {
                    // Binder already dead at link time — the exact race COLUMBA-AZ
                    // hit. Roll back the refs we just published and treat as
                    // disconnected; the rebind path recovers.
                    linkedBinder.set(null)
                    deathRecipient.set(null)
                    Log.w(TAG, "linkToDeath failed; binder already dead", e)
                    trySend(null)
                    return
                }
                val job = scope.launch {
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
                connectJob.getAndSet(job)?.cancel()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Service disconnected; awaiting reconnect (emitting null)")
                unlinkDeath()
                connectJob.getAndSet(null)?.cancel()
                // Punch-list item 10: emit null so downstream awaitBound() suspends
                // until a fresh onServiceConnected lands, instead of resolving
                // immediately against the dead binder still cached in the StateFlow.
                trySend(null)
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.w(TAG, "onBindingDied from $name — binding will need rebind")
                unlinkDeath()
                connectJob.getAndSet(null)?.cancel()
                trySend(null)
                // Re-bind so Android delivers a fresh onServiceConnected once the
                // next :reticulum process is up. Without this, onBindingDied is
                // a terminal event for the original bind() handle.
                try {
                    context.unbindService(this)
                } catch (e: IllegalArgumentException) {
                    // Already unbound — proceed with rebind. Log at DEBUG so
                    // the swallowed exception isn't truly invisible if it
                    // surfaces during diagnosis.
                    Log.d(TAG, "unbindService: already unbound, proceeding with rebind: ${e.message}")
                }
                try {
                    context.bindService(intentFor(context), this, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    Log.e(TAG, "rebindService denied by permissions", e)
                }
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
            connectJob.getAndSet(null)?.cancel()
            unlinkDeath()
            try {
                context.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // The service was never successfully bound or already unbound. Safe to ignore.
            }
        }
    }
}
