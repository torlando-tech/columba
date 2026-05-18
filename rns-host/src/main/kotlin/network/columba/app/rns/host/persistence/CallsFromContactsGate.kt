package network.columba.app.rns.host.persistence

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.rns.host.di.ServiceDatabaseProvider

/**
 * Decides whether an inbound LXST link request should be silently dropped
 * because the caller is not in the user's contacts list.
 *
 * Both `PythonCallManager` and `NativeCallManager` invoke
 * [shouldSilentlyDrop] from their `onCallerIdentified` callback BEFORE
 * sending `STATUS_RINGING` and BEFORE notifying `Telephone.onIncomingCall`.
 * If this returns `true`, the call manager tears down the link without any
 * signal, leaving the originator with a wait-time timeout that's
 * indistinguishable from "remote went away" — no `STATUS_BUSY`, no
 * `STATUS_REJECTED`, no notification on this device.
 *
 * The lookup walks the same two indices used elsewhere in the persistence
 * layer (see [ServicePersistenceManager.shouldBlockUnknownSender]):
 *
 *   1. `announceDao.getAnnounceByIdentityHash(identityHashHex)` to translate
 *      the inbound RNS identity hash into the destination hash the contacts
 *      table is keyed on. No announce → treat as unknown (drop).
 *   2. `localIdentityDao.getActiveIdentitySync()` to find the current
 *      active local identity. Missing → fail open (allow).
 *   3. `contactDao.contactExists(announce.destinationHash, active.identityHash)`
 *      to confirm the (destination, owner) pair is in the contacts table.
 *
 * Any other exception inside the gate logs and returns `false` (allow). This
 * mirrors the fail-open semantics of `block_unknown_senders` for messages so
 * the toggle can never accidentally brick all inbound calls if a DAO query
 * fails.
 *
 * Hilt-provided as a `@Singleton` in both flavor `HostBackendModule.kt`s.
 */
class CallsFromContactsGate(
    private val context: Context,
    private val settingsAccessor: ServiceSettingsAccessor,
) {
    private companion object {
        const val TAG = "CallsFromContactsGate"
    }

    private val database: ColumbaDatabase by lazy { ServiceDatabaseProvider.getDatabase(context) }
    private val announceDao by lazy { database.announceDao() }
    private val contactDao by lazy { database.contactDao() }
    private val localIdentityDao by lazy { database.localIdentityDao() }

    /**
     * @return `true` → silently tear down the inbound link (don't surface the
     * call to UI, don't send any signal back to the originator).
     * `false` → let the call ring normally.
     *
     * Reads `getAllowCallsFromContactsOnly()` on every call so live UI
     * toggles take effect immediately without requiring any IPC.
     */
    fun shouldSilentlyDrop(identityHashHex: String): Boolean =
        try {
            if (!settingsAccessor.getAllowCallsFromContactsOnly()) {
                false
            } else {
                // RNS reactor / Chaquopy callback thread expects a synchronous
                // decision before STATUS_RINGING or STATUS_AVAILABLE can be
                // sent. Matches the message-side block_unknown_senders gate's
                // sync-on-receive shape.
                runBlocking(Dispatchers.IO) { // THREADING: allowed — inbound-link callback requires synchronous gate decision
                    val normalised = identityHashHex.lowercase()
                    val announce = announceDao.getAnnounceByIdentityHash(normalised)
                    if (announce == null) {
                        Log.d(TAG, "Drop: no announce for $normalised")
                        true
                    } else {
                        val active = localIdentityDao.getActiveIdentitySync()
                        if (active == null) {
                            // No active local identity — fail open. We can't
                            // meaningfully check contacts without an owner.
                            Log.w(TAG, "Allow: no active local identity, contacts check skipped")
                            false
                        } else {
                            val known = contactDao.contactExists(announce.destinationHash, active.identityHash)
                            if (!known) {
                                Log.d(TAG, "Drop: ${announce.destinationHash.take(16)} not a contact of ${active.identityHash.take(16)}")
                            }
                            !known
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fail open — a DAO blowup should not stop all inbound calls.
            Log.w(TAG, "Contact gate failed, allowing call: ${e.message}", e)
            false
        }
}
