package network.columba.app.audio

import network.columba.app.di.ApplicationScope
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallMicrophoneAdmissionCoordinator
    @Inject
    constructor(
        private val microphoneArbiter: MicrophoneAdmissionArbiter,
        private val telephony: RnsTelephony,
        @ApplicationScope applicationScope: CoroutineScope,
    ) {
        private val lock = Any()
        private var callLease: MicrophoneAdmissionArbiter.Lease? = null
        private var outgoingAdmissionPending = false

        init {
            applicationScope.launch {
                telephony.callState.collect(::reconcile)
            }
        }

        fun tryAcquireForOutgoing(): Boolean =
            synchronized(lock) {
                if (callLease != null) return@synchronized false
                val lease = microphoneArbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL)
                    ?: return@synchronized false
                callLease = lease
                outgoingAdmissionPending = true
                true
            }

        fun markOutgoingStarted() {
            synchronized(lock) {
                outgoingAdmissionPending = false
            }
        }

        fun releaseFailedOutgoing() {
            synchronized(lock) {
                outgoingAdmissionPending = false
                if (!callUsesMicrophone(telephony.callState.value)) releaseLocked()
            }
        }

        fun ensureCallAdmission(): Boolean =
            synchronized(lock) {
                callLease != null ||
                    microphoneArbiter.adoptOrAcquire(MicrophoneAdmissionArbiter.Owner.CALL)
                        ?.also { callLease = it } != null
            }

        private fun reconcile(state: CallState) {
            synchronized(lock) {
                if (callUsesMicrophone(state)) {
                    if (callLease == null) {
                        callLease = microphoneArbiter.adoptOrAcquire(MicrophoneAdmissionArbiter.Owner.CALL)
                    }
                } else if (!outgoingAdmissionPending && !callUsesMicrophone(telephony.callState.value)) {
                    releaseLocked()
                }
            }
        }

        private fun releaseLocked() {
            callLease?.let(microphoneArbiter::release)
            callLease = null
        }

        private fun callUsesMicrophone(state: CallState): Boolean =
            state is CallState.Connecting ||
                state is CallState.Ringing ||
                state is CallState.Incoming ||
                state is CallState.Active
    }
