package com.vaultsync.core.vault

import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.model.VaultFolder
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream

interface VaultRepository {
    fun getFolders(parentId: String?): Flow<List<VaultFolder>>
    fun getAllFolders(): Flow<List<VaultFolder>>
    suspend fun getFolder(folderId: String): VaultFolder?
    suspend fun createFolder(name: String, parentId: String? = null): VaultFolder
    suspend fun renameFolder(folderId: String, newName: String)
    suspend fun moveFolder(folderId: String, newParentId: String?)
    suspend fun deleteFolder(folderId: String)

    fun getEntries(folderId: String?): Flow<List<VaultEntry>>
    fun getEntriesByCategory(category: VaultCategory): Flow<List<VaultEntry>>
    fun getAllEntries(): Flow<List<VaultEntry>>
    suspend fun getEntry(id: String): VaultEntry?

    suspend fun importFile(
        sourceFile: File,
        displayName: String? = null,
        destinationFolderId: String? = null,
        vmk: ByteArray,
        preserveOriginal: Boolean = true,
    ): VaultEntry

    suspend fun importStream(
        inputStream: InputStream,
        displayName: String,
        originalSize: Long,
        mimeType: String?,
        destinationFolderId: String? = null,
        vmk: ByteArray,
    ): VaultEntry

    suspend fun renameEntry(entryId: String, newName: String)
    suspend fun copyEntry(entryId: String, targetFolderId: String?, vmk: ByteArray): VaultEntry
    suspend fun deleteEntry(entryId: String)
    suspend fun moveEntry(entryId: String, targetFolderId: String?)
    suspend fun setEntryInUse(entryId: String, inUse: Boolean)
    suspend fun exportOrDecrypt(entryId: String, destinationFile: File, vmk: ByteArray)
    suspend fun cleanupOrphanedBlobs()
}
