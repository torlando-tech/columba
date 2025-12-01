package com.lxmf.messenger.service

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

        /**
         * StateFlow emitting the destination hash of the currently active conversation,
         * or null if no conversation is active.
         */
        val activeConversation: StateFlow<String?> = _activeConversation.asStateFlow()

        /**
         * Sets the currently active conversation.
         *
         * @param destinationHash The destination hash of the conversation being viewed,
         *                        or null if no conversation is active (user navigated away)
         */
        fun setActive(destinationHash: String?) {
            _activeConversation.value = destinationHash
        }
    }
