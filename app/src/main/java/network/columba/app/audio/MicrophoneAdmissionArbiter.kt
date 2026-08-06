package network.columba.app.audio

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrophoneAdmissionArbiter
    @Inject
    constructor() {
        enum class Owner {
            CALL,
            VOICE_RECORDING,
        }

        private var owner: Owner? = null

        @Synchronized
        fun tryAcquire(requester: Owner): Boolean {
            if (owner != null) return false
            owner = requester
            return true
        }

        @Synchronized
        fun ensureOwned(requester: Owner): Boolean {
            if (owner == null) owner = requester
            return owner == requester
        }

        @Synchronized
        fun release(requester: Owner) {
            if (owner == requester) owner = null
        }

        @Synchronized
        fun isOwnedBy(requester: Owner): Boolean = owner == requester
    }
