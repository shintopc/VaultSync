package com.vaultsync.core.storage

import android.content.Context
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.security.VaultFileDecryptor
import com.vaultsync.core.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class VaultPreviewManager(
    private val context: Context,
    private val vaultRepository: VaultRepository,
    private val storageManager: VaultStorageManager,
) {
    private val previewDir: File = File(context.cacheDir, "vault_previews")

    init {
        ensurePreviewDir()
    }

    private fun ensurePreviewDir() {
        if (!previewDir.exists()) {
            previewDir.mkdirs()
        }
        // Place .nomedia in the preview cache as extra precaution
        File(previewDir, ".nomedia").createNewFile()
    }

    suspend fun decryptForPreview(
        entry: VaultEntry,
        vmk: ByteArray,
    ): File = withContext(Dispatchers.IO) {
        ensurePreviewDir()
        vaultRepository.setEntryInUse(entry.id, true)

        val encryptedBlob = storageManager.getEncryptedBlobFile(entry.id)
        if (!encryptedBlob.exists()) {
            vaultRepository.setEntryInUse(entry.id, false)
            throw java.io.FileNotFoundException("Encrypted blob for ${entry.originalFileName} not found in vault storage.")
        }

        val extension = entry.originalFileName.substringAfterLast('.', "")
        val suffix = if (extension.isNotBlank()) ".$extension" else ".tmp"
        val tempPreviewFile = File(previewDir, "preview_${entry.id}_${UUID.randomUUID()}$suffix")

        try {
            VaultFileDecryptor.decryptFile(encryptedBlob, tempPreviewFile, vmk)
            tempPreviewFile
        } catch (e: Exception) {
            if (tempPreviewFile.exists()) {
                tempPreviewFile.delete()
            }
            vaultRepository.setEntryInUse(entry.id, false)
            throw e
        }
    }

    suspend fun releasePreview(
        entry: VaultEntry,
        previewFile: File?,
    ) = withContext(Dispatchers.IO) {
        try {
            if (previewFile != null && previewFile.exists()) {
                previewFile.delete()
            }
        } finally {
            vaultRepository.setEntryInUse(entry.id, false)
        }
    }

    suspend fun clearAllPreviews() = withContext(Dispatchers.IO) {
        try {
            val files = previewDir.listFiles() ?: return@withContext
            for (file in files) {
                if (file.name != ".nomedia") {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isPreviewable(entry: VaultEntry): Boolean {
        return when (entry.category) {
            VaultCategory.Images, VaultCategory.Videos, VaultCategory.Audio -> true
            VaultCategory.Documents -> isTextDocument(entry) || isPdfDocument(entry)
            else -> false
        }
    }

    fun isPdfDocument(entry: VaultEntry): Boolean {
        val ext = entry.originalFileName.substringAfterLast('.', "").lowercase()
        val mime = entry.mimeType?.lowercase() ?: ""
        return ext == "pdf" || mime == "application/pdf"
    }

    fun isTextDocument(entry: VaultEntry): Boolean {
        val ext = entry.originalFileName.substringAfterLast('.', "").lowercase()
        val mime = entry.mimeType?.lowercase() ?: ""
        return mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "application/javascript" ||
            ext in setOf("txt", "log", "json", "xml", "md", "csv", "kt", "java", "py", "html", "css", "js", "ts", "yml", "yaml", "sh")
    }
}
