package network.columba.app

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether [MainActivity] is currently visible (STARTED or higher) and
 * serializes foreground-ownership transfer against the background presenter's
 * check-then-post.
 *
 * Incoming-call presentation has two owners: [network.columba.app.service.IncomingCallPresenter]
 * (background: full-screen-intent notification) and the in-app incoming call screen
 * inside MainActivity (foreground). While MainActivity is visible it owns the
 * presentation: the presenter must not post the background notification, and a
 * post that lands while the main UI is visible would duplicate the in-app call
 * screen or undo the cancel MainActivity made when it took over presentation.
 *
 * Serialisation strategy: single-thread confinement, not locking. Every
 * [claimForeground], [releaseForeground] and [postWhileBackground] call runs its
 * state check and notification operation on the main thread (enforced by
 * [requireMainThread], not just documented), so the check-then-post sequences are
 * atomic by construction: there is no second thread to interleave against.
 *
 * This deliberately replaces an earlier `synchronized` monitor: that lock was
 * taken by MainActivity on the main thread (flag flip + cancel) and by the
 * presenter on Dispatchers.IO (flag check + post), i.e. the main thread blocked
 * on whichever binder call (`notify`) the IO thread happened to hold the lock
 * across - an ANR hazard traded for a sub-frame visual residual. With main
 * [postWhileBackground] enqueues onto the main looper instead of blocking the
 * caller; the two notification operations keep their exact order relative to
 * each other either way (post-then-cancel or skip-then-claim), so the visible
 * outcome is unchanged. Both notification calls are fire-and-forget
 * (`NotificationManager.notify`/`cancel`), so confining them to the main
 * thread never parks it on a result.
 */
@Singleton
class MainActivityVisibility @Inject constructor() {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /**
     * Presenter: run [block] (the notification post) only while the foreground
     * has not been claimed; the flag check and the post execute on the main
     * thread as one sequence (see class KDoc), so they are atomic against
     * [claimForeground].
     *
     * [block] runs on the main thread and must not block. Safe to call from any
     * thread (the presenter collects on Dispatchers.IO).
     */
    fun postWhileBackground(block: () -> Unit) {
        AppMainThread.post {
            if (_visible.value) {
                return@post
            }
            block()
        }
    }

    /**
     * MainActivity: claim foreground ownership. Must be called on the main
     * thread (activity onStart). Flips the flag and runs [onVisible]
     * (MainActivity's background-notification cancel) as one main-thread
     * sequence, so a presenter post enqueued before the claim is either
     * skipped (claim observed) or removed by this cancel, never left behind.
     */
    fun claimForeground(onVisible: () -> Unit) {
        requireMainThread()
        _visible.value = true
        onVisible()
    }

    /**
     * MainActivity: release foreground ownership (activity STOPPED).
     *
     * [changingConfigurations] (MainActivity's `isChangingConfigurations`) keeps
     * the claim across a rotation: releasing and re-claiming would open a
     * visible=false window that the background presenter could post into
     * mid-rotation. The claim is retained until the restarted activity runs
     * [claimForeground] again or a non-config STOP releases it. An activity
     * finishing (isFinishing, never a configuration change) always releases.
     */
    fun releaseForeground(changingConfigurations: Boolean = false) {
        requireMainThread()
        if (changingConfigurations) {
            return
        }
        _visible.value = false
    }

    private fun requireMainThread() {
        check(AppMainThread.isMainThread()) {
            "foreground ownership transfer must run on the main thread"
        }
    }
}
