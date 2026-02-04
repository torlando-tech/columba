# Columba Data Persistence Skill

```yaml
---
name: columba-data-persistence
description: Room database architecture and data persistence patterns for Columba messenger. Use when implementing database schema, entities, DAOs, migrations, backup/restore, or working with Room database queries and relationships.
version: 1.0.0
author: Columba Development
tags: [room, database, persistence, android, columba]
---
```

## Overview

This skill provides comprehensive guidance for data persistence in the Columba Android messenger using **Room Persistence Library**. Room is Android's recommended database solution, providing a clean abstraction layer over SQLite with compile-time verification and type safety.

**Key Benefits:**
- **Compile-time SQL verification** - Catch errors at build time, not runtime
- **Reactive data with Flow** - UI updates automatically when data changes
- **Type-safe queries** - No string-based SQL injection risks
- **Migration support** - Structured database upgrades
- **Testing support** - In-memory databases for fast tests

## When to Use This Skill

Auto-triggers when working on:
- Database schema design (entities, relationships)
- DAO (Data Access Object) implementation
- Database migrations (version upgrades)
- Backup and restore functionality
- Complex queries (joins, aggregations)
- Database performance optimization
- Data model changes or refactoring
- Room database configuration

## Quick Reference

### Common Scenarios

#### Define an Entity
```kotlin
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationHash: String,
    val content: String,
    val timestamp: Long,
    @ColumnInfo(name = "is_from_me") val isFromMe: Boolean
)
```

#### Create a DAO
```kotlin
@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationHash = :hash ORDER BY timestamp DESC")
    fun getMessagesForConversation(hash: String): Flow<List<MessageEntity>>

    @Delete
    suspend fun delete(message: MessageEntity)
}
```

#### Database Class
```kotlin
@Database(entities = [MessageEntity::class, ConversationEntity::class], version = 1)
abstract class ColumbaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
}
```

#### Migration
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN title TEXT NOT NULL DEFAULT ''")
    }
}
```

## Core Concepts

### 1. Database Architecture

Columba uses a **relational database** with the following structure:

```
ColumbaDatabase
├── messages            (MessageEntity)
│   ├── Primary key: id (message hash)
│   └── Foreign key: conversationHash → conversations.peerHash
├── conversations       (ConversationEntity)
│   └── Primary key: peerHash
├── peers              (PeerEntity)
│   └── Primary key: peerHash
├── attachments        (AttachmentEntity)
│   ├── Primary key: id
│   └── Foreign key: messageId → messages.id
└── settings           (SettingsEntity)
    └── Primary key: key
```

### 2. Entity Design Principles

**Best Practices:**
- Use meaningful table names (`messages` not `message_table`)
- Primary keys should be immutable (message hash, peer hash)
- Use `@ColumnInfo(name = "snake_case")` for consistency with SQL
- Nullable fields should use `?` in Kotlin
- Use appropriate data types (Long for timestamps, String for hashes)
- Include `@Index` for frequently queried columns

**Example:**
```kotlin
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationHash"]),
        Index(value = ["timestamp"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["peerHash"],
            childColumns = ["conversationHash"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(...)
```

### 3. DAO Patterns

**Three Query Types:**

1. **Suspend functions** - For one-shot operations
   ```kotlin
   @Insert
   suspend fun insert(message: MessageEntity)
   ```

2. **Flow** - For reactive, observable data
   ```kotlin
   @Query("SELECT * FROM messages")
   fun getAllMessages(): Flow<List<MessageEntity>>
   ```

3. **Direct return** - For synchronous reads (avoid on main thread)
   ```kotlin
   @Query("SELECT COUNT(*) FROM messages")
   fun getMessageCount(): Int
   ```

**Use Flow for UI updates:**
```kotlin
// In ViewModel
val messages = messageDao.getMessages().stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = emptyList()
)

// In Composable
val messages by viewModel.messages.collectAsState()
```

### 4. Relationship Patterns

**One-to-Many: Conversation → Messages**

```kotlin
data class ConversationWithMessages(
    @Embedded val conversation: ConversationEntity,
    @Relation(
        parentColumn = "peerHash",
        entityColumn = "conversationHash"
    )
    val messages: List<MessageEntity>
)

@Query("SELECT * FROM conversations")
fun getConversationsWithMessages(): Flow<List<ConversationWithMessages>>
```

**Many-to-Many: Messages ↔ Attachments**

```kotlin
@Entity(
    tableName = "message_attachment_cross_ref",
    primaryKeys = ["messageId", "attachmentId"]
)
data class MessageAttachmentCrossRef(
    val messageId: String,
    val attachmentId: String
)

data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            MessageAttachmentCrossRef::class,
            parentColumn = "messageId",
            entityColumn = "attachmentId"
        )
    )
    val attachments: List<AttachmentEntity>
)
```

### 5. Migration Strategy

**Version Control:**
- Start at version 1
- Increment for each schema change
- **Never** skip versions
- Test migrations with real data

**Migration Types:**
1. **Additive** - Adding columns (safe, backward compatible)
2. **Destructive** - Removing columns (data loss, need backup)
3. **Transformative** - Changing data types (complex, needs migration logic)

**Migration Testing:**
```kotlin
@Test
fun testMigration1To2() {
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ColumbaDatabase::class.java
    )

    // Create database at version 1
    helper.createDatabase(TEST_DB, 1).apply {
        execSQL("INSERT INTO messages VALUES ('id1', 'hash1', 'content', 123)")
        close()
    }

    // Migrate to version 2
    helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

    // Verify migration
    helper.getMigrated Database(TEST_DB, 2).apply {
        val cursor = query("SELECT * FROM messages")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(cursor.getColumnIndex("title")))  // New column
        close()
    }
}
```

## Columba Database Schema

### MessageEntity (messages table)

```kotlin
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationHash"]),
        Index(value = ["timestamp"]),
        Index(value = ["state"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["peerHash"],
            childColumns = ["conversationHash"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,                     // LXM hash (hex string)

    @ColumnInfo(name = "conversation_hash")
    val conversationHash: String,       // Peer destination hash

    val content: String,                // Message content (UTF-8)
    val title: String = "",             // LXMF title field
    val timestamp: Long,                // Unix timestamp (milliseconds)

    @ColumnInfo(name = "is_from_me")
    val isFromMe: Boolean,              // Originator flag

    val state: Int,                     // LXMF message state (0x00-0xFF)
    val method: Int,                    // Delivery method (1-5)

    @ColumnInfo(name = "packed_lxm", typeAffinity = ColumnInfo.BLOB)
    val packedLxm: ByteArray,           // Full packed LXMF message

    val fields: String = "{}",          // JSON of LXMF fields
    val extras: String = "{}",          // JSON extras (rssi, snr, stamps)

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,        // Read status

    val progress: Float = 0f            // Delivery progress (0.0 to 1.0)
) {
    // ByteArray doesn't implement equals properly
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
```

### ConversationEntity (conversations table)

```kotlin
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["lastMessageTimestamp"])
    ]
)
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_hash")
    val peerHash: String,               // Destination hash (hex string)

    @ColumnInfo(name = "peer_name")
    val peerName: String,               // Display name

    @ColumnInfo(name = "last_message")
    val lastMessage: String,            // Last message preview

    @ColumnInfo(name = "last_message_timestamp")
    val lastMessageTimestamp: Long,     // Last activity timestamp

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,           // Unread message count

    @ColumnInfo(name = "is_trusted")
    val isTrusted: Boolean = false,     // Trust flag

    val appearance: String? = null,     // JSON [icon, fg_color, bg_color]

    @ColumnInfo(name = "send_telemetry")
    val sendTelemetry: Boolean = false, // Auto-send telemetry flag

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()  // Creation timestamp
)
```

### PeerEntity (peers table)

```kotlin
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_hash")
    val peerHash: String,               // Destination hash

    @ColumnInfo(name = "display_name")
    val displayName: String,            // Display name

    @ColumnInfo(name = "public_key", typeAffinity = ColumnInfo.BLOB)
    val publicKey: ByteArray? = null,   // RNS identity public key

    val appearance: String? = null,     // JSON appearance data

    @ColumnInfo(name = "last_seen")
    val lastSeen: Long? = null,         // Last announce/message timestamp

    @ColumnInfo(name = "is_announced")
    val isAnnounced: Boolean = false,   // Have path to peer

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
```

### AttachmentEntity (attachments table)

```kotlin
@Entity(
    tableName = "attachments",
    indices = [Index(value = ["messageId"])]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "message_id")
    val messageId: String,              // Associated message ID

    @ColumnInfo(name = "file_name")
    val fileName: String,               // Original filename

    @ColumnInfo(name = "file_path")
    val filePath: String,               // Local storage path

    @ColumnInfo(name = "mime_type")
    val mimeType: String,               // MIME type

    @ColumnInfo(name = "file_size")
    val fileSize: Long,                 // Size in bytes

    val hash: String? = null,           // File hash (for verification)

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
```

## Documentation Structure

### Quick Start
- **This file (SKILL.md)** - Overview and quick reference

### Deep Dive Guides (`docs/`)
1. **ROOM_ARCHITECTURE.md** - Database architecture and design patterns
2. **DAO_PATTERNS.md** - Advanced DAO queries and patterns
3. **MIGRATIONS.md** - Migration strategies and testing
4. **BACKUP_RESTORE.md** - Backup and restore implementation
5. **PERFORMANCE.md** - Query optimization and indexing
6. **TESTING.md** - Database testing strategies
7. **TROUBLESHOOTING.md** - Common issues and solutions

### Implementation Patterns (`patterns/`)
1. **entity-design.md** - Entity design best practices
2. **dao-operations.md** - DAO implementation patterns
3. **relationships.md** - One-to-many, many-to-many relationships
4. **migration-pattern.md** - Safe migration workflows

### Code Templates (`templates/`)
1. **ColumbaDatabase.kt** - Complete database class
2. **MessageDao.kt** - Message DAO with all operations
3. **ConversationDao.kt** - Conversation DAO
4. **DatabaseModule.kt** - Hilt dependency injection
5. **migration-template.kt** - Migration boilerplate

### Verification Checklists (`checklists/`)
1. **schema-checklist.md** - Database schema verification
2. **migration-checklist.md** - Migration safety checks
3. **performance-checklist.md** - Performance optimization

## Performance Targets

### Query Performance
- **Simple SELECT**: < 10ms (indexed columns)
- **Complex JOIN**: < 50ms (indexed relationships)
- **Large result set** (1000+ rows): Use paging
- **Write operations**: < 20ms per insert/update

### Database Size
- **Messages**: ~500 bytes/message average
- **10,000 messages**: ~5MB database size
- **100,000 messages**: ~50MB (acceptable on modern devices)
- **Attachments**: Store paths only, files in filesystem

### UI Responsiveness
- **Flow emission**: Immediate (< 5ms)
- **List scroll**: 60 FPS with 1000+ messages
- **Search**: < 100ms for full-text search

## Testing Strategy

### Unit Tests (DAO)
```kotlin
@Test
fun testInsertAndRetrieveMessage() = runTest {
    val message = MessageEntity(
        id = "test123",
        conversationHash = "peer123",
        content = "Hello",
        timestamp = System.currentTimeMillis(),
        isFromMe = true,
        state = 1,
        method = 2,
        packedLxm = byteArrayOf()
    )

    messageDao.insert(message)

    val messages = messageDao.getMessagesForConversation("peer123").first()
    assertEquals(1, messages.size)
    assertEquals("Hello", messages[0].content)
}
```

### Migration Tests
```kotlin
@Test
fun testAllMigrations() {
    helper.createDatabase(TEST_DB, 1).close()

    Room.databaseBuilder(context, ColumbaDatabase::class.java, TEST_DB)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
        .apply {
            openHelper.writableDatabase.close()
        }
}
```

### Integration Tests
- Test DAO with Repository
- Test ViewModel with real database
- Test complex queries end-to-end

## Common Patterns

### Pattern 1: Upsert (Insert or Update)

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsert(message: MessageEntity)

// Usage
messageDao.upsert(message)  // Inserts if new, updates if exists
```

### Pattern 2: Reactive Pagination

```kotlin
@Query("SELECT * FROM messages WHERE conversationHash = :hash ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
fun getMessagesPaged(hash: String, limit: Int, offset: Int): Flow<List<MessageEntity>>

// ViewModel
fun loadMoreMessages() {
    viewModelScope.launch {
        offset += 50
        messageDao.getMessagesPaged(conversationHash, 50, offset).collect { newMessages ->
            _messages.value = _messages.value + newMessages
        }
    }
}
```

### Pattern 3: Bulk Operations

```kotlin
@Insert
suspend fun insertAll(messages: List<MessageEntity>)

@Transaction
suspend fun updateConversationWithMessages(conversation: ConversationEntity, messages: List<MessageEntity>) {
    conversationDao.insert(conversation)
    messageDao.insertAll(messages)
}
```

### Pattern 4: Conditional Queries

```kotlin
@Query("""
    SELECT * FROM messages
    WHERE conversationHash = :hash
    AND (:unreadOnly = 0 OR isRead = 0)
    ORDER BY timestamp DESC
""")
fun getMessages(hash: String, unreadOnly: Boolean): Flow<List<MessageEntity>>
```

## Integration with Other Skills

**Related Skills:**
- **columba-lxmf-messaging**: Message entities match LXMF data model
- **columba-threading-redesign**: Use Dispatchers.IO for database operations
- **kotlin-android-chaquopy-testing**: Test database with in-memory DB
- **jetpack-compose-ui**: Use Flow for reactive UI updates

## Key Takeaways

1. **Use Flow for reactive data** - UI updates automatically
2. **Index frequently queried columns** - Essential for performance
3. **Test migrations** - Use MigrationTestHelper
4. **Use transactions for multi-step operations** - Ensures atomicity
5. **Store large data separately** - Keep database lean (paths not files)
6. **Suspend functions for writes** - Never block main thread
7. **Use relationships sparingly** - Can impact performance
8. **Monitor database size** - Implement cleanup/archival if needed

## Next Steps

### For New Implementation
1. Read `docs/ROOM_ARCHITECTURE.md` for architecture overview
2. Copy `templates/ColumbaDatabase.kt` as starting point
3. Design entities using `patterns/entity-design.md`
4. Implement DAOs using `patterns/dao-operations.md`
5. Set up Hilt injection with `templates/DatabaseModule.kt`
6. Run through `checklists/schema-checklist.md`

### For Schema Changes
1. Review current schema
2. Design migration using `patterns/migration-pattern.md`
3. Implement migration class
4. Test with `checklists/migration-checklist.md`
5. Update entity versions and docs

## Additional Resources

- **Room Documentation**: https://developer.android.com/training/data-storage/room
- **Android Data Storage Guide**: https://developer.android.com/guide/topics/data
- **Room Migration Guide**: https://developer.android.com/training/data-storage/room/migrating-db-versions
- **context7 MCP**: Query latest Room documentation via MCP

## Skill Maintenance

**Last Updated**: 2025-10-30
**Room Version**: Compatible with Room 2.6.x+
**Kotlin Version**: 1.9.x+
**Columba Target**: Android 15+ (SDK 35)

**Update Triggers:**
- Room library major version updates
- Database schema changes
- New entity requirements
- Performance optimization discoveries
- Android best practices updates
