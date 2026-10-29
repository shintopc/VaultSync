package com.vaultsync.core.manifest

import android.util.Log
import androidx.room.withTransaction
import com.vaultsync.core.database.VaultDatabase
import com.vaultsync.core.database.VaultEntryEntity
import com.vaultsync.core.database.VaultFolderEntity
import com.vaultsync.core.security.VaultCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

object VaultManifestManager {
    private const val TAG = "VaultManifestManager"

    fun writeManifest(manifestFile: File, manifest: VaultManifest) {
        val parent = manifestFile.parentFile ?: throw IllegalArgumentException("Manifest file must have a parent directory")
        parent.mkdirs()

        val tempFile = File(parent, "${manifestFile.name}.${UUID.randomUUID()}.tmp")
        try {
            val bytes = VaultManifestSerializer.serializeManifest(manifest)
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fsync manifest file: ${e.message}")
                }
            }

            if (manifestFile.exists()) {
                manifestFile.delete()
            }
            if (!tempFile.renameTo(manifestFile)) {
                tempFile.copyTo(manifestFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }

    fun readManifest(manifestFile: File): VaultManifest? {
        if (!manifestFile.exists() || manifestFile.length() < 32) {
            return null
        }
        return try {
            val bytes = manifestFile.readBytes()
            VaultManifestSerializer.deserializeManifest(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read vault manifest from ${manifestFile.absolutePath}: ${e.message}")
            null
        }
    }

    suspend fun createManifestFromCurrentVault(
        database: VaultDatabase,
        credentialStore: VaultCredentialStore,
        vmk: ByteArray,
        existingVaultId: String? = null,
    ): VaultManifest = withContext(Dispatchers.IO) {
        val pinWrapped = credentialStore.getWrappedVmk()
            ?: throw IllegalStateException("CredentialStore does not contain wrapped master key")
        val recWrapped = credentialStore.getRecoveryWrappedVmk()

        val folderEntities = database.folderDao().getAll().first()
        val entryEntities = database.entryDao().getAll().first()

        val catalogFolders = folderEntities.map { entity ->
            CatalogFolder(
                id = entity.id,
                name = entity.name,
                parentId = entity.parentId,
                createdAtMillis = entity.createdAtMillis,
                updatedAtMillis = entity.updatedAtMillis,
            )
        }

        val catalogEntries = entryEntities.map { entity ->
            CatalogEntry(
                id = entity.id,
                folderId = entity.folderId,
                originalFileName = entity.originalFileName,
                category = entity.category,
                mimeType = entity.mimeType,
                originalSizeBytes = entity.originalSizeBytes,
                encryptedSizeBytes = entity.encryptedSizeBytes,
                createdAtMillis = entity.createdAtMillis,
                dateModifiedMillis = entity.dateModifiedMillis,
            )
        }

        val catalog = VaultCatalog(
            folders = catalogFolders,
            entries = catalogEntries,
            exportedAtMillis = System.currentTimeMillis(),
        )

        val vaultId = existingVaultId ?: UUID.randomUUID().toString()
        val (nonce, encryptedCatalog) = VaultManifestSerializer.encryptCatalog(
            catalog = catalog,
            vmk = vmk,
            vaultId = vaultId,
            version = VaultManifest.FORMAT_VERSION,
        )

        val now = System.currentTimeMillis()
        VaultManifest(
            formatVersion = VaultManifest.FORMAT_VERSION,
            vaultId = vaultId,
            createdAtMillis = now,
            updatedAtMillis = now,
            wrappedVmkWithPin = pinWrapped,
            wrappedVmkWithRecoveryKey = recWrapped,
            catalogNonce = nonce,
            encryptedCatalog = encryptedCatalog,
        )
    }

    suspend fun reconstructDatabaseFromCatalog(
        database: VaultDatabase,
        catalog: VaultCatalog,
        existingBlobChecker: ((String) -> Boolean)? = null,
    ): Int = withContext(Dispatchers.IO) {
        var restoredCount = 0
        database.withTransaction {
            val folderDao = database.folderDao()
            val entryDao = database.entryDao()

            // 1. Rebuild folders (preserve hierarchies)
            // Insert parent folders first to respect foreign keys
            val remainingFolders = catalog.folders.toMutableList()
            var pass = 0
            while (remainingFolders.isNotEmpty() && pass < 50) {
                pass++
                val iterator = remainingFolders.iterator()
                while (iterator.hasNext()) {
                    val folder = iterator.next()
                    if (folder.parentId == null || folderDao.getById(folder.parentId) != null) {
                        folderDao.insert(folder.toEntity())
                        iterator.remove()
                    }
                }
            }
            // If any cyclical or orphaned folders remain, insert with parentId = null
            for (orphan in remainingFolders) {
                folderDao.insert(orphan.toEntity().copy(parentId = null))
            }

            // 2. Rebuild entries
            for (entry in catalog.entries) {
                // If blob checker is provided, only restore entries whose encrypted blobs are on disk
                if (existingBlobChecker != null && !existingBlobChecker(entry.id)) {
                    Log.w(TAG, "Skipping entry ${entry.originalFileName} (blob ${entry.id}.vsf not found on disk)")
                    continue
                }

                // If folder doesn't exist, place in root
                val validFolderId = if (entry.folderId != null && folderDao.getById(entry.folderId) != null) {
                    entry.folderId
                } else {
                    null
                }

                entryDao.insert(entry.toEntity().copy(folderId = validFolderId))
                restoredCount++
            }
        }
        restoredCount
    }
}
