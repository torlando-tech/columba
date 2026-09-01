package network.columba.app

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether [MainActivity] is currently visible (STARTED or higher).
 *
 * Incoming-call presentation has two owners: [network.columba.app.service.IncomingCallPresenter]
 * (background: full-screen-intent notification) and the in-app incoming call screen
 * inside MainActivity (foreground). While MainActivity is visible it owns the
 * presentation: the presenter must neither post nor update the background
 * notification. A post that lands while the main UI is visible would duplicate
 * the in-app call screen, or undo the cancel MainActivity made when it took
 * over presentation.
 */
@Singleton
class MainActivityVisibility @Inject constructor() {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun setVisible(isVisible: Boolean) {
        _visible.value = isVisible
    }
}
