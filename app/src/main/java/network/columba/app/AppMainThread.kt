package network.columba.app

import android.os.Handler
import android.os.Looper

/**
 * Minimal seam onto the application main looper for fire-and-forget work that
 * must share a single execution thread with the activity lifecycle callbacks
 * (see [MainActivityVisibility] for why confinement, not locking).
 *
 * A `post`d block runs on the main thread after any already-queued work,
 * preserving enqueue order. Unit tests replace [post] and [isMainThread] with a
 * synchronous fake (the real main looper does not exist on the JVM), the same
 * way `Dispatchers.setMain` swaps the coroutine main dispatcher.
 */
object AppMainThread {
    /** Test seam: replaced wholesale by unit tests; production uses the looper. */
    @Volatile
    var post: ((Runnable) -> Unit) = { runnable -> Handler(Looper.getMainLooper()).post(runnable) }

    /** Test seam: whether the calling thread is the (faked) main thread. */
    @Volatile
    var isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }
}
