# Feature: Import Contacts from Destination Hash (Sideband Interoperability)

## Overview

Enable importing contacts using just a destination hash (32 hex characters) from Sideband's "Copy Address" feature, in addition to the existing full `lxma://` URL format. This enables seamless contact sharing between Sideband and Columba users.

## Problem Statement

Sideband's "Copy Address" feature only copies the LXMF destination hash (32 hex characters), but Columba currently requires the full `lxma://hash:pubkey` format (~165 characters) which includes both the destination hash and the 128-character public key. This makes it difficult for users to import contacts from Sideband.

### Technical Background

The relationship between identity components in Reticulum:

```
Public Key (64 bytes / 128 hex chars)
    ↓ SHA-256 + truncate
Identity Hash (16 bytes / 32 hex chars)
    ↓ + destination name hash ("lxmf.delivery")
Destination Hash (16 bytes / 32 hex chars)
```

**Key insight**: The destination hash cannot be reversed to obtain the public key (hashes are one-way functions). However, the public key can be retrieved from the network if the identity has announced itself.

### How Sideband Shares Contacts

From `sbapp/ui/conversations.py`:
```python
def gen_copy_addr(item):
    def x():
        Clipboard.copy(RNS.hexrep(self.conversation_dropdown.context_dest, delimit=False))
    return x
```

This copies only the 32-character destination hash.

## Solution

Detect input format in the Add Contact Manually dialog and handle accordingly:

| Input Format | Action |
|-------------|--------|
| Full `lxma://` URL | Add contact immediately (existing behavior) |
| 32-char destination hash | Create "pending" contact, resolve public key via network |

---

## Detailed Implementation Plan

### 1. Data Model Changes

**File:** `data/src/main/java/com/lxmf/messenger/data/db/entity/ContactEntity.kt`

#### Add ContactStatus Enum

```kotlin
/**
 * Represents the resolution status of a contact's identity.
 */
enum class ContactStatus {
    /**
     * Full identity is known (destination hash + public key).
     * Contact can send and receive messages.
     */
    ACTIVE,

    /**
     * Only destination hash is known, awaiting public key from network.
     * Contact cannot send messages until identity is resolved.
     */
    PENDING_IDENTITY,

    /**
     * Network search timed out without finding the identity.
     * User can manually retry the search.
     */
    UNRESOLVED
}
```

#### Update ContactEntity

```kotlin
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey
    val destinationHash: String,
    val identityHash: String,
    val publicKey: ByteArray?,  // Now nullable for pending contacts
    val customNickname: String? = null,
    val notes: String? = null,
    val tags: String? = null,
    val addedTimestamp: Long,
    val addedVia: String,
    val lastInteractionTimestamp: Long = 0,
    val isPinned: Boolean = false,
    val status: ContactStatus = ContactStatus.ACTIVE  // NEW FIELD
) {
    // ... existing equals/hashCode
}
```

#### Database Migration

```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE contacts ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'"
        )
    }
}
```

---

### 2. Validation Changes

**File:** `app/src/main/java/com/lxmf/messenger/util/validation/InputValidator.kt`

#### Add IdentityInput Sealed Class

```kotlin
/**
 * Represents parsed identity input from user.
 * Can be either a full identity (hash + pubkey) or just a destination hash.
 */
sealed class IdentityInput {
    /**
     * Full identity with both destination hash and public key.
     * Can be added as an active contact immediately.
     */
    data class FullIdentity(
        val destinationHash: String,
        val publicKey: ByteArray
    ) : IdentityInput()

    /**
     * Only destination hash available.
     * Must resolve public key from network before messaging.
     */
    data class DestinationHashOnly(
        val destinationHash: String
    ) : IdentityInput()
}
```

#### Add parseIdentityInput Function

```kotlin
/**
 * Parses user input and determines the identity format.
 *
 * Supports two formats:
 * 1. Full lxma:// URL: "lxma://<32-char-hash>:<128-char-pubkey>"
 * 2. Destination hash only: 32 hexadecimal characters
 *
 * @param input Raw user input string
 * @return ValidationResult containing parsed IdentityInput or error message
 */
fun parseIdentityInput(input: String): ValidationResult<IdentityInput> {
    val trimmed = input.trim().lowercase()

    // Check for empty input
    if (trimmed.isEmpty()) {
        return ValidationResult.Error("Please enter an identity string or destination hash")
    }

    // Try full lxma:// format first
    if (trimmed.startsWith(LXMF_IDENTITY_PREFIX)) {
        return when (val result = validateIdentityString(trimmed)) {
            is ValidationResult.Success -> {
                val parts = trimmed.removePrefix(LXMF_IDENTITY_PREFIX).split(":")
                val destHash = parts[0]
                val pubKeyHex = parts[1]
                val pubKeyBytes = pubKeyHex.chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
                ValidationResult.Success(IdentityInput.FullIdentity(destHash, pubKeyBytes))
            }
            is ValidationResult.Error -> result
        }
    }

    // Try destination hash only (32 hex chars)
    if (trimmed.length == DESTINATION_HASH_LENGTH * 2) {
        // Validate it's valid hex
        if (!HEX_REGEX.matches(trimmed)) {
            return ValidationResult.Error(
                "Invalid destination hash: must contain only hexadecimal characters (0-9, a-f)"
            )
        }
        return ValidationResult.Success(IdentityInput.DestinationHashOnly(trimmed))
    }

    // Neither format matches
    return ValidationResult.Error(
        "Invalid format. Enter either:\n" +
        "• Full identity: lxma://hash:pubkey\n" +
        "• Destination hash: 32 hexadecimal characters"
    )
}
```

---

### 3. Repository Changes

**File:** `data/src/main/java/com/lxmf/messenger/data/repository/ContactRepository.kt`

#### Add New Methods

```kotlin
/**
 * Adds a contact with only a destination hash (pending identity resolution).
 *
 * The contact will be created with PENDING_IDENTITY status and a null public key.
 * Background workers will attempt to resolve the identity from the network.
 *
 * @param destinationHash The 32-character hex destination hash
 * @param nickname Optional display name for the contact
 * @return Result indicating success or failure
 */
suspend fun addPendingContact(
    destinationHash: String,
    nickname: String? = null
): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val activeIdentityHash = identityManager.getActiveIdentityHash()
            ?: return@withContext Result.failure(IllegalStateException("No active identity"))

        val contact = ContactEntity(
            destinationHash = destinationHash,
            identityHash = activeIdentityHash,
            publicKey = null,  // Will be filled when identity is resolved
            customNickname = nickname,
            addedTimestamp = System.currentTimeMillis(),
            addedVia = "MANUAL_PENDING",
            status = ContactStatus.PENDING_IDENTITY
        )

        contactDao.insertContact(contact)
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to add pending contact", e)
        Result.failure(e)
    }
}

/**
 * Updates a pending contact with resolved identity information.
 *
 * Called when the network returns the public key for a pending contact.
 * Changes status from PENDING_IDENTITY to ACTIVE.
 *
 * @param destinationHash The contact's destination hash
 * @param publicKey The resolved 64-byte public key
 * @return Result indicating success or failure
 */
suspend fun updateContactWithIdentity(
    destinationHash: String,
    publicKey: ByteArray
): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        contactDao.updateContactIdentity(
            destinationHash = destinationHash,
            publicKey = publicKey,
            status = ContactStatus.ACTIVE
        )
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to update contact identity", e)
        Result.failure(e)
    }
}

/**
 * Updates a contact's status (e.g., to UNRESOLVED after timeout).
 */
suspend fun updateContactStatus(
    destinationHash: String,
    status: ContactStatus
): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        contactDao.updateContactStatus(destinationHash, status)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Gets all contacts with specified statuses.
 */
suspend fun getContactsByStatus(
    statuses: List<ContactStatus>
): List<ContactEntity> = withContext(Dispatchers.IO) {
    contactDao.getContactsByStatus(statuses.map { it.name })
}
```

#### Add DAO Methods

```kotlin
@Dao
interface ContactDao {
    // ... existing methods

    @Query("""
        UPDATE contacts
        SET publicKey = :publicKey, status = :status
        WHERE destinationHash = :destinationHash
    """)
    suspend fun updateContactIdentity(
        destinationHash: String,
        publicKey: ByteArray,
        status: ContactStatus
    )

    @Query("UPDATE contacts SET status = :status WHERE destinationHash = :destinationHash")
    suspend fun updateContactStatus(destinationHash: String, status: ContactStatus)

    @Query("SELECT * FROM contacts WHERE status IN (:statuses)")
    suspend fun getContactsByStatus(statuses: List<String>): List<ContactEntity>
}
```

---

### 4. ViewModel Changes

**File:** `app/src/main/java/com/lxmf/messenger/viewmodel/ContactsViewModel.kt`

#### Add Result Sealed Class

```kotlin
/**
 * Result of attempting to add a contact.
 */
sealed class AddContactResult {
    /**
     * Contact added successfully with full identity.
     * Can message immediately.
     */
    object Success : AddContactResult()

    /**
     * Contact added with pending identity.
     * Waiting for network to resolve public key.
     */
    object PendingIdentity : AddContactResult()

    /**
     * Failed to add contact.
     */
    data class Error(val message: String) : AddContactResult()
}
```

#### Add Contact Addition Method

```kotlin
/**
 * Adds a contact from user input, handling both full identity and hash-only formats.
 *
 * Flow:
 * 1. Parse and validate input format
 * 2. For full identity: add immediately as ACTIVE
 * 3. For hash-only:
 *    a. Check local cache for identity (Identity.recall)
 *    b. If found: add as ACTIVE
 *    c. If not found: add as PENDING_IDENTITY, request from network
 *
 * @param input User input (lxma:// URL or destination hash)
 * @param nickname Optional display name
 * @return AddContactResult indicating outcome
 */
suspend fun addContact(input: String, nickname: String?): AddContactResult {
    // Parse input
    val parsed = InputValidator.parseIdentityInput(input)
    if (parsed is ValidationResult.Error) {
        return AddContactResult.Error(parsed.message)
    }

    val identityInput = (parsed as ValidationResult.Success).value

    return when (identityInput) {
        is IdentityInput.FullIdentity -> {
            // Full identity provided - add immediately
            val result = contactRepository.addContactFromQrCode(
                destinationHash = identityInput.destinationHash,
                publicKey = identityInput.publicKey,
                nickname = nickname
            )
            if (result.isSuccess) {
                AddContactResult.Success
            } else {
                AddContactResult.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            }
        }

        is IdentityInput.DestinationHashOnly -> {
            // Only hash - try to resolve identity
            val destHashBytes = identityInput.destinationHash
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

            // Try local cache first
            val cachedIdentity = reticulumService.recallIdentity(destHashBytes)

            if (cachedIdentity != null) {
                // Found in cache - add with full identity
                val result = contactRepository.addContactFromQrCode(
                    destinationHash = identityInput.destinationHash,
                    publicKey = cachedIdentity.publicKey,
                    nickname = nickname
                )
                if (result.isSuccess) {
                    AddContactResult.Success
                } else {
                    AddContactResult.Error(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } else {
                // Not in cache - create pending contact
                val result = contactRepository.addPendingContact(
                    destinationHash = identityInput.destinationHash,
                    nickname = nickname
                )

                if (result.isSuccess) {
                    // Request identity from network
                    reticulumService.requestPath(destHashBytes)
                    AddContactResult.PendingIdentity
                } else {
                    AddContactResult.Error(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            }
        }
    }
}

/**
 * Retries identity resolution for an unresolved contact.
 */
suspend fun retryIdentityResolution(destinationHash: String) {
    // Reset status to pending
    contactRepository.updateContactStatus(destinationHash, ContactStatus.PENDING_IDENTITY)

    // Request from network again
    val destHashBytes = destinationHash
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
    reticulumService.requestPath(destHashBytes)
}
```

---

### 5. Reticulum Service Integration

**File:** `reticulum/src/main/java/com/lxmf/messenger/reticulum/ReticulumService.kt`

#### Add Identity Resolution Methods

```kotlin
/**
 * Attempts to recall an identity from local cache.
 *
 * This checks Reticulum's known_destinations cache for a previously
 * seen identity matching the destination hash.
 *
 * @param destinationHash The 16-byte destination hash
 * @return Identity if found in cache, null otherwise
 */
suspend fun recallIdentity(destinationHash: ByteArray): Identity? {
    return withContext(Dispatchers.IO) {
        try {
            // Call Python: RNS.Identity.recall(destination_hash)
            val result = pythonBridge.call(
                "recall_identity",
                mapOf("destination_hash" to destinationHash.toHexString())
            )

            if (result != null && result.containsKey("public_key")) {
                Identity(
                    publicKey = (result["public_key"] as String).hexToByteArray(),
                    hash = destinationHash
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to recall identity: ${e.message}")
            null
        }
    }
}

/**
 * Requests a path to a destination from the network.
 *
 * Broadcasts a path request - if any peer knows the destination,
 * they will respond with an announce containing the public key.
 *
 * @param destinationHash The 16-byte destination hash to find
 */
suspend fun requestPath(destinationHash: ByteArray) {
    withContext(Dispatchers.IO) {
        try {
            // Call Python: RNS.Transport.request_path(destination_hash)
            pythonBridge.call(
                "request_path",
                mapOf("destination_hash" to destinationHash.toHexString())
            )
            Log.d(TAG, "Requested path for ${destinationHash.toHexString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request path: ${e.message}")
        }
    }
}

/**
 * Data class representing a Reticulum identity.
 */
data class Identity(
    val publicKey: ByteArray,
    val hash: ByteArray
)
```

---

### 6. UI Changes

**File:** `app/src/main/java/com/lxmf/messenger/ui/screens/ContactsScreen.kt`

#### 6.1 Update ManualEntryDialog

```kotlin
@Composable
fun ManualEntryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    var identityString by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showPendingInfo by remember { mutableStateOf(false) }

    // Handle pending info dialog
    if (showPendingInfo) {
        PendingContactInfoDialog(
            onDismiss = {
                showPendingInfo = false
                onDismiss()
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Contact Manually") },
        text = {
            Column {
                OutlinedTextField(
                    value = identityString,
                    onValueChange = {
                        identityString = it.trim()
                        errorMessage = null
                    },
                    label = { Text("Identity") },
                    placeholder = { Text("lxma://... or 32-char hash") },
                    supportingText = {
                        if (errorMessage != null) {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Paste full lxma:// URL or destination hash from Sideband")
                        }
                    },
                    isError = errorMessage != null,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = nickname,
                    onValueChange = {
                        if (it.length <= ValidationConstants.MAX_NICKNAME_LENGTH) {
                            nickname = it
                        }
                    },
                    label = { Text("Nickname (optional)") },
                    supportingText = {
                        Text("${nickname.length}/${ValidationConstants.MAX_NICKNAME_LENGTH}")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isLoading = true
                    viewModel.viewModelScope.launch {
                        when (val result = viewModel.addContact(identityString, nickname.ifBlank { null })) {
                            is AddContactResult.Success -> {
                                onDismiss()
                            }
                            is AddContactResult.PendingIdentity -> {
                                showPendingInfo = true
                            }
                            is AddContactResult.Error -> {
                                errorMessage = result.message
                                isLoading = false
                            }
                        }
                    }
                },
                enabled = identityString.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

#### 6.2 Pending Contact Info Dialog

```kotlin
/**
 * Informational dialog shown after adding a contact with only a destination hash.
 * Explains that the app is searching for the contact's identity on the network.
 */
@Composable
fun PendingContactInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Contact Added") },
        text = {
            Column {
                Text(
                    "This contact was added with just a destination hash.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Columba is now searching the network for their public key. " +
                    "You'll be able to message them once their identity is found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This may take a few minutes if they're online, or longer if they're offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}
```

#### 6.3 Contact List Item with Status Indicators

```kotlin
/**
 * Contact list item with visual indicators for pending/unresolved status.
 */
@Composable
fun ContactListItem(
    contact: EnrichedContact,
    onClick: () -> Unit,
    onPendingClick: () -> Unit,  // Opens bottom sheet for pending contacts
    modifier: Modifier = Modifier
) {
    val isPending = contact.status == ContactStatus.PENDING_IDENTITY
    val isUnresolved = contact.status == ContactStatus.UNRESOLVED

    ListItem(
        modifier = modifier.clickable {
            if (isPending || isUnresolved) {
                onPendingClick()
            } else {
                onClick()
            }
        },
        headlineContent = {
            Text(
                text = contact.displayName,
                color = if (isPending || isUnresolved) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        },
        supportingContent = {
            when (contact.status) {
                ContactStatus.PENDING_IDENTITY -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Searching for identity...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ContactStatus.UNRESOLVED -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Identity not found - tap to retry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    // Normal online/offline status
                    if (contact.isOnline) {
                        Text(
                            text = "Online",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        leadingContent = {
            // Avatar with status overlay
            Box {
                ContactAvatar(contact = contact)
                if (isPending || isUnresolved) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .background(
                                if (isUnresolved) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isUnresolved)
                                Icons.Outlined.QuestionMark
                            else
                                Icons.Outlined.HourglassEmpty,
                            contentDescription = null,
                            modifier = Modifier.size(8.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    )
}
```

#### 6.4 Pending Contact Bottom Sheet

```kotlin
/**
 * Bottom sheet shown when tapping a pending or unresolved contact.
 * Provides information about the status and actions to retry or delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingContactBottomSheet(
    contact: EnrichedContact,
    onDismiss: () -> Unit,
    onRetrySearch: () -> Unit,
    onDelete: () -> Unit
) {
    val isUnresolved = contact.status == ContactStatus.UNRESOLVED

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status icon
            Icon(
                imageVector = if (isUnresolved)
                    Icons.Outlined.ErrorOutline
                else
                    Icons.Outlined.HourglassEmpty,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (isUnresolved)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = if (isUnresolved)
                    "Identity Not Found"
                else
                    "Searching for Identity",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Contact name
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Explanation
            Text(
                text = if (isUnresolved)
                    "Columba couldn't find this contact's public key on the network. " +
                    "They may need to come online and announce their presence, or the " +
                    "destination hash may be incorrect."
                else
                    "Columba is searching the Reticulum network for this contact's " +
                    "public key. You'll be able to message them once their identity " +
                    "is found. This typically happens when they come online.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Destination hash (for verification)
            Text(
                text = "Destination: ${contact.destinationHash.take(8)}...${contact.destinationHash.takeLast(8)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Retry button
            Button(
                onClick = {
                    onRetrySearch()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Search Network Again")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Delete button
            TextButton(
                onClick = {
                    onDelete()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Remove Contact",
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Bottom padding for navigation bar
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
```

---

### 7. Background Identity Resolution

**File:** `app/src/main/java/com/lxmf/messenger/service/IdentityResolutionWorker.kt` (new file)

```kotlin
package com.lxmf.messenger.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.reticulum.ReticulumService
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically checks for resolved identities.
 *
 * This worker:
 * 1. Queries all contacts with PENDING_IDENTITY status
 * 2. For each, checks if identity is now in local cache (from network announce)
 * 3. If found, updates contact to ACTIVE with the public key
 * 4. If not found and timeout exceeded, marks as UNRESOLVED
 *
 * Runs every 15 minutes while the app has pending contacts.
 */
@HiltWorker
class IdentityResolutionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val contactRepository: ContactRepository,
    private val reticulumService: ReticulumService
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting identity resolution check")

        try {
            val pendingContacts = contactRepository.getContactsByStatus(
                listOf(ContactStatus.PENDING_IDENTITY)
            )

            if (pendingContacts.isEmpty()) {
                Log.d(TAG, "No pending contacts to resolve")
                return Result.success()
            }

            Log.d(TAG, "Checking ${pendingContacts.size} pending contacts")

            for (contact in pendingContacts) {
                checkContact(contact)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Identity resolution failed", e)
            return Result.retry()
        }
    }

    private suspend fun checkContact(contact: ContactEntity) {
        val destHashBytes = contact.destinationHash
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        // Check if identity now available in cache
        val identity = reticulumService.recallIdentity(destHashBytes)

        if (identity != null) {
            // Found! Update to ACTIVE
            Log.i(TAG, "Resolved identity for ${contact.destinationHash}")
            contactRepository.updateContactWithIdentity(
                contact.destinationHash,
                identity.publicKey
            )
        } else {
            // Check timeout
            val elapsed = System.currentTimeMillis() - contact.addedTimestamp
            if (elapsed > RESOLUTION_TIMEOUT_MS) {
                Log.w(TAG, "Identity resolution timed out for ${contact.destinationHash}")
                contactRepository.updateContactStatus(
                    contact.destinationHash,
                    ContactStatus.UNRESOLVED
                )
            } else {
                // Still within timeout, re-request path
                Log.d(TAG, "Re-requesting path for ${contact.destinationHash}")
                reticulumService.requestPath(destHashBytes)
            }
        }
    }

    companion object {
        private const val TAG = "IdentityResolutionWorker"
        private const val WORK_NAME = "identity_resolution"

        /**
         * Timeout before marking contact as UNRESOLVED (48 hours)
         */
        const val RESOLUTION_TIMEOUT_MS = 48 * 60 * 60 * 1000L

        /**
         * Schedules periodic identity resolution checks.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<IdentityResolutionWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
        }

        /**
         * Triggers an immediate identity resolution check.
         */
        fun runNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<IdentityResolutionWorker>()
                .build()

            WorkManager.getInstance(context)
                .enqueue(workRequest)
        }
    }
}
```

---

### 8. Application Initialization

**File:** `app/src/main/java/com/lxmf/messenger/ColumbaApplication.kt`

Add worker scheduling in `onCreate()`:

```kotlin
override fun onCreate() {
    super.onCreate()
    // ... existing initialization

    // Schedule identity resolution worker
    IdentityResolutionWorker.schedule(this)
}
```

---

## Material Design Compliance

### Visual Elements

| Element | Usage | Specification |
|---------|-------|---------------|
| `CircularProgressIndicator` | Inline pending state | Size: 12.dp, strokeWidth: 2.dp |
| `Icons.Outlined.HourglassEmpty` | Pending status | Size: 12.dp (inline), 48.dp (sheet) |
| `Icons.Outlined.ErrorOutline` | Unresolved status | Error color tint |
| `Icons.Outlined.Info` | Info dialog icon | Primary color tint |
| `Icons.Default.Refresh` | Retry button | In Button with text |
| `ModalBottomSheet` | Contact info sheet | Material 3 pattern |
| `AlertDialog` | Confirmations/info | Standard M3 dialogs |

### Color Usage

| State | Color | Token |
|-------|-------|-------|
| Pending text | Muted | `onSurfaceVariant` |
| Pending icon | Primary | `primary` |
| Unresolved text | Error | `error` |
| Unresolved icon | Error | `error` |
| Delete button | Error | `error` |

### Interaction Patterns

1. **Dialog closes on successful add** (both full and pending)
2. **Info dialog appears** after pending add to explain state
3. **Tapping pending/unresolved contact** opens info bottom sheet
4. **Bottom sheet actions**: "Search Network Again" (primary) and "Remove Contact" (destructive)
5. **Contact list items** remain tappable but route to info sheet instead of conversation

---

## Files Summary

### Files to Modify

| File | Changes |
|------|---------|
| `ContactEntity.kt` | Add `status` enum field, make `publicKey` nullable |
| `ContactDao.kt` | Add update methods for status and identity |
| `ContactRepository.kt` | Add `addPendingContact()`, `updateContactWithIdentity()`, `updateContactStatus()` |
| `InputValidator.kt` | Add `IdentityInput` sealed class, `parseIdentityInput()` function |
| `ContactsViewModel.kt` | Add `AddContactResult`, `addContact()`, `retryIdentityResolution()` |
| `ContactsScreen.kt` | Update dialog, add pending indicators, add bottom sheet |
| `ReticulumService.kt` | Add `recallIdentity()`, `requestPath()` |
| `ColumbaApplication.kt` | Schedule identity resolution worker |
| Database migration | Add `status` column with default `ACTIVE` |

### New Files

| File | Purpose |
|------|---------|
| `IdentityResolutionWorker.kt` | Background polling for identity resolution |

---

## Testing Scenarios

| # | Scenario | Expected Behavior |
|---|----------|-------------------|
| 1 | Full lxma:// URL input | Contact added immediately as ACTIVE |
| 2 | Valid 32-char hex input | Contact added as PENDING_IDENTITY, info dialog shown |
| 3 | Hash with cached identity | Contact added immediately as ACTIVE (cache hit) |
| 4 | Network resolution | PENDING_IDENTITY becomes ACTIVE when announce received |
| 5 | Invalid input | Error message shown, dialog stays open |
| 6 | Timeout (48h) | PENDING_IDENTITY marked as UNRESOLVED |
| 7 | Retry search | UNRESOLVED reset to PENDING_IDENTITY, path requested |
| 8 | Tap pending contact | Bottom sheet opens with correct state and actions |
| 9 | Delete pending contact | Contact removed from database |

---

## Future Considerations

- **Notification**: Alert user when pending contact becomes active
- **Bulk import**: Import multiple contacts from file
- **QR code fallback**: Prompt to scan QR if hash resolution fails
- **Manual path request**: Button to force immediate network query
- **Timeout configuration**: User-configurable resolution timeout
