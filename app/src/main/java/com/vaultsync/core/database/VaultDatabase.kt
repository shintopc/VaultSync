package com.vaultsync.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        VaultFolderEntity::class,
        VaultEntryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun folderDao(): VaultFolderDao
    abstract fun entryDao(): VaultEntryDao

    companion object {
        private const val DB_NAME = "vaultsync_metadata.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_entries ADD COLUMN is_trash INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vault_entries ADD COLUMN deleted_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE vault_entries ADD COLUMN content_hash TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vault_entries_is_trash ON vault_entries(is_trash)")
            }
        }

        fun create(context: Context, inMemory: Boolean = false): VaultDatabase {
            return if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            } else {
                Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, DB_NAME)
                    .addMigrations(MIGRATION_1_2)
                    .build()
            }
        }
    }
}
