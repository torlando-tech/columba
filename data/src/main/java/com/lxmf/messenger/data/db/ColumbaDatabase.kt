package com.lxmf.messenger.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ContactDao
import com.lxmf.messenger.data.db.dao.ConversationDao
import com.lxmf.messenger.data.db.dao.CustomThemeDao
import com.lxmf.messenger.data.db.dao.DraftDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.dao.MessageDao
import com.lxmf.messenger.data.db.dao.OfflineMapRegionDao
import com.lxmf.messenger.data.db.dao.PeerIconDao
import com.lxmf.messenger.data.db.dao.PeerIdentityDao
import com.lxmf.messenger.data.db.dao.ReceivedLocationDao
import com.lxmf.messenger.data.db.dao.RmspServerDao
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.CustomThemeEntity
import com.lxmf.messenger.data.db.entity.DraftEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.db.entity.OfflineMapRegionEntity
import com.lxmf.messenger.data.db.entity.PeerIconEntity
import com.lxmf.messenger.data.db.entity.PeerIdentityEntity
import com.lxmf.messenger.data.db.entity.ReceivedLocationEntity
import com.lxmf.messenger.data.db.entity.RmspServerEntity

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
    ],
    version = 36,
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
}
