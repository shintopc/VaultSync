package com.vaultsync.core.trash

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrashEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TrashDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao

    companion object {
        private const val DB_NAME = "vaultsync_trash.db"

        @Volatile
        private var instance: TrashDatabase? = null

        fun getInstance(context: Context): TrashDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrashDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
        }
    }
}
