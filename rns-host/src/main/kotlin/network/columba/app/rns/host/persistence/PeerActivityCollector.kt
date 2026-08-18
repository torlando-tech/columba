package network.columba.app.rns.host.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import network.columba.app.data.db.entity.PeerActivityType
import network.columba.app.data.model.InterfaceType
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.util.AppDataParser
import network.columba.app.rns.host.util.PeerNameResolver
import org.json.JSONObject

/**
 * One service-lifecycle owner for all protocol events which prove peer activity.
 * It consumes the local backend before IPC, so presence remains correct while
 * the UI process is absent.
 */
internal class PeerActivityCollector(
    private val backend: RnsBackend,
    private val persistence: ServicePersistenceManager,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var collectionJob: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope): Job {
        collectionJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            supervisorScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    backend.lxmf.observeMessages().collect { message ->
                        persistence.persistIncomingMessageActivity(
                            messageHash = message.messageHash,
                            sourceHash = message.sourceHash.toHex(),
                            deliveryMethod = message.deliveryMethod,
                            receivedAt = now(),
                        )
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    backend.core.observeAnnounces().collect(::persistAnnounceEvent)
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    backend.lxmf.observeDeliveryStatus().collect { update ->
                        persistence.persistDeliveryStatus(update.messageHash, update.status)
                        if (PeerActivityPolicy.isVerifiedDeliveryProof(update.status)) {
                            persistence.persistDeliveryProof(update.messageHash, now())
                        }
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    backend.telemetry.locationTelemetryFlow.collect { telemetry ->
                        val sourceHash = telemetry.sourceHash ?: return@collect
                        val eventId =
                            "$sourceHash:${telemetry.ts}:${telemetry.lat.toBits()}:${telemetry.lng.toBits()}:" +
                                "${telemetry.acc.toRawBits()}:${telemetry.cease}"
                        persistence.persistTelemetryActivity(
                            sourceHash = sourceHash,
                            eventId = eventId,
                            receivedAt = now(),
                            isDirect = telemetry.isDirect,
                        )
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    backend.transportAdmin.reactionReceivedFlow.collect { payload ->
                        runCatching { JSONObject(payload) }.getOrNull()?.let { reaction ->
                            val sourceHash = reaction.optString("source_hash")
                            val target = reaction.optString("reaction_to")
                            val emoji = reaction.optString("emoji")
                            val protocolTimestamp = reaction.optLong("timestamp")
                            if (sourceHash.isNotBlank() && target.isNotBlank() && emoji.isNotBlank()) {
                                persistence.persistReactionActivity(
                                    eventId = "$sourceHash:$target:$emoji:$protocolTimestamp",
                                    sourceHash = sourceHash,
                                    receivedAt = now(),
                                )
                            }
                        }
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    backend.core.observeLinks().collect { event ->
                        if (event is LinkEvent.Established) {
                            persistence.recordPeerActivity(
                                destinationHash = event.link.destination.hexHash,
                                activityType = PeerActivityType.LINK,
                                receivedAt = now(),
                            )
                        }
                    }
                }
            }
        }.also { collectionJob = it }
    }

    private suspend fun persistAnnounceEvent(announce: AnnounceEvent) {
        val destinationHash = announce.destinationHash.toHex()
        val publicKey = announce.identity.publicKey
        val parsedName =
            announce.displayName
                ?: announce.appData?.let {
                    AppDataParser.parseDisplayName(it, announce.aspect.orEmpty())
                }
        val peerName =
            parsedName?.takeIf(PeerNameResolver::isValidPeerName)
                ?: PeerNameResolver.formatHashAsFallback(destinationHash)
        val receivedAt = now()

        if (publicKey.isEmpty()) {
            // Without a validated identity key, do not let malformed or
            // blackholed announces repopulate durable activity.
            return
        }

        // Persist metadata below the IPC boundary so the display name is not
        // lost while the UI is absent. Persistence separately admits only
        // non-path-response packet hashes to durable peer activity.
        persistence.persistAnnounce(
            destinationHash = destinationHash,
            peerName = peerName,
            publicKey = publicKey,
            appData = announce.appData,
            hops = announce.hops,
            timestamp = receivedAt,
            nodeType = announce.nodeType.name,
            receivingInterface = announce.receivingInterface,
            receivingInterfaceType = InterfaceType.fromName(announce.receivingInterface).storageName,
            aspect = announce.aspect,
            stampCost = announce.stampCost,
            stampCostFlexibility = announce.stampCostFlexibility,
            peeringCost = announce.peeringCost,
            propagationTransferLimitKb = null,
            announcePacketHash = announce.announcePacketHash?.toHex(),
            isPathResponse = announce.isPathResponse,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
