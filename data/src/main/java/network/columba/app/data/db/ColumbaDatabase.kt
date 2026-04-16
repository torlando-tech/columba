package network.columba.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import network.columba.app.data.db.dao.AnnounceDao
import network.columba.app.data.db.dao.BlockedPeerDao
import network.columba.app.data.db.dao.ContactDao
import network.columba.app.data.db.dao.ConversationDao
import network.columba.app.data.db.dao.CustomThemeDao
import network.columba.app.data.db.dao.DraftDao
import network.columba.app.data.db.dao.InterfaceFirstSeenDao
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.OfflineMapRegionDao
import network.columba.app.data.db.dao.PeerIconDao
import network.columba.app.data.db.dao.PeerIdentityDao
import network.columba.app.data.db.dao.ReceivedLocationDao
import network.columba.app.data.db.dao.RmspServerDao
import network.columba.app.data.db.entity.AnnounceEntity
import network.columba.app.data.db.entity.BlockedPeerEntity
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.CustomThemeEntity
import network.columba.app.data.db.entity.DraftEntity
import network.columba.app.data.db.entity.InterfaceFirstSeenEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.OfflineMapRegionEntity
import network.columba.app.data.db.entity.PeerIconEntity
import network.columba.app.data.db.entity.PeerIdentityEntity
import network.columba.app.data.db.entity.ReceivedLocationEntity
import network.columba.app.data.db.entity.RmspServerEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AnnounceEntity::class,
        PeerIdentityEntity::class,
        PeerIconEntity::class,
        ContactEntity::class,
        CustomThemeEntity::class,
        LocalIdentityEntity::class,
        ReceivedLocationEntity::class,
        OfflineMapRegionEntity::class,
        RmspServerEntity::class,
        DraftEntity::class,
        BlockedPeerEntity::class,
        InterfaceFirstSeenEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ColumbaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun announceDao(): AnnounceDao

    abstract fun peerIdentityDao(): PeerIdentityDao

    abstract fun peerIconDao(): PeerIconDao

    abstract fun contactDao(): ContactDao

    abstract fun customThemeDao(): CustomThemeDao

    abstract fun localIdentityDao(): LocalIdentityDao

    abstract fun receivedLocationDao(): ReceivedLocationDao

    abstract fun offlineMapRegionDao(): OfflineMapRegionDao

    abstract fun rmspServerDao(): RmspServerDao

    abstract fun draftDao(): DraftDao

    abstract fun blockedPeerDao(): BlockedPeerDao

    abstract fun interfaceFirstSeenDao(): InterfaceFirstSeenDao
}
