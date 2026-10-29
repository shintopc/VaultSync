package com.vaultsync.core.storage

import android.content.Context
import com.vaultsync.core.manifest.VaultManifest
import com.vaultsync.core.migration.MigrationJournalManager
import java.io.File

class VaultStorageManager(
    private val explicitVaultRoot: File? = null,
    private val context: Context? = null,
) {
    constructor(vaultRoot: File) : this(explicitVaultRoot = vaultRoot, context = null)
    constructor(context: Context) : this(explicitVaultRoot = null, context = context)

    val vaultRoot: File
        get() = explicitVaultRoot ?: (context?.let { resolveVaultRoot(it) } ?: File("/tmp/vault"))

    val blobsDir: File
        get() = File(vaultRoot, "blobs").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                ensureNoMedia(dir)
            }
        }

    val tempDir: File
        get() = File(vaultRoot, "temp").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                ensureNoMedia(dir)
            }
        }

    val migrationJournalDir: File
        get() = File(vaultRoot, MigrationJournalManager.JOURNAL_DIR_NAME).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                ensureNoMedia(dir)
            }
        }

    val manifestFile: File
        get() = File(vaultRoot, VaultManifest.MANIFEST_FILE_NAME)

    val migrationJournalFile: File
        get() = File(migrationJournalDir, MigrationJournalManager.JOURNAL_FILE_NAME)

    val isSharedStorageActive: Boolean
        get() = context?.let { VaultStoragePreferences(it).isSharedStorageEnabled } ?: false

    fun initDirectories() {
        vaultRoot.mkdirs()
        ensureNoMedia(vaultRoot)
        blobsDir
        tempDir
        migrationJournalDir
    }

    fun getEncryptedBlobFile(id: String): File {
        val primary = File(blobsDir, "$id.vsf")
        if (primary.exists()) {
            return primary
        }
        // Fallback: check alternate storage locations if context is available
        context?.let { ctx ->
            // 1. Check shared storage directory
            val prefs = VaultStoragePreferences(ctx)
            val sharedDir = prefs.getSharedStorageDirectory()
            val sharedBlob = File(File(sharedDir, "blobs"), "$id.vsf")
            if (sharedBlob.exists()) {
                return sharedBlob
            }
            // 2. Check app-private internal storage
            val internalBlob = File(File(ctx.filesDir, "vault/blobs"), "$id.vsf")
            if (internalBlob.exists()) {
                return internalBlob
            }
        }
        return primary
    }

    fun getTempBlobFile(id: String): File = File(tempDir, "$id.part")

    fun getAvailableStorageBytes(): Long {
        return vaultRoot.usableSpace
    }

    fun hasAvailableStorage(requiredBytes: Long, safetyMarginBytes: Long = 10 * 1024 * 1024): Boolean {
        if (requiredBytes <= 0) return true
        val usable = vaultRoot.usableSpace
        return (usable - safetyMarginBytes) >= requiredBytes
    }

    fun cleanStaleTempFiles() {
        val files = tempDir.listFiles() ?: return
        for (file in files) {
            file.delete()
        }
        val partFiles = blobsDir.listFiles { _, name -> name.endsWith(".part") } ?: return
        for (file in partFiles) {
            file.delete()
        }
    }

    fun deleteBlob(id: String): Boolean {
        val file = getEncryptedBlobFile(id)
        val deletedPrimary = if (file.exists()) file.delete() else true
        context?.let { ctx ->
            val internalBlob = File(File(ctx.filesDir, "vault/blobs"), "$id.vsf")
            if (internalBlob.exists()) internalBlob.delete()
            val sharedBlob = File(File(VaultStoragePreferences(ctx).getSharedStorageDirectory(), "blobs"), "$id.vsf")
            if (sharedBlob.exists()) sharedBlob.delete()
        }
        return deletedPrimary
    }

    fun isExistingVaultPresent(): Boolean {
        if (manifestFile.exists() && manifestFile.length() >= 32) return true
        context?.let { ctx ->
            val sharedDir = VaultStoragePreferences(ctx).getSharedStorageDirectory()
            val sharedManifest = File(sharedDir, VaultManifest.MANIFEST_FILE_NAME)
            if (sharedManifest.exists() && sharedManifest.length() >= 32) return true
            val internalManifest = File(File(ctx.filesDir, "vault"), VaultManifest.MANIFEST_FILE_NAME)
            if (internalManifest.exists() && internalManifest.length() >= 32) return true
        }
        return false
    }

    private fun ensureNoMedia(directory: File) {
        val noMedia = File(directory, ".nomedia")
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun resolveVaultRoot(context: Context): File {
            val prefs = VaultStoragePreferences(context)
            return if (prefs.isSharedStorageEnabled) {
                prefs.getSharedStorageDirectory()
            } else {
                File(context.filesDir, "vault")
            }
        }
    }
}
