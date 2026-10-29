package com.vaultsync.core.vault

import com.vaultsync.core.database.VaultDatabase
import com.vaultsync.core.database.VaultEntryDao
import com.vaultsync.core.database.VaultEntryEntity
import com.vaultsync.core.database.VaultFolderDao
import com.vaultsync.core.database.VaultFolderEntity
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.model.VaultFolder
import com.vaultsync.core.security.VaultFileDecryptor
import com.vaultsync.core.security.VaultFileEncryptor
import com.vaultsync.core.storage.VaultStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID

class AndroidVaultRepository(
    private val folderDao: VaultFolderDao,
    private val entryDao: VaultEntryDao,
    private val storageManager: VaultStorageManager,
) : VaultRepository {

    constructor(
        database: VaultDatabase,
        storageManager: VaultStorageManager,
    ) : this(
        folderDao = database.folderDao(),
        entryDao = database.entryDao(),
        storageManager = storageManager,
    )

    init {
        storageManager.initDirectories()
    }

    override fun getFolders(parentId: String?): Flow<List<VaultFolder>> {
        return folderDao.getByParentId(parentId).map { entities ->
            entities.map { it.toVaultFolder() }
        }.distinctUntilChanged()
    }

    override fun getAllFolders(): Flow<List<VaultFolder>> {
        return folderDao.getAll().map { entities ->
            entities.map { it.toVaultFolder() }
        }.distinctUntilChanged()
    }

    override suspend fun getFolder(folderId: String): VaultFolder? = withContext(Dispatchers.IO) {
        val entity = folderDao.getById(folderId) ?: return@withContext null
        val subCount = folderDao.getSubfolderCount(folderId)
        val itemCount = entryDao.getCountInFolder(folderId)
        entity.toVaultFolder(itemCount = itemCount, subfolderCount = subCount)
    }

    override suspend fun createFolder(name: String, parentId: String?): VaultFolder = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Folder name cannot be blank" }

        if (parentId != null) {
            val parent = folderDao.getById(parentId)
            requireNotNull(parent) { "Parent folder with ID $parentId does not exist" }
        }

        val now = System.currentTimeMillis()
        val entity = VaultFolderEntity(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            parentId = parentId,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        folderDao.insert(entity)
        entity.toVaultFolder()
    }

    override suspend fun renameFolder(folderId: String, newName: String) = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        require(cleanName.isNotBlank()) { "Folder name cannot be blank" }

        val existing = folderDao.getById(folderId)
            ?: throw IllegalArgumentException("Folder with ID $folderId does not exist")

        folderDao.update(
            existing.copy(
                name = cleanName,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun moveFolder(folderId: String, newParentId: String?): Unit = withContext(Dispatchers.IO) {
        if (folderId == newParentId) {
            throw IllegalArgumentException("Cannot move folder into itself")
        }
        val folder = folderDao.getById(folderId)
            ?: throw IllegalArgumentException("Folder with ID $folderId does not exist")

        if (newParentId != null) {
            var current: VaultFolderEntity? = folderDao.getById(newParentId)
                ?: throw IllegalArgumentException("Target folder with ID $newParentId does not exist")
            while (current != null) {
                if (current.id == folderId) {
                    throw IllegalStateException("Cannot move folder into one of its subfolders")
                }
                current = current.parentId?.let { folderDao.getById(it) }
            }
        }

        folderDao.updateParentId(folderId, newParentId, System.currentTimeMillis())
    }

    override suspend fun deleteFolder(folderId: String): Unit = withContext(Dispatchers.IO) {
        val subfolderCount = folderDao.getSubfolderCount(folderId)
        val entryCount = entryDao.getCountInFolder(folderId)

        if (subfolderCount > 0 || entryCount > 0) {
            throw IllegalStateException(
                "Cannot delete non-empty folder: contains $subfolderCount subfolder(s) and $entryCount file(s).",
            )
        }

        folderDao.deleteById(folderId)
    }

    override fun getEntries(folderId: String?): Flow<List<VaultEntry>> {
        return entryDao.getByFolderId(folderId).map { entities ->
            entities.map { it.toVaultEntry() }
        }.distinctUntilChanged()
    }

    override fun getEntriesByCategory(category: VaultCategory): Flow<List<VaultEntry>> {
        val flow = if (category == VaultCategory.All) {
            entryDao.getAll().map { list -> list.map { it.toVaultEntry() } }
        } else {
            entryDao.getByCategory(category.name).map { list -> list.map { it.toVaultEntry() } }
        }
        return flow.distinctUntilChanged()
    }

    override fun getAllEntries(): Flow<List<VaultEntry>> {
        return entryDao.getAll().map { list -> list.map { it.toVaultEntry() } }.distinctUntilChanged()
    }

    override suspend fun getEntry(id: String): VaultEntry? = withContext(Dispatchers.IO) {
        entryDao.getById(id)?.toVaultEntry()
    }

    override suspend fun importFile(
        sourceFile: File,
        displayName: String?,
        destinationFolderId: String?,
        vmk: ByteArray,
        preserveOriginal: Boolean,
    ): VaultEntry = withContext(Dispatchers.IO) {
        require(sourceFile.exists()) { "Source file does not exist: ${sourceFile.absolutePath}" }
        val finalName = displayName?.trim().takeUnless { it.isNullOrBlank() } ?: sourceFile.name

        BufferedInputStream(FileInputStream(sourceFile)).use { input ->
            importStream(
                inputStream = input,
                displayName = finalName,
                originalSize = sourceFile.length(),
                mimeType = null,
                destinationFolderId = destinationFolderId,
                vmk = vmk,
            )
        }
    }

    override suspend fun importStream(
        inputStream: InputStream,
        displayName: String,
        originalSize: Long,
        mimeType: String?,
        destinationFolderId: String?,
        vmk: ByteArray,
    ): VaultEntry = withContext(Dispatchers.IO) {
        val cleanName = displayName.trim().ifBlank { "Untitled" }

        if (destinationFolderId != null) {
            val parent = folderDao.getById(destinationFolderId)
            requireNotNull(parent) { "Destination folder $destinationFolderId does not exist" }
        }

        val blobId = UUID.randomUUID().toString()
        val tempFile = storageManager.getTempBlobFile(blobId)
        val finalBlobFile = storageManager.getEncryptedBlobFile(blobId)
        val category = VaultCategory.fromMimeOrExtension(mimeType, cleanName)

        var actualOriginalSize = originalSize
        var tempSpoolFile: File? = null
        var streamToEncrypt: InputStream = inputStream

        if (originalSize <= 0L) {
            val spool = storageManager.getTempBlobFile("spool_${blobId}")
            tempSpoolFile = spool
            FileOutputStream(spool).use { out ->
                inputStream.copyTo(out)
            }
            actualOriginalSize = spool.length()
            streamToEncrypt = FileInputStream(spool)
        }

        if (actualOriginalSize > 0 && !storageManager.hasAvailableStorage(actualOriginalSize)) {
            tempSpoolFile?.delete()
            val available = storageManager.getAvailableStorageBytes()
            throw java.io.IOException("Insufficient storage: requires $actualOriginalSize bytes, but available storage is $available bytes.")
        }

        var encResultSizeBytes = 0L

        try {
            // 1. Stream encryption incrementally into temp file
            BufferedOutputStream(FileOutputStream(tempFile)).use { output ->
                val result = VaultFileEncryptor.encryptStream(
                    inputStream = streamToEncrypt,
                    outputStream = output,
                    vmk = vmk,
                    knownPlaintextSize = actualOriginalSize,
                    fileId = UUID.fromString(blobId),
                )
                encResultSizeBytes = result.encryptedSizeBytes
            }

            // 2. Verify encrypted output integrity before committing metadata
            val header = VaultFileDecryptor.readHeader(tempFile)
            val unwrappedDek = VaultFileDecryptor.unwrapDek(header, vmk)
            require(unwrappedDek.size == 32) { "Verification failed: unwrapped key is invalid" }

            // 3. Move verified blob to permanent storage under opaque ID
            if (finalBlobFile.exists()) {
                finalBlobFile.delete()
            }
            if (!tempFile.renameTo(finalBlobFile)) {
                tempFile.copyTo(finalBlobFile, overwrite = true)
                tempFile.delete()
            }

            // 4. Commit metadata into Room database
            val now = System.currentTimeMillis()
            val entity = VaultEntryEntity(
                id = blobId,
                folderId = destinationFolderId,
                originalFileName = cleanName,
                category = category.name,
                mimeType = mimeType,
                originalSizeBytes = actualOriginalSize,
                encryptedSizeBytes = encResultSizeBytes,
                createdAtMillis = now,
                dateModifiedMillis = now,
                isInUse = false,
            )
            entryDao.insert(entity)
            entity.toVaultEntry()
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            if (finalBlobFile.exists()) {
                finalBlobFile.delete()
            }
            throw e
        } finally {
            if (tempSpoolFile != null) {
                try {
                    streamToEncrypt.close()
                } catch (_: Exception) {}
                if (tempSpoolFile.exists()) {
                    tempSpoolFile.delete()
                }
            }
        }
    }

    override suspend fun renameEntry(entryId: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        require(cleanName.isNotBlank()) { "File name cannot be blank" }

        val entry = entryDao.getById(entryId)
            ?: throw IllegalArgumentException("Entry $entryId not found")

        val newCategory = VaultCategory.fromMimeOrExtension(entry.mimeType, cleanName)
        entryDao.update(
            entry.copy(
                originalFileName = cleanName,
                category = newCategory.name,
                dateModifiedMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun copyEntry(
        entryId: String,
        targetFolderId: String?,
        vmk: ByteArray,
    ): VaultEntry = withContext(Dispatchers.IO) {
        val sourceEntry = entryDao.getById(entryId)
            ?: throw IllegalArgumentException("Entry $entryId not found")

        if (targetFolderId != null) {
            val targetFolder = folderDao.getById(targetFolderId)
            requireNotNull(targetFolder) { "Target folder $targetFolderId does not exist" }
        }

        val sourceBlob = storageManager.getEncryptedBlobFile(sourceEntry.id)
        if (!sourceBlob.exists()) {
            throw java.io.FileNotFoundException("Source encrypted blob not found: ${sourceBlob.name}")
        }

        if (sourceEntry.originalSizeBytes > 0 && !storageManager.hasAvailableStorage(sourceEntry.originalSizeBytes)) {
            val available = storageManager.getAvailableStorageBytes()
            throw java.io.IOException("Insufficient storage: cannot copy file (${sourceEntry.originalSizeBytes} bytes), available storage is $available bytes.")
        }

        val newBlobId = UUID.randomUUID().toString()
        val tempFile = storageManager.getTempBlobFile(newBlobId)
        val finalBlobFile = storageManager.getEncryptedBlobFile(newBlobId)

        var encResultSizeBytes = 0L

        try {
            // Decrypt source and re-encrypt under new DEK directly in-memory via piped stream
            // No unencrypted plaintext is ever written to disk or flash storage
            coroutineScope {
                val pipedOut = PipedOutputStream()
                val pipedIn = PipedInputStream(pipedOut, 64 * 1024)

                val encryptJob = async(Dispatchers.IO) {
                    BufferedInputStream(pipedIn).use { pIn ->
                        BufferedOutputStream(FileOutputStream(tempFile)).use { fOut ->
                            VaultFileEncryptor.encryptStream(
                                inputStream = pIn,
                                outputStream = fOut,
                                vmk = vmk,
                                knownPlaintextSize = sourceEntry.originalSizeBytes,
                                fileId = UUID.fromString(newBlobId),
                            )
                        }
                    }
                }

                val decryptJob = launch(Dispatchers.IO) {
                    try {
                        BufferedInputStream(FileInputStream(sourceBlob)).use { fIn ->
                            BufferedOutputStream(pipedOut).use { pOut ->
                                VaultFileDecryptor.decryptStream(fIn, pOut, vmk)
                            }
                        }
                    } catch (e: Throwable) {
                        try {
                            pipedOut.close()
                        } catch (_: Throwable) {}
                        throw e
                    }
                }

                decryptJob.join()
                val encResult = encryptJob.await()
                encResultSizeBytes = encResult.encryptedSizeBytes
            }

            // Verify newly encrypted output
            val header = VaultFileDecryptor.readHeader(tempFile)
            val unwrappedDek = VaultFileDecryptor.unwrapDek(header, vmk)
            require(unwrappedDek.size == 32) { "Verification failed: unwrapped key is invalid" }

            if (finalBlobFile.exists()) {
                finalBlobFile.delete()
            }
            if (!tempFile.renameTo(finalBlobFile)) {
                tempFile.copyTo(finalBlobFile, overwrite = true)
                tempFile.delete()
            }

            val now = System.currentTimeMillis()
            val copyName = if (sourceEntry.folderId == targetFolderId) {
                "Copy of ${sourceEntry.originalFileName}"
            } else {
                sourceEntry.originalFileName
            }

            val newEntity = VaultEntryEntity(
                id = newBlobId,
                folderId = targetFolderId,
                originalFileName = copyName,
                category = sourceEntry.category,
                mimeType = sourceEntry.mimeType,
                originalSizeBytes = sourceEntry.originalSizeBytes,
                encryptedSizeBytes = encResultSizeBytes,
                createdAtMillis = now,
                dateModifiedMillis = now,
                isInUse = false,
            )
            entryDao.insert(newEntity)
            newEntity.toVaultEntry()
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    override suspend fun deleteEntry(entryId: String): Unit = withContext(Dispatchers.IO) {
        val entry = entryDao.getById(entryId)
            ?: throw IllegalArgumentException("Entry $entryId not found")

        if (entry.isInUse) {
            throw IllegalStateException("Cannot delete file: it is currently in use.")
        }

        storageManager.deleteBlob(entryId)
        entryDao.deleteById(entryId)
    }

    override suspend fun moveEntry(entryId: String, targetFolderId: String?): Unit = withContext(Dispatchers.IO) {
        if (targetFolderId != null) {
            val target = folderDao.getById(targetFolderId)
            requireNotNull(target) { "Target folder $targetFolderId does not exist" }
        }
        val entry = entryDao.getById(entryId)
            ?: throw IllegalArgumentException("Entry $entryId not found")

        entryDao.updateFolderId(entry.id, targetFolderId, System.currentTimeMillis())
    }

    override suspend fun setEntryInUse(entryId: String, inUse: Boolean): Unit = withContext(Dispatchers.IO) {
        entryDao.setInUse(entryId, inUse)
    }

    override suspend fun exportOrDecrypt(
        entryId: String,
        destinationFile: File,
        vmk: ByteArray,
    ): Unit = withContext(Dispatchers.IO) {
        val entry = entryDao.getById(entryId)
            ?: throw IllegalArgumentException("Entry $entryId not found")

        val blobFile = storageManager.getEncryptedBlobFile(entry.id)
        if (!blobFile.exists()) {
            throw java.io.FileNotFoundException("Encrypted data blob is missing for file: ${entry.originalFileName}")
        }
        VaultFileDecryptor.decryptFile(blobFile, destinationFile, vmk)
    }

    override suspend fun cleanupOrphanedBlobs(): Unit = withContext(Dispatchers.IO) {
        storageManager.cleanStaleTempFiles()

        val registeredIds = entryDao.getAllEntryIds().toSet()
        val blobFiles = storageManager.blobsDir.listFiles { _, name -> name.endsWith(".vsf") } ?: return@withContext
        val orphanDir = File(storageManager.blobsDir.parentFile, "quarantine")
        if (blobFiles.any { it.name.substringBeforeLast(".vsf") !in registeredIds }) {
            orphanDir.mkdirs()
        }
        for (blob in blobFiles) {
            val id = blob.name.substringBeforeLast(".vsf")
            if (id !in registeredIds) {
                val target = File(orphanDir, blob.name)
                if (!blob.renameTo(target)) {
                    blob.copyTo(target, overwrite = true)
                    blob.delete()
                }
            }
        }
    }

    private fun VaultFolderEntity.toVaultFolder(itemCount: Int = 0, subfolderCount: Int = 0): VaultFolder =
        VaultFolder(
            id = id,
            name = name,
            parentId = parentId,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            itemCount = itemCount,
            subfolderCount = subfolderCount,
        )

    private fun VaultEntryEntity.toVaultEntry(): VaultEntry =
        VaultEntry(
            id = id,
            folderId = folderId,
            originalFileName = originalFileName,
            category = VaultCategory.entries.firstOrNull { it.name == category } ?: VaultCategory.Other,
            mimeType = mimeType,
            originalSizeBytes = originalSizeBytes,
            encryptedSizeBytes = encryptedSizeBytes,
            createdAtMillis = createdAtMillis,
            dateModifiedMillis = dateModifiedMillis,
            isInUse = isInUse,
        )
}
