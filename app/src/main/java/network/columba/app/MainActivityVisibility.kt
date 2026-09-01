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
 * The visibility flip and the accompanying notification operations are
 * therefore made atomic against each other: MainActivity claims foreground
 * ownership with [claimForeground] (flag flip plus its cancel in one locked
 * section) and the presenter posts with [postWhileBackground] (flag check plus
 * post in one locked section). Either the claim wins (the presenter's post is
 * skipped) or the post wins (the claim's cancel removes it); no interleaving
 * leaves a post outliving the claim.
 */
@Singleton
class MainActivityVisibility @Inject constructor() {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Serializes ownership claims and background posts (see class KDoc). */
    private val lock = Any()

    /**
     * MainActivity: claim foreground ownership. Flips the flag and runs
     * [onVisible] (MainActivity's background-notification cancel) in one
     * locked section, so a background post can neither observe a stale
     * "not visible" after this claim nor survive its cancel.
     */
    fun claimForeground(onVisible: () -> Unit) {
        synchronized(lock) {
            _visible.value = true
            onVisible()
        }
    }

    /** MainActivity: release foreground ownership (activity STOPPED). */
    fun releaseForeground() {
        synchronized(lock) { _visible.value = false }
    }

    /**
     * Presenter: run [block] (the notification post) only while the foreground
     * has not been claimed; the flag check and the post are one locked section.
     */
    fun postWhileBackground(block: () -> Unit) {
        synchronized(lock) {
            if (_visible.value) {
                return
            }
            block()
        }
    }
}
