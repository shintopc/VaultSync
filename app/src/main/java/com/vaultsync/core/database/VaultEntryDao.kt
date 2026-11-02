package com.vaultsync.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultEntryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: VaultEntryEntity)

    @Update
    suspend fun update(entry: VaultEntryEntity)

    @Query("SELECT * FROM vault_entries WHERE id = :id")
    suspend fun getById(id: String): VaultEntryEntity?

    @Query("SELECT * FROM vault_entries WHERE (is_trash = 0 OR is_trash IS NULL) AND folder_id IS :folderId ORDER BY created_at DESC")
    fun getByFolderId(folderId: String?): Flow<List<VaultEntryEntity>>

    @Query("SELECT * FROM vault_entries WHERE (is_trash = 0 OR is_trash IS NULL) AND category = :category ORDER BY created_at DESC")
    fun getByCategory(category: String): Flow<List<VaultEntryEntity>>

    @Query("SELECT * FROM vault_entries WHERE (is_trash = 0 OR is_trash IS NULL) ORDER BY created_at DESC")
    fun getAll(): Flow<List<VaultEntryEntity>>

    @Query("SELECT * FROM vault_entries WHERE is_trash = 1 ORDER BY deleted_at DESC")
    fun getTrashEntries(): Flow<List<VaultEntryEntity>>

    @Query("SELECT COUNT(*) FROM vault_entries WHERE is_trash = 1")
    fun getTrashCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM vault_entries WHERE is_trash = 1")
    suspend fun getTrashCountDirect(): Int

    @Query("SELECT COUNT(*) FROM vault_entries WHERE (is_trash = 0 OR is_trash IS NULL) AND folder_id = :folderId")
    suspend fun getCountInFolder(folderId: String): Int

    @Query("UPDATE vault_entries SET is_in_use = :inUse WHERE id = :id")
    suspend fun setInUse(id: String, inUse: Boolean)

    @Query("UPDATE vault_entries SET folder_id = :targetFolderId, date_modified = :modifiedTime WHERE id = :id")
    suspend fun updateFolderId(id: String, targetFolderId: String?, modifiedTime: Long)

    @Query("UPDATE vault_entries SET is_trash = 1, deleted_at = :deletedAt WHERE id = :id")
    suspend fun moveToTrash(id: String, deletedAt: Long)

    @Query("UPDATE vault_entries SET is_trash = 0, deleted_at = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    @Query("SELECT * FROM vault_entries WHERE is_trash = 1 AND deleted_at < :cutoff")
    suspend fun getExpiredTrash(cutoff: Long): List<VaultEntryEntity>

    @Query("DELETE FROM vault_entries WHERE is_trash = 1")
    suspend fun emptyTrash()

    @Query("DELETE FROM vault_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT id FROM vault_entries")
    suspend fun getAllEntryIds(): List<String>

    @Query("UPDATE vault_entries SET content_hash = :hash WHERE id = :id")
    suspend fun updateContentHash(id: String, hash: String)

    @Query("SELECT * FROM vault_entries WHERE (is_trash = 0 OR is_trash IS NULL)")
    suspend fun getAllActiveList(): List<VaultEntryEntity>
}
