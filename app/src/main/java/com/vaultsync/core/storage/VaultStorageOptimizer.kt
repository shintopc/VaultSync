package com.vaultsync.core.storage

import com.vaultsync.core.database.VaultDatabase
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.security.VaultFileDecryptor
import com.vaultsync.core.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class DuplicateGroup(
    val contentHash: String,
    val fileSize: Long,
    val entries: List<VaultEntry>,
    val wastedBytes: Long,
)

data class StorageOptimizationReport(
    val duplicateGroups: List<DuplicateGroup>,
    val totalDuplicateCount: Int,
    val totalWastedBytes: Long,
)

object VaultStorageOptimizer {

    suspend fun analyzeStorage(
        database: VaultDatabase,
        storageManager: VaultStorageManager,
        vmk: ByteArray,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): StorageOptimizationReport = withContext(Dispatchers.IO) {
        val entryDao = database.entryDao()
        val allActive = entryDao.getAllActiveList()

        if (allActive.size < 2) {
            return@withContext StorageOptimizationReport(emptyList(), 0, 0L)
        }

        // Group by exact original file size first (instant O(N) filter)
        val sizeBuckets = allActive.groupBy { it.originalSizeBytes }.filter { it.value.size > 1 }
        val candidateCount = sizeBuckets.values.sumOf { it.size }
        var processed = 0

        val digest = MessageDigest.getInstance("SHA-256")
        val hashMap = mutableMapOf<String, MutableList<VaultEntry>>()

        for ((size, entries) in sizeBuckets) {
            for (entity in entries) {
                val blobFile = storageManager.getEncryptedBlobFile(entity.id)
                val hash = if (!entity.contentHash.isNullOrBlank()) {
                    entity.contentHash
                } else if (blobFile.exists()) {
                    val sample = VaultFileDecryptor.readSamplePlaintext(blobFile, vmk, 4096)
                    val computedHash = if (sample.isNotEmpty()) {
                        digest.reset()
                        digest.update(sample)
                        // Also mix in original file size bytes
                        digest.digest(java.nio.ByteBuffer.allocate(8).putLong(size).array())
                            .joinToString("") { "%02x".format(it) }
                    } else {
                        "size_${size}_${entity.id}"
                    }
                    try {
                        entryDao.updateContentHash(entity.id, computedHash)
                    } catch (_: Exception) {}
                    computedHash
                } else {
                    "missing_${entity.id}"
                }

                val entry = VaultEntry(
                    id = entity.id,
                    folderId = entity.folderId,
                    originalFileName = entity.originalFileName,
                    category = com.vaultsync.core.model.VaultCategory.entries.firstOrNull { it.name == entity.category }
                        ?: com.vaultsync.core.model.VaultCategory.Other,
                    mimeType = entity.mimeType,
                    originalSizeBytes = entity.originalSizeBytes,
                    encryptedSizeBytes = entity.encryptedSizeBytes,
                    createdAtMillis = entity.createdAtMillis,
                    dateModifiedMillis = entity.dateModifiedMillis,
                    isInUse = entity.isInUse,
                    isTrash = entity.isTrash,
                    deletedAtMillis = entity.deletedAtMillis,
                    contentHash = hash,
                )

                hashMap.getOrPut(hash) { mutableListOf() }.add(entry)
                processed++
                onProgress?.invoke(processed, candidateCount)
            }
        }

        val duplicateGroups = hashMap.values
            .filter { it.size > 1 }
            .map { list ->
                val size = list.first().originalSizeBytes
                val wasted = size * (list.size - 1)
                DuplicateGroup(
                    contentHash = list.first().contentHash ?: "",
                    fileSize = size,
                    entries = list.sortedBy { it.createdAtMillis }, // oldest first as primary
                    wastedBytes = wasted,
                )
            }
            .sortedByDescending { it.wastedBytes }

        val totalDuplicateCount = duplicateGroups.sumOf { it.entries.size - 1 }
        val totalWastedBytes = duplicateGroups.sumOf { it.wastedBytes }

        StorageOptimizationReport(
            duplicateGroups = duplicateGroups,
            totalDuplicateCount = totalDuplicateCount,
            totalWastedBytes = totalWastedBytes,
        )
    }

    suspend fun removeDuplicates(
        repository: VaultRepository,
        duplicateGroups: List<DuplicateGroup>,
        moveToTrash: Boolean = true,
    ): Int = withContext(Dispatchers.IO) {
        var removed = 0
        for (group in duplicateGroups) {
            // Keep the first entry (original), delete subsequent duplicates
            val toRemove = group.entries.drop(1)
            for (entry in toRemove) {
                if (moveToTrash) {
                    repository.moveToTrash(entry.id)
                } else {
                    repository.deleteEntry(entry.id)
                }
                removed++
            }
        }
        return@withContext removed
    }
}

