package com.lxmf.messenger.migration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for migration data classes serialization.
 * Tests that all data classes correctly serialize to JSON and deserialize back.
 */
class MigrationDataTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // region Test Data Helpers

    private fun createTestSettings() =
        SettingsExport(
            notificationsEnabled = true,
            notificationReceivedMessage = true,
            notificationReceivedMessageFavorite = false,
            notificationHeardAnnounce = true,
            notificationBleConnected = false,
            notificationBleDisconnected = false,
            autoAnnounceEnabled = true,
            autoAnnounceIntervalMinutes = 5,
            themePreference = "preset:VIBRANT",
        )

    private fun createTestIdentity(
        hash: String = "abc123def456",
        name: String = "TestIdentity",
        isActive: Boolean = true,
    ) = IdentityExport(
        identityHash = hash,
        displayName = name,
        destinationHash = "dest_$hash",
        keyData = "dGVzdEtleURhdGFCYXNlNjRFbmNvZGVk", // Base64 encoded test data
        createdTimestamp = 1700000000000L,
        lastUsedTimestamp = 1700001000000L,
        isActive = isActive,
    )

    private fun createTestConversation() =
        ConversationExport(
            peerHash = "peer123",
            identityHash = "abc123def456",
            peerName = "Test Peer",
            peerPublicKey = "cHVibGljS2V5QmFzZTY0",
            lastMessage = "Hello!",
            lastMessageTimestamp = 1700000500000L,
            unreadCount = 2,
            lastSeenTimestamp = 1700000400000L,
        )

    private fun createTestMessage() =
        MessageExport(
            id = "msg_001",
            conversationHash = "peer123",
            identityHash = "abc123def456",
            content = "Test message content",
            timestamp = 1700000500000L,
            isFromMe = true,
            status = "DELIVERED",
            isRead = true,
            fieldsJson = """{"attachment":"file.png"}""",
        )

    private fun createTestContact() =
        ContactExport(
            destinationHash = "contact123",
            identityHash = "abc123def456",
            publicKey = "Y29udGFjdFB1YmxpY0tleQ==",
            customNickname = "Best Friend",
            notes = "Met at conference",
            tags = "work,friend",
            addedTimestamp = 1699000000000L,
            addedVia = "announce",
            lastInteractionTimestamp = 1700000000000L,
            isPinned = true,
            status = "ACTIVE",
        )

    private fun createTestAnnounce(receivingInterfaceType: String? = "AUTO_INTERFACE") =
        AnnounceExport(
            destinationHash = "announce123",
            peerName = "Remote Node",
            publicKey = "YW5ub3VuY2VQdWJsaWNLZXk=",
            appData = "YXBwRGF0YQ==",
            hops = 3,
            lastSeenTimestamp = 1700000000000L,
            nodeType = "node",
            receivingInterface = "AutoInterface",
            receivingInterfaceType = receivingInterfaceType,
            aspect = "lxmf.delivery",
            isFavorite = true,
            favoritedTimestamp = 1699500000000L,
        )

    private fun createTestInterface() =
        InterfaceExport(
            name = "Test TCP Interface",
            type = "TCPClient",
            enabled = true,
            configJson = """{"target_host":"localhost","target_port":4242}""",
            displayOrder = 1,
        )

    private fun createTestCustomTheme() =
        CustomThemeExport(
            originalId = 1L,
            name = "Ocean Blue",
            description = "A calming blue theme",
            baseTheme = "VIBRANT",
            seedPrimary = 0xFF0066CC.toInt(),
            seedSecondary = 0xFF00AAFF.toInt(),
            seedTertiary = 0xFF00CCFF.toInt(),
            createdTimestamp = 1699000000000L,
            modifiedTimestamp = 1700000000000L,
            // Light mode colors
            lightPrimary = 0xFF0066CC.toInt(),
            lightOnPrimary = 0xFFFFFFFF.toInt(),
            lightPrimaryContainer = 0xFFD0E4FF.toInt(),
            lightOnPrimaryContainer = 0xFF001D36.toInt(),
            lightSecondary = 0xFF535F70.toInt(),
            lightOnSecondary = 0xFFFFFFFF.toInt(),
            lightSecondaryContainer = 0xFFD7E3F8.toInt(),
            lightOnSecondaryContainer = 0xFF101C2B.toInt(),
            lightTertiary = 0xFF6B5778.toInt(),
            lightOnTertiary = 0xFFFFFFFF.toInt(),
            lightTertiaryContainer = 0xFFF3DAFF.toInt(),
            lightOnTertiaryContainer = 0xFF251432.toInt(),
            lightError = 0xFFBA1A1A.toInt(),
            lightOnError = 0xFFFFFFFF.toInt(),
            lightErrorContainer = 0xFFFFDAD6.toInt(),
            lightOnErrorContainer = 0xFF410002.toInt(),
            lightBackground = 0xFFFDFCFF.toInt(),
            lightOnBackground = 0xFF1A1C1E.toInt(),
            lightSurface = 0xFFFDFCFF.toInt(),
            lightOnSurface = 0xFF1A1C1E.toInt(),
            lightSurfaceVariant = 0xFFDFE2EB.toInt(),
            lightOnSurfaceVariant = 0xFF43474E.toInt(),
            lightOutline = 0xFF73777F.toInt(),
            lightOutlineVariant = 0xFFC3C6CF.toInt(),
            // Dark mode colors
            darkPrimary = 0xFF9DCAFF.toInt(),
            darkOnPrimary = 0xFF003258.toInt(),
            darkPrimaryContainer = 0xFF00497D.toInt(),
            darkOnPrimaryContainer = 0xFFD0E4FF.toInt(),
            darkSecondary = 0xFFBBC7DB.toInt(),
            darkOnSecondary = 0xFF253140.toInt(),
            darkSecondaryContainer = 0xFF3C4858.toInt(),
            darkOnSecondaryContainer = 0xFFD7E3F8.toInt(),
            darkTertiary = 0xFFD6BEE4.toInt(),
            darkOnTertiary = 0xFF3B2948.toInt(),
            darkTertiaryContainer = 0xFF523F5F.toInt(),
            darkOnTertiaryContainer = 0xFFF3DAFF.toInt(),
            darkError = 0xFFFFB4AB.toInt(),
            darkOnError = 0xFF690005.toInt(),
            darkErrorContainer = 0xFF93000A.toInt(),
            darkOnErrorContainer = 0xFFFFDAD6.toInt(),
            darkBackground = 0xFF1A1C1E.toInt(),
            darkOnBackground = 0xFFE3E2E6.toInt(),
            darkSurface = 0xFF1A1C1E.toInt(),
            darkOnSurface = 0xFFE3E2E6.toInt(),
            darkSurfaceVariant = 0xFF43474E.toInt(),
            darkOnSurfaceVariant = 0xFFC3C6CF.toInt(),
            darkOutline = 0xFF8D9199.toInt(),
            darkOutlineVariant = 0xFF43474E.toInt(),
        )

    // endregion

    // region MigrationBundle Tests

    @Test
    fun `MigrationBundle version is set to CURRENT_VERSION`() {
        val bundle =
            MigrationBundle(
                identities = emptyList(),
                conversations = emptyList(),
                messages = emptyList(),
                contacts = emptyList(),
                settings = createTestSettings(),
            )

        assertEquals(MigrationBundle.CURRENT_VERSION, bundle.version)
        assertEquals(6, bundle.version)
    }

    @Test
    fun `MigrationBundle serializes to JSON and deserializes back correctly`() {
        val bundle =
            MigrationBundle(
                identities = listOf(createTestIdentity()),
                conversations = listOf(createTestConversation()),
                messages = listOf(createTestMessage()),
                contacts = listOf(createTestContact()),
                settings = createTestSettings(),
            )

        val jsonString = json.encodeToString(bundle)
        val decoded = json.decodeFromString<MigrationBundle>(jsonString)

        assertEquals(bundle.version, decoded.version)
        assertEquals(bundle.identities, decoded.identities)
        assertEquals(bundle.conversations, decoded.conversations)
        assertEquals(bundle.messages, decoded.messages)
        assertEquals(bundle.contacts, decoded.contacts)
        assertEquals(bundle.settings, decoded.settings)
    }

    @Test
    fun `MigrationBundle with empty optional lists serializes correctly`() {
        val bundle =
            MigrationBundle(
                identities = listOf(createTestIdentity()),
                conversations = emptyList(),
                messages = emptyList(),
                contacts = emptyList(),
                announces = emptyList(),
                interfaces = emptyList(),
                customThemes = emptyList(),
                settings = createTestSettings(),
                attachmentManifest = emptyList(),
            )

        val jsonString = json.encodeToString(bundle)
        val decoded = json.decodeFromString<MigrationBundle>(jsonString)

        assertEquals(emptyList<AnnounceExport>(), decoded.announces)
        assertEquals(emptyList<InterfaceExport>(), decoded.interfaces)
        assertEquals(emptyList<CustomThemeExport>(), decoded.customThemes)
        assertEquals(emptyList<AttachmentRef>(), decoded.attachmentManifest)
    }

    @Test
    fun `MigrationBundle with populated optional lists serializes correctly`() {
        val bundle =
            MigrationBundle(
                identities = listOf(createTestIdentity()),
                conversations = emptyList(),
                messages = emptyList(),
                contacts = emptyList(),
                announces = listOf(createTestAnnounce()),
                interfaces = listOf(createTestInterface()),
                customThemes = listOf(createTestCustomTheme()),
                settings = createTestSettings(),
                attachmentManifest =
                    listOf(
                        AttachmentRef(
                            messageId = "msg_001",
                            fieldKey = "attachment",
                            relativePath = "attachments/msg_001_attachment.png",
                            sizeBytes = 12345L,
                        ),
                    ),
            )

        val jsonString = json.encodeToString(bundle)
        val decoded = json.decodeFromString<MigrationBundle>(jsonString)

        assertEquals(1, decoded.announces.size)
        assertEquals(1, decoded.interfaces.size)
        assertEquals(1, decoded.customThemes.size)
        assertEquals(1, decoded.attachmentManifest.size)
        assertEquals(bundle.announces, decoded.announces)
        assertEquals(bundle.interfaces, decoded.interfaces)
        assertEquals(bundle.customThemes, decoded.customThemes)
        assertEquals(bundle.attachmentManifest, decoded.attachmentManifest)
    }

    // endregion

    // region IdentityExport Tests

    @Test
    fun `IdentityExport round-trip preserves all fields`() {
        val identity =
            createTestIdentity(
                hash = "unique_hash_123",
                name = "My Identity",
                isActive = true,
            )

        val jsonString = json.encodeToString(identity)
        val decoded = json.decodeFromString<IdentityExport>(jsonString)

        assertEquals(identity, decoded)
        assertEquals("unique_hash_123", decoded.identityHash)
        assertEquals("My Identity", decoded.displayName)
        assertEquals("dest_unique_hash_123", decoded.destinationHash)
        assertEquals(true, decoded.isActive)
    }

    // endregion

    // region CustomThemeExport Tests

    @Test
    fun `CustomThemeExport preserves all 48 color values through serialization`() {
        val theme = createTestCustomTheme()

        val jsonString = json.encodeToString(theme)
        val decoded = json.decodeFromString<CustomThemeExport>(jsonString)

        // Verify all metadata
        assertEquals(theme.originalId, decoded.originalId)
        assertEquals(theme.name, decoded.name)
        assertEquals(theme.description, decoded.description)
        assertEquals(theme.baseTheme, decoded.baseTheme)

        // Verify seed colors
        assertEquals(theme.seedPrimary, decoded.seedPrimary)
        assertEquals(theme.seedSecondary, decoded.seedSecondary)
        assertEquals(theme.seedTertiary, decoded.seedTertiary)

        // Verify all light mode colors (24 colors)
        assertEquals(theme.lightPrimary, decoded.lightPrimary)
        assertEquals(theme.lightOnPrimary, decoded.lightOnPrimary)
        assertEquals(theme.lightPrimaryContainer, decoded.lightPrimaryContainer)
        assertEquals(theme.lightOnPrimaryContainer, decoded.lightOnPrimaryContainer)
        assertEquals(theme.lightSecondary, decoded.lightSecondary)
        assertEquals(theme.lightOnSecondary, decoded.lightOnSecondary)
        assertEquals(theme.lightSecondaryContainer, decoded.lightSecondaryContainer)
        assertEquals(theme.lightOnSecondaryContainer, decoded.lightOnSecondaryContainer)
        assertEquals(theme.lightTertiary, decoded.lightTertiary)
        assertEquals(theme.lightOnTertiary, decoded.lightOnTertiary)
        assertEquals(theme.lightTertiaryContainer, decoded.lightTertiaryContainer)
        assertEquals(theme.lightOnTertiaryContainer, decoded.lightOnTertiaryContainer)
        assertEquals(theme.lightError, decoded.lightError)
        assertEquals(theme.lightOnError, decoded.lightOnError)
        assertEquals(theme.lightErrorContainer, decoded.lightErrorContainer)
        assertEquals(theme.lightOnErrorContainer, decoded.lightOnErrorContainer)
        assertEquals(theme.lightBackground, decoded.lightBackground)
        assertEquals(theme.lightOnBackground, decoded.lightOnBackground)
        assertEquals(theme.lightSurface, decoded.lightSurface)
        assertEquals(theme.lightOnSurface, decoded.lightOnSurface)
        assertEquals(theme.lightSurfaceVariant, decoded.lightSurfaceVariant)
        assertEquals(theme.lightOnSurfaceVariant, decoded.lightOnSurfaceVariant)
        assertEquals(theme.lightOutline, decoded.lightOutline)
        assertEquals(theme.lightOutlineVariant, decoded.lightOutlineVariant)

        // Verify all dark mode colors (24 colors)
        assertEquals(theme.darkPrimary, decoded.darkPrimary)
        assertEquals(theme.darkOnPrimary, decoded.darkOnPrimary)
        assertEquals(theme.darkPrimaryContainer, decoded.darkPrimaryContainer)
        assertEquals(theme.darkOnPrimaryContainer, decoded.darkOnPrimaryContainer)
        assertEquals(theme.darkSecondary, decoded.darkSecondary)
        assertEquals(theme.darkOnSecondary, decoded.darkOnSecondary)
        assertEquals(theme.darkSecondaryContainer, decoded.darkSecondaryContainer)
        assertEquals(theme.darkOnSecondaryContainer, decoded.darkOnSecondaryContainer)
        assertEquals(theme.darkTertiary, decoded.darkTertiary)
        assertEquals(theme.darkOnTertiary, decoded.darkOnTertiary)
        assertEquals(theme.darkTertiaryContainer, decoded.darkTertiaryContainer)
        assertEquals(theme.darkOnTertiaryContainer, decoded.darkOnTertiaryContainer)
        assertEquals(theme.darkError, decoded.darkError)
        assertEquals(theme.darkOnError, decoded.darkOnError)
        assertEquals(theme.darkErrorContainer, decoded.darkErrorContainer)
        assertEquals(theme.darkOnErrorContainer, decoded.darkOnErrorContainer)
        assertEquals(theme.darkBackground, decoded.darkBackground)
        assertEquals(theme.darkOnBackground, decoded.darkOnBackground)
        assertEquals(theme.darkSurface, decoded.darkSurface)
        assertEquals(theme.darkOnSurface, decoded.darkOnSurface)
        assertEquals(theme.darkSurfaceVariant, decoded.darkSurfaceVariant)
        assertEquals(theme.darkOnSurfaceVariant, decoded.darkOnSurfaceVariant)
        assertEquals(theme.darkOutline, decoded.darkOutline)
        assertEquals(theme.darkOutlineVariant, decoded.darkOutlineVariant)
    }

    // endregion

    // region SettingsExport Tests

    @Test
    fun `SettingsExport round-trip preserves all fields`() {
        val settings =
            SettingsExport(
                notificationsEnabled = false,
                notificationReceivedMessage = true,
                notificationReceivedMessageFavorite = true,
                notificationHeardAnnounce = false,
                notificationBleConnected = true,
                notificationBleDisconnected = true,
                autoAnnounceEnabled = false,
                autoAnnounceIntervalMinutes = 15,
                themePreference = "custom:42",
            )

        val jsonString = json.encodeToString(settings)
        val decoded = json.decodeFromString<SettingsExport>(jsonString)

        assertEquals(settings, decoded)
        assertEquals(false, decoded.notificationsEnabled)
        assertEquals(true, decoded.notificationReceivedMessage)
        assertEquals(15, decoded.autoAnnounceIntervalMinutes)
        assertEquals("custom:42", decoded.themePreference)
    }

    @Test
    fun `SettingsExport propagation settings round-trip correctly`() {
        val settings =
            SettingsExport(
                notificationsEnabled = true,
                notificationReceivedMessage = true,
                notificationReceivedMessageFavorite = false,
                notificationHeardAnnounce = false,
                notificationBleConnected = false,
                notificationBleDisconnected = false,
                autoAnnounceEnabled = true,
                autoAnnounceIntervalMinutes = 5,
                themePreference = "preset:VIBRANT",
                defaultDeliveryMethod = "propagated",
                tryPropagationOnFail = false,
                manualPropagationNode = "abcdef1234567890",
                lastPropagationNode = "1234567890abcdef",
                autoSelectPropagationNode = false,
                autoRetrieveEnabled = false,
                retrievalIntervalSeconds = 120,
            )

        val jsonString = json.encodeToString(settings)
        val decoded = json.decodeFromString<SettingsExport>(jsonString)

        assertEquals("propagated", decoded.defaultDeliveryMethod)
        assertEquals(false, decoded.tryPropagationOnFail)
        assertEquals("abcdef1234567890", decoded.manualPropagationNode)
        assertEquals("1234567890abcdef", decoded.lastPropagationNode)
        assertEquals(false, decoded.autoSelectPropagationNode)
        assertEquals(false, decoded.autoRetrieveEnabled)
        assertEquals(120, decoded.retrievalIntervalSeconds)
    }

    @Test
    fun `SettingsExport backward compatibility with v5 JSON without propagation settings`() {
        // Simulate deserializing a v5 export without propagation settings
        val v5Json =
            """
            {
                "notificationsEnabled": true,
                "notificationReceivedMessage": true,
                "notificationReceivedMessageFavorite": false,
                "notificationHeardAnnounce": false,
                "notificationBleConnected": false,
                "notificationBleDisconnected": false,
                "autoAnnounceEnabled": true,
                "autoAnnounceIntervalMinutes": 5,
                "themePreference": "preset:VIBRANT"
            }
            """.trimIndent()

        val decoded = json.decodeFromString<SettingsExport>(v5Json)

        // Original fields should be preserved
        assertEquals(true, decoded.notificationsEnabled)
        assertEquals(true, decoded.notificationReceivedMessage)
        assertEquals(5, decoded.autoAnnounceIntervalMinutes)
        assertEquals("preset:VIBRANT", decoded.themePreference)

        // New fields should be null (backward compatibility)
        assertNull(decoded.defaultDeliveryMethod)
        assertNull(decoded.tryPropagationOnFail)
        assertNull(decoded.manualPropagationNode)
        assertNull(decoded.lastPropagationNode)
        assertNull(decoded.autoSelectPropagationNode)
        assertNull(decoded.autoRetrieveEnabled)
        assertNull(decoded.retrievalIntervalSeconds)
    }

    // endregion

    // region AnnounceExport Tests

    @Test
    fun `AnnounceExport round-trip preserves receivingInterfaceType`() {
        val announce = createTestAnnounce(receivingInterfaceType = "TCP_CLIENT")

        val jsonString = json.encodeToString(announce)
        val decoded = json.decodeFromString<AnnounceExport>(jsonString)

        assertEquals("TCP_CLIENT", decoded.receivingInterfaceType)
        assertEquals(announce.receivingInterface, decoded.receivingInterface)
    }

    @Test
    fun `AnnounceExport backward compatibility with v5 JSON without receivingInterfaceType`() {
        // Simulate deserializing a v5 export without receivingInterfaceType
        val v5Json =
            """
            {
                "destinationHash": "announce123",
                "peerName": "Remote Node",
                "publicKey": "YW5ub3VuY2VQdWJsaWNLZXk=",
                "appData": "YXBwRGF0YQ==",
                "hops": 3,
                "lastSeenTimestamp": 1700000000000,
                "nodeType": "node",
                "receivingInterface": "AutoInterface",
                "aspect": "lxmf.delivery",
                "isFavorite": true,
                "favoritedTimestamp": 1699500000000
            }
            """.trimIndent()

        val decoded = json.decodeFromString<AnnounceExport>(v5Json)

        // Original fields should be preserved
        assertEquals("announce123", decoded.destinationHash)
        assertEquals("AutoInterface", decoded.receivingInterface)

        // New field should be null (backward compatibility)
        assertNull(decoded.receivingInterfaceType)
    }

    // endregion

    // region InterfaceExport Tests

    @Test
    fun `InterfaceExport round-trip preserves configJson`() {
        val interfaceConfig =
            InterfaceExport(
                name = "Complex Interface",
                type = "TCPClient",
                enabled = true,
                configJson =
                    """{"target_host":"example.com","target_port":4965,""" +
                        """"kiss_framing":false,"network_name":"secure","passphrase":"secret123"}""",
                displayOrder = 2,
            )

        val jsonString = json.encodeToString(interfaceConfig)
        val decoded = json.decodeFromString<InterfaceExport>(jsonString)

        assertEquals(interfaceConfig, decoded)
        assertEquals(interfaceConfig.configJson, decoded.configJson)
        // Verify the nested JSON is preserved exactly
        assertNotNull(decoded.configJson)
        assert(decoded.configJson.contains("example.com"))
        assert(decoded.configJson.contains("secret123"))
    }

    // endregion

    // region ContactExport Tests

    @Test
    fun `ContactExport with null publicKey serializes correctly`() {
        // This tests the scenario where a contact has PENDING_IDENTITY status
        // and hasn't yet resolved their identity from the network
        val contact =
            ContactExport(
                destinationHash = "pending_contact_123",
                identityHash = "abc123def456",
                publicKey = null, // Pending contact - no public key yet
                customNickname = "Pending Friend",
                notes = "Added via destination hash only",
                tags = "pending",
                addedTimestamp = 1699000000000L,
                addedVia = "MANUAL_PENDING",
                lastInteractionTimestamp = 0L,
                isPinned = false,
                status = "PENDING_IDENTITY",
            )

        val jsonString = json.encodeToString(contact)
        val decoded = json.decodeFromString<ContactExport>(jsonString)

        assertEquals(contact, decoded)
        assertNull(decoded.publicKey)
        assertEquals("pending_contact_123", decoded.destinationHash)
        assertEquals("Pending Friend", decoded.customNickname)
        assertEquals("PENDING_IDENTITY", decoded.status)
    }

    @Test
    fun `ContactExport with valid publicKey serializes correctly`() {
        val contact = createTestContact()

        val jsonString = json.encodeToString(contact)
        val decoded = json.decodeFromString<ContactExport>(jsonString)

        assertEquals(contact, decoded)
        assertNotNull(decoded.publicKey)
        assertEquals("Y29udGFjdFB1YmxpY0tleQ==", decoded.publicKey)
        assertEquals("ACTIVE", decoded.status)
    }

    @Test
    fun `MigrationBundle with pending contacts serializes correctly`() {
        // Test that a migration bundle containing both resolved and pending contacts works
        val resolvedContact = createTestContact()
        val pendingContact =
            ContactExport(
                destinationHash = "pending_123",
                identityHash = "abc123def456",
                publicKey = null, // Pending - no public key
                customNickname = null,
                notes = null,
                tags = null,
                addedTimestamp = 1699000000000L,
                addedVia = "MANUAL_PENDING",
                lastInteractionTimestamp = 0L,
                isPinned = false,
                status = "PENDING_IDENTITY",
            )

        val bundle =
            MigrationBundle(
                identities = listOf(createTestIdentity()),
                conversations = emptyList(),
                messages = emptyList(),
                contacts = listOf(resolvedContact, pendingContact),
                settings = createTestSettings(),
            )

        val jsonString = json.encodeToString(bundle)
        val decoded = json.decodeFromString<MigrationBundle>(jsonString)

        assertEquals(2, decoded.contacts.size)
        // First contact should have public key
        assertNotNull(decoded.contacts[0].publicKey)
        // Second contact (pending) should have null public key
        assertNull(decoded.contacts[1].publicKey)
        // Status should be preserved
        assertEquals("ACTIVE", decoded.contacts[0].status)
        assertEquals("PENDING_IDENTITY", decoded.contacts[1].status)
    }

    @Test
    fun `ContactExport backward compatibility with older exports without status field`() {
        // Simulate deserializing an older export without the status field
        val oldJson =
            """
            {
                "destinationHash": "contact123",
                "identityHash": "abc123def456",
                "publicKey": "Y29udGFjdFB1YmxpY0tleQ==",
                "customNickname": "Old Friend",
                "notes": null,
                "tags": null,
                "addedTimestamp": 1699000000000,
                "addedVia": "announce",
                "lastInteractionTimestamp": 1700000000000,
                "isPinned": false
            }
            """.trimIndent()

        val decoded = json.decodeFromString<ContactExport>(oldJson)

        // Original fields should be preserved
        assertEquals("contact123", decoded.destinationHash)
        assertEquals("Y29udGFjdFB1YmxpY0tleQ==", decoded.publicKey)
        assertEquals("Old Friend", decoded.customNickname)

        // Status should be null (backward compatibility default)
        assertNull(decoded.status)
    }

    // endregion

    // region Result Classes Tests

    @Test
    fun `ExportResult Success contains correct counts`() {
        val result =
            ExportResult.Success(
                identityCount = 3,
                messageCount = 150,
                contactCount = 25,
                announceCount = 100,
                peerIdentityCount = 75,
                interfaceCount = 4,
                customThemeCount = 2,
            )

        assertEquals(3, result.identityCount)
        assertEquals(150, result.messageCount)
        assertEquals(25, result.contactCount)
        assertEquals(100, result.announceCount)
        assertEquals(75, result.peerIdentityCount)
        assertEquals(4, result.interfaceCount)
        assertEquals(2, result.customThemeCount)
    }

    @Test
    fun `ImportResult Success contains correct counts`() {
        val result =
            ImportResult.Success(
                identitiesImported = 2,
                messagesImported = 100,
                contactsImported = 15,
                announcesImported = 50,
                peerIdentitiesImported = 40,
                interfacesImported = 3,
                customThemesImported = 1,
            )

        assertEquals(2, result.identitiesImported)
        assertEquals(100, result.messagesImported)
        assertEquals(15, result.contactsImported)
        assertEquals(50, result.announcesImported)
        assertEquals(40, result.peerIdentitiesImported)
        assertEquals(3, result.interfacesImported)
        assertEquals(1, result.customThemesImported)
    }

    // endregion
}
