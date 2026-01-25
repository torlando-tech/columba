package com.lxmf.messenger.ui.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Utility for checking lifecycle state before showing window-based UI components
 * like dialogs and bottom sheets.
 *
 * Window-based popups (AlertDialog, ModalBottomSheet) require a valid window token
 * from the activity. If the activity is being destroyed (finishing, or in CREATED state),
 * attempting to show these components will cause [android.view.WindowManager.BadTokenException].
 *
 * This guard prevents those crashes by checking if the activity is at least STARTED.
 */
object LifecycleGuard {

    /**
     * Checks if the lifecycle owner is in a state where window-based UI can be shown safely.
     *
     * A lifecycle owner is considered "active for windows" when it is at least in the STARTED state.
     * This ensures the activity's window token is still valid for creating popup windows.
     *
     * @param lifecycleOwner The lifecycle owner to check (typically from LocalLifecycleOwner)
     * @return true if it's safe to show dialogs/bottom sheets, false otherwise
     */
    fun isActiveForWindows(lifecycleOwner: LifecycleOwner): Boolean {
        return lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * Checks if the lifecycle is in a state where window-based UI can be shown safely.
     *
     * @param lifecycle The lifecycle to check
     * @return true if it's safe to show dialogs/bottom sheets, false otherwise
     */
    fun isActiveForWindows(lifecycle: Lifecycle): Boolean {
        return lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * Checks if a specific lifecycle state is valid for showing window-based UI.
     *
     * This is useful for unit testing where you want to verify the logic directly.
     *
     * @param state The lifecycle state to check
     * @return true if the state is at least STARTED, false otherwise
     */
    fun isStateActiveForWindows(state: Lifecycle.State): Boolean {
        return state.isAtLeast(Lifecycle.State.STARTED)
    }
}

