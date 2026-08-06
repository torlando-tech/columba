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

        class Lease internal constructor(
            val owner: Owner,
        )

        private var activeLease: Lease? = null

        @Synchronized
        fun tryAcquire(owner: Owner): Lease? {
            if (activeLease != null) return null
            return Lease(owner).also { activeLease = it }
        }

        @Synchronized
        fun adoptOrAcquire(owner: Owner): Lease? {
            val current = activeLease
            if (current != null) return current.takeIf { it.owner == owner }
            return Lease(owner).also { activeLease = it }
        }

        @Synchronized
        fun release(lease: Lease) {
            if (activeLease === lease) activeLease = null
        }

        @Synchronized
        fun isActive(lease: Lease): Boolean = activeLease === lease

        @Synchronized
        fun currentOwner(): Owner? = activeLease?.owner
    }
