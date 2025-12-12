package com.lxmf.messenger.test

import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.service.RelayInfo

/**
 * Factory functions for creating test objects with sensible defaults.
 * Shared across multiple test files for consistent test data.
 */
object TestFactories {
    const val TEST_IDENTITY_HASH = "test_identity_hash_123"
    const val TEST_DEST_HASH = "0123456789abcdef0123456789abcdef"
    const val TEST_DEST_HASH_2 = "fedcba9876543210fedcba9876543210"
    const val TEST_DEST_HASH_3 = "aabbccdd11223344aabbccdd11223344"
    val TEST_PUBLIC_KEY = ByteArray(64) { it.toByte() }

    fun createLocalIdentity(
        identityHash: String = TEST_IDENTITY_HASH,
        displayName: String = "Test Identity",
        isActive: Boolean = true,
    ) = LocalIdentityEntity(
        identityHash = identityHash,
        displayName = displayName,
        destinationHash = "dest_$identityHash",
        filePath = "/data/identity_$identityHash",
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = isActive,
    )

    /**
     * Configuration for creating test ContactEntity objects.
     */
    data class ContactConfig(
        val destinationHash: String = TEST_DEST_HASH,
        val identityHash: String = TEST_IDENTITY_HASH,
        val publicKey: ByteArray? = TEST_PUBLIC_KEY,
        val customNickname: String? = null,
        val status: ContactStatus = ContactStatus.ACTIVE,
        val isPinned: Boolean = false,
        val isMyRelay: Boolean = false,
        val addedVia: String = "MANUAL",
    )

    fun createContactEntity(config: ContactConfig = ContactConfig()) = ContactEntity(
        destinationHash = config.destinationHash,
        identityHash = config.identityHash,
        publicKey = config.publicKey,
        customNickname = config.customNickname,
        notes = null,
        tags = null,
        addedTimestamp = System.currentTimeMillis(),
        addedVia = config.addedVia,
        lastInteractionTimestamp = 0,
        isPinned = config.isPinned,
        status = config.status,
        isMyRelay = config.isMyRelay,
    )

    /** Convenience overload for simple cases. */
    fun createContactEntity(
        destinationHash: String = TEST_DEST_HASH,
        identityHash: String = TEST_IDENTITY_HASH,
        status: ContactStatus = ContactStatus.ACTIVE,
        isMyRelay: Boolean = false,
    ) = createContactEntity(
        ContactConfig(
            destinationHash = destinationHash,
            identityHash = identityHash,
            status = status,
            isMyRelay = isMyRelay,
        ),
    )

    /**
     * Configuration for creating test EnrichedContact objects.
     */
    data class EnrichedContactConfig(
        val destinationHash: String = TEST_DEST_HASH,
        val publicKey: ByteArray? = TEST_PUBLIC_KEY,
        val displayName: String = "Test Contact",
        val customNickname: String? = null,
        val announceName: String? = null,
        val isPinned: Boolean = false,
        val isMyRelay: Boolean = false,
        val status: ContactStatus = ContactStatus.ACTIVE,
        val hops: Int? = 1,
        val isOnline: Boolean = true,
        val nodeType: String? = "PEER",
        val hasConversation: Boolean = false,
        val unreadCount: Int = 0,
        val tags: String? = null,
    )

    fun createEnrichedContact(config: EnrichedContactConfig = EnrichedContactConfig()) = EnrichedContact(
        destinationHash = config.destinationHash,
        publicKey = config.publicKey,
        displayName = config.displayName,
        customNickname = config.customNickname,
        announceName = config.announceName ?: config.displayName,
        lastSeenTimestamp = System.currentTimeMillis(),
        hops = config.hops,
        isOnline = config.isOnline,
        hasConversation = config.hasConversation,
        unreadCount = config.unreadCount,
        lastMessageTimestamp = null,
        notes = null,
        tags = config.tags,
        addedTimestamp = System.currentTimeMillis(),
        addedVia = "ANNOUNCE",
        isPinned = config.isPinned,
        status = config.status,
        isMyRelay = config.isMyRelay,
        nodeType = config.nodeType,
    )

    /** Convenience overload for simple cases. */
    fun createEnrichedContact(
        destinationHash: String = TEST_DEST_HASH,
        displayName: String = "Test Contact",
        status: ContactStatus = ContactStatus.ACTIVE,
        isMyRelay: Boolean = false,
        isPinned: Boolean = false,
    ) = createEnrichedContact(
        EnrichedContactConfig(
            destinationHash = destinationHash,
            displayName = displayName,
            status = status,
            isMyRelay = isMyRelay,
            isPinned = isPinned,
        ),
    )

    fun createAnnounce(
        destinationHash: String = TEST_DEST_HASH,
        peerName: String = "Test Peer",
        publicKey: ByteArray = TEST_PUBLIC_KEY,
        hops: Int = 1,
        nodeType: String = "PROPAGATION_NODE",
        lastSeenTimestamp: Long = System.currentTimeMillis(),
    ) = Announce(
        destinationHash = destinationHash,
        peerName = peerName,
        publicKey = publicKey,
        appData = null,
        hops = hops,
        lastSeenTimestamp = lastSeenTimestamp,
        nodeType = nodeType,
        receivingInterface = null,
        isFavorite = false,
    )

    fun createRelayInfo(
        destinationHash: String = TEST_DEST_HASH,
        displayName: String = "Test Relay",
        hops: Int = 1,
        isAutoSelected: Boolean = true,
        lastSeenTimestamp: Long = System.currentTimeMillis(),
    ) = RelayInfo(
        destinationHash = destinationHash,
        displayName = displayName,
        hops = hops,
        isAutoSelected = isAutoSelected,
        lastSeenTimestamp = lastSeenTimestamp,
    )
}
