package com.lxmf.messenger.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lxmf.messenger.data.config.ConfigFileParser
import com.lxmf.messenger.data.database.dao.InterfaceDao
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.toJsonString
import com.lxmf.messenger.reticulum.model.typeName
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Provider

/**
 * Room database for storing Reticulum network interface configurations.
 */
@Database(
    entities = [InterfaceEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class InterfaceDatabase : RoomDatabase() {
    abstract fun interfaceDao(): InterfaceDao

    companion object {
        /**
         * Migration from version 1 to version 2.
         * Adds the BetweenTheBorders testnet interface and renames the existing testnet interface.
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Rename the existing "testnet" interface to "RNS Testnet Dublin"
                    db.execSQL(
                        """
                    UPDATE interfaces
                    SET name = 'RNS Testnet Dublin', displayOrder = 2
                    WHERE name = 'testnet' AND type = 'TCPClient'
                """,
                    )

                    // Update displayOrder for existing interfaces to make room
                    db.execSQL(
                        """
                    UPDATE interfaces
                    SET displayOrder = displayOrder + 1
                    WHERE displayOrder >= 2 AND name != 'RNS Testnet Dublin'
                """,
                    )

                    // Insert the BetweenTheBorders interface if it doesn't already exist
                    // First check if it exists
                    val cursor = db.query("SELECT COUNT(*) FROM interfaces WHERE name = 'RNS Testnet BetweenTheBorders'")
                    cursor.moveToFirst()
                    val count = cursor.getInt(0)
                    cursor.close()

                    if (count == 0) {
                        db.execSQL(
                            """
                        INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                        VALUES (?, ?, ?, ?, ?)
                    """,
                            arrayOf(
                                "RNS Testnet BetweenTheBorders",
                                "TCPClient",
                                1, // true
                                """{"target_host":"reticulum.betweentheborders.com","target_port":4242,"kiss_framing":false,"mode":"full"}""",
                                3,
                            ),
                        )
                    }
                }
            }

        /**
         * Migration from version 2 to version 3.
         * Replaces Dublin testnet with Sideband public server and removes BetweenTheBorders testnet.
         */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Update Dublin testnet to Sideband server
                    db.execSQL(
                        """
                        UPDATE interfaces
                        SET name = 'Sideband Server',
                            configJson = '{"target_host":"sideband.connect.reticulum.network","target_port":4965,"kiss_framing":false,"mode":"full"}'
                        WHERE name = 'RNS Testnet Dublin' AND type = 'TCPClient'
                    """,
                    )

                    // Remove BetweenTheBorders testnet
                    db.execSQL(
                        """
                        DELETE FROM interfaces
                        WHERE name = 'RNS Testnet BetweenTheBorders' AND type = 'TCPClient'
                    """,
                    )
                }
            }
    }

    /**
     * Callback to populate the database with default data on creation.
     * Imports interfaces from existing config file if present, otherwise creates default.
     */
    class Callback(
        private val context: Context,
        private val database: Provider<InterfaceDatabase>,
        private val applicationScope: CoroutineScope,
    ) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)

            // Populate database synchronously using raw SQL to avoid transaction conflicts
            // Room's onCreate() runs in a transaction, so we can't use DAO suspend methods
            // which try to create their own transactions
            populateDatabaseDirect(db)
        }

        /**
         * Populate database directly using raw SQL inserts.
         * This is necessary because onCreate() runs inside a transaction,
         * and we can't use Room's DAO suspend methods which create their own transactions.
         */
        private fun populateDatabaseDirect(db: SupportSQLiteDatabase) {
            // Check if Reticulum config file exists
            val configFile = File(context.filesDir, "reticulum/config")

            if (configFile.exists()) {
                android.util.Log.i("InterfaceDatabase", "Found existing config file, importing interfaces...")
                try {
                    val interfaces = ConfigFileParser.parseConfigFile(configFile)

                    if (interfaces.isEmpty()) {
                        android.util.Log.w("InterfaceDatabase", "Config file exists but no interfaces found, creating default")
                        insertDefaultInterfacesDirect(db)
                    } else {
                        interfaces.forEachIndexed { index, config ->
                            insertInterfaceDirect(db, config, index)
                        }
                        android.util.Log.d("InterfaceDatabase", "Imported ${interfaces.size} interface(s) from config file")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InterfaceDatabase", "Error importing config file, creating default", e)
                    insertDefaultInterfacesDirect(db)
                }
            } else {
                android.util.Log.i("InterfaceDatabase", "No config file found, creating default AutoInterface")
                insertDefaultInterfacesDirect(db)
            }
        }

        /**
         * Insert default interfaces using raw SQL.
         */
        private fun insertDefaultInterfacesDirect(db: SupportSQLiteDatabase) {
            // Insert AutoInterface
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf(
                    "Auto Discovery",
                    "AutoInterface",
                    1, // true
                    """{"group_id":"","discovery_scope":"link","mode":"full"}""",
                    0,
                ),
            )

            // Insert AndroidBLE
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf(
                    "Bluetooth LE",
                    "AndroidBLE",
                    1, // true
                    """{"device_name":"Reticulum-Android","max_connections":7,"mode":"full"}""",
                    1,
                ),
            )

            // Insert Sideband Server
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf(
                    "Sideband Server",
                    "TCPClient",
                    1, // true
                    """{"target_host":"sideband.connect.reticulum.network","target_port":4965,"kiss_framing":false,"mode":"full"}""",
                    2,
                ),
            )
        }

        /**
         * Insert interface using raw SQL.
         */
        private fun insertInterfaceDirect(
            db: SupportSQLiteDatabase,
            config: InterfaceConfig,
            displayOrder: Int,
        ) {
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf(
                    config.name,
                    config.typeName,
                    if (config.enabled) 1 else 0,
                    config.toJsonString(),
                    displayOrder,
                ),
            )
        }

        /**
         * Import interfaces from existing config file, or create default AutoInterface.
         * This enables migration from manual config files to database-driven configuration.
         */
        private suspend fun populateDatabase(interfaceDao: InterfaceDao) {
            // Check if Reticulum config file exists
            val configFile = File(context.filesDir, "reticulum/config")

            if (configFile.exists()) {
                // Import from existing config file
                android.util.Log.i("InterfaceDatabase", "Found existing config file, importing interfaces...")

                try {
                    val interfaces = ConfigFileParser.parseConfigFile(configFile)

                    if (interfaces.isEmpty()) {
                        android.util.Log.w("InterfaceDatabase", "Config file exists but no interfaces found, creating default")
                        createDefaultInterface(interfaceDao)
                    } else {
                        interfaces.forEachIndexed { index, config ->
                            val entity = configToEntity(config, index)
                            interfaceDao.insertInterface(entity)
                        }
                        android.util.Log.d("InterfaceDatabase", "Imported ${interfaces.size} interface(s) from config file")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InterfaceDatabase", "Error importing config file, creating default", e)
                    createDefaultInterface(interfaceDao)
                }
            } else {
                // No config file, create default AutoInterface
                android.util.Log.i("InterfaceDatabase", "No config file found, creating default AutoInterface")
                createDefaultInterface(interfaceDao)
            }
        }

        /**
         * Create default interface configurations.
         * Creates AutoInterface, AndroidBLE, and TCPClient interfaces by default.
         */
        private suspend fun createDefaultInterface(interfaceDao: InterfaceDao) {
            val defaultAutoInterface =
                InterfaceEntity(
                    name = "Auto Discovery",
                    type = "AutoInterface",
                    enabled = true,
                    configJson =
                        """
                        {
                            "group_id": "",
                            "discovery_scope": "link",
                            "mode": "full"
                        }
                        """.trimIndent(),
                    displayOrder = 0,
                )

            val defaultBleInterface =
                InterfaceEntity(
                    name = "Bluetooth LE",
                    type = "AndroidBLE",
                    enabled = true,
                    configJson =
                        """
                        {
                            "device_name": "Reticulum-Android",
                            "max_connections": 7,
                            "mode": "full"
                        }
                        """.trimIndent(),
                    displayOrder = 1,
                )

            val sidebandServerInterface =
                InterfaceEntity(
                    name = "Sideband Server",
                    type = "TCPClient",
                    enabled = true,
                    configJson =
                        """
                        {
                            "target_host": "sideband.connect.reticulum.network",
                            "target_port": 4965,
                            "kiss_framing": false,
                            "mode": "full"
                        }
                        """.trimIndent(),
                    displayOrder = 2,
                )

            interfaceDao.insertInterface(defaultAutoInterface)
            interfaceDao.insertInterface(defaultBleInterface)
            interfaceDao.insertInterface(sidebandServerInterface)
        }

        /**
         * Convert InterfaceConfig to InterfaceEntity.
         */
        private fun configToEntity(
            config: InterfaceConfig,
            displayOrder: Int,
        ): InterfaceEntity =
            InterfaceEntity(
                name = config.name,
                type = config.typeName,
                enabled = config.enabled,
                configJson = config.toJsonString(),
                displayOrder = displayOrder,
            )
    }
}
