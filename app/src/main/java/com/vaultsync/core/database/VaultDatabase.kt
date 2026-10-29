package com.vaultsync.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VaultFolderEntity::class,
        VaultEntryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun folderDao(): VaultFolderDao
    abstract fun entryDao(): VaultEntryDao

    companion object {
        private const val DB_NAME = "vaultsync_metadata.db"

        fun create(context: Context, inMemory: Boolean = false): VaultDatabase {
            return if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            } else {
                Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, DB_NAME)
                    .build()
            }
        }
    }
}
