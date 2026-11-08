package network.columba.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the currently active (visible) conversation.
 *
 * This is used to suppress notifications for messages arriving in a conversation
 * that the user is currently viewing. This prevents redundant notifications
 * when the message is already visible on screen.
 *
 * Pattern follows Signal and other messaging apps.
 */
@Singleton
class ActiveConversationManager
    @Inject
    constructor() {
        private val _activeConversation = MutableStateFlow<String?>(null)
        private val _activeThread = MutableStateFlow<String?>(null)

        /**
         * StateFlow emitting the destination hash of the currently active conversation,
         * or null if no conversation is active.
         */
        val activeConversation: StateFlow<String?> = _activeConversation.asStateFlow()

        /**
         * Thread shown inside the active conversation, or null for Main. With
         * the thread flag off the app has one flat list, so this stays null and
         * a message from any of the peer's threads counts as on-screen.
         */
        val activeThread: StateFlow<String?> = _activeThread.asStateFlow()

        /**
         * Sets the currently active conversation and the thread it shows.
         *
         * @param destinationHash The destination hash of the conversation being viewed,
         *                        or null if no conversation is active (user navigated away)
         * @param threadIdHex The thread the screen is showing (null = Main)
         */
        fun setActive(
            destinationHash: String?,
            threadIdHex: String? = null,
        ) {
            _activeConversation.value = destinationHash
            _activeThread.value = if (destinationHash == null) null else threadIdHex
        }
    }
