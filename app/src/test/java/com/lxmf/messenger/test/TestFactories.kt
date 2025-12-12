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

    fun createContactEntity(
        destinationHash: String = TEST_DEST_HASH,
        identityHash: String = TEST_IDENTITY_HASH,
        publicKey: ByteArray? = TEST_PUBLIC_KEY,
        customNickname: String? = null,
        status: ContactStatus = ContactStatus.ACTIVE,
        isPinned: Boolean = false,
        isMyRelay: Boolean = false,
        addedVia: String = "MANUAL",
    ) = ContactEntity(
        destinationHash = destinationHash,
        identityHash = identityHash,
        publicKey = publicKey,
        customNickname = customNickname,
        notes = null,
        tags = null,
        addedTimestamp = System.currentTimeMillis(),
        addedVia = addedVia,
        lastInteractionTimestamp = 0,
        isPinned = isPinned,
        status = status,
        isMyRelay = isMyRelay,
    )

    fun createEnrichedContact(
        destinationHash: String = TEST_DEST_HASH,
        publicKey: ByteArray? = TEST_PUBLIC_KEY,
        displayName: String = "Test Contact",
        customNickname: String? = null,
        announceName: String? = displayName,
        isPinned: Boolean = false,
        isMyRelay: Boolean = false,
        status: ContactStatus = ContactStatus.ACTIVE,
        hops: Int? = 1,
        isOnline: Boolean = true,
        nodeType: String? = "PEER",
        hasConversation: Boolean = false,
        unreadCount: Int = 0,
        tags: String? = null,
    ) = EnrichedContact(
        destinationHash = destinationHash,
        publicKey = publicKey,
        displayName = displayName,
        customNickname = customNickname,
        announceName = announceName,
        lastSeenTimestamp = System.currentTimeMillis(),
        hops = hops,
        isOnline = isOnline,
        hasConversation = hasConversation,
        unreadCount = unreadCount,
        lastMessageTimestamp = null,
        notes = null,
        tags = tags,
        addedTimestamp = System.currentTimeMillis(),
        addedVia = "ANNOUNCE",
        isPinned = isPinned,
        status = status,
        isMyRelay = isMyRelay,
        nodeType = nodeType,
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
