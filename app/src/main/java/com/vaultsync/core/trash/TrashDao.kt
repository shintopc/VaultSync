package com.vaultsync.core.trash

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash_entries ORDER BY deletedAt DESC")
    suspend fun getAll(): List<TrashEntity>

    @Query("SELECT COUNT(*) FROM trash_entries")
    suspend fun getCount(): Int

    @Query("SELECT SUM(sizeBytes) FROM trash_entries")
    suspend fun getTotalSizeBytes(): Long?

    @Query("SELECT * FROM trash_entries WHERE id = :id")
    suspend fun getById(id: String): TrashEntity?

    @Query("SELECT * FROM trash_entries WHERE autoDeleteAt <= :now")
    suspend fun getExpired(now: Long): List<TrashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TrashEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<TrashEntity>)

    @Query("DELETE FROM trash_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM trash_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM trash_entries WHERE autoDeleteAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM trash_entries")
    suspend fun deleteAll()
}
