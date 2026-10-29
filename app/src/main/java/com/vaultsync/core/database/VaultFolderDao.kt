package com.vaultsync.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultFolderDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: VaultFolderEntity)

    @Update
    suspend fun update(folder: VaultFolderEntity)

    @Query("SELECT * FROM vault_folders WHERE id = :id")
    suspend fun getById(id: String): VaultFolderEntity?

    @Query("SELECT * FROM vault_folders WHERE parent_id IS :parentId ORDER BY name COLLATE NOCASE ASC")
    fun getByParentId(parentId: String?): Flow<List<VaultFolderEntity>>

    @Query("SELECT * FROM vault_folders ORDER BY name COLLATE NOCASE ASC")
    fun getAll(): Flow<List<VaultFolderEntity>>

    @Query("SELECT COUNT(*) FROM vault_folders WHERE parent_id = :folderId")
    suspend fun getSubfolderCount(folderId: String): Int

    @Query("UPDATE vault_folders SET parent_id = :newParentId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateParentId(id: String, newParentId: String?, updatedAt: Long)

    @Query("DELETE FROM vault_folders WHERE id = :id")
    suspend fun deleteById(id: String)
}
