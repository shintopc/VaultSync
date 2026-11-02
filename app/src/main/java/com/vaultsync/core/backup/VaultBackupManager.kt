package com.vaultsync.core.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vaultsync.core.storage.VaultStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupMetadata(
    val formatVersion: Int,
    val createdAtMillis: Long,
    val blobCount: Int,
    val totalSizeBytes: Long,
)

object VaultBackupManager {
    private const val TAG = "VaultBackupManager"
    private const val METADATA_ENTRY = "backup_metadata.json"
    private const val MANIFEST_ENTRY = "manifest.vsm"
    private const val MANIFEST_BAK_ENTRY = "manifest.vsm.bak"
    private const val BLOBS_PREFIX = "blobs/"

    suspend fun createBackup(
        context: Context,
        destinationUri: Uri,
        storageManager: VaultStorageManager,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): BackupMetadata = withContext(Dispatchers.IO) {
        val outputStream = context.contentResolver.openOutputStream(destinationUri)
            ?: throw java.io.IOException("Cannot open output stream for URI: $destinationUri")
        createBackupToStream(outputStream, storageManager, onProgress)
    }

    suspend fun createBackupToStream(
        outputStream: OutputStream,
        storageManager: VaultStorageManager,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): BackupMetadata = withContext(Dispatchers.IO) {
        val blobsDir = storageManager.blobsDir
        val blobFiles = blobsDir.listFiles { _, name -> name.endsWith(".vsf") } ?: emptyArray()
        val manifestFile = storageManager.manifestFile

        if (!manifestFile.exists() && blobFiles.isEmpty()) {
            throw IllegalStateException("Vault is empty. No files to back up.")
        }

        val totalFiles = blobFiles.size + (if (manifestFile.exists()) 1 else 0)
        var processedFiles = 0

        val totalSizeBytes = blobFiles.sumOf { it.length() } + (if (manifestFile.exists()) manifestFile.length() else 0L)
        val metadata = BackupMetadata(
            formatVersion = 1,
            createdAtMillis = System.currentTimeMillis(),
            blobCount = blobFiles.size,
            totalSizeBytes = totalSizeBytes,
        )

        ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
            // 1. Write metadata JSON
            val metaJson = JSONObject().apply {
                put("formatVersion", metadata.formatVersion)
                put("createdAtMillis", metadata.createdAtMillis)
                put("blobCount", metadata.blobCount)
                put("totalSizeBytes", metadata.totalSizeBytes)
            }.toString(2)
            zipOut.putNextEntry(ZipEntry(METADATA_ENTRY))
            zipOut.write(metaJson.toByteArray())
            zipOut.closeEntry()

            // 2. Write manifest file
            if (manifestFile.exists()) {
                zipOut.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                FileInputStream(manifestFile).use { it.copyTo(zipOut) }
                zipOut.closeEntry()
                processedFiles++
                onProgress?.invoke(processedFiles, totalFiles)
            }

            // Write backup manifest if available
            val bakFile = storageManager.backupManifestFile
            if (bakFile.exists()) {
                zipOut.putNextEntry(ZipEntry(MANIFEST_BAK_ENTRY))
                FileInputStream(bakFile).use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }

            // 3. Write encrypted blobs
            val buffer = ByteArray(64 * 1024)
            for (blob in blobFiles) {
                zipOut.putNextEntry(ZipEntry("$BLOBS_PREFIX${blob.name}"))
                FileInputStream(blob).use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        zipOut.write(buffer, 0, read)
                    }
                }
                zipOut.closeEntry()
                processedFiles++
                onProgress?.invoke(processedFiles, totalFiles)
            }

            zipOut.finish()
        }

        return@withContext metadata
    }

    suspend fun restoreBackup(
        context: Context,
        sourceUri: Uri,
        storageManager: VaultStorageManager,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw java.io.IOException("Cannot open input stream for backup URI: $sourceUri")
        restoreBackupFromStream(inputStream, storageManager, onProgress)
    }

    suspend fun restoreBackupFromStream(
        inputStream: InputStream,
        storageManager: VaultStorageManager,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val blobsDir = storageManager.blobsDir
        if (!blobsDir.exists()) blobsDir.mkdirs()

        var restoredBlobs = 0
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == MANIFEST_ENTRY) {
                    val dest = storageManager.manifestFile
                    FileOutputStream(dest).use { fos ->
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                } else if (name == MANIFEST_BAK_ENTRY) {
                    val dest = storageManager.backupManifestFile
                    FileOutputStream(dest).use { fos ->
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                } else if (name.startsWith(BLOBS_PREFIX) && name.endsWith(".vsf")) {
                    val blobFileName = name.removePrefix(BLOBS_PREFIX)
                    val dest = File(blobsDir, blobFileName)
                    FileOutputStream(dest).use { fos ->
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                    restoredBlobs++
                    onProgress?.invoke(restoredBlobs, 0)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        Log.i(TAG, "Restored $restoredBlobs blobs from backup archive")
        return@withContext restoredBlobs
    }
}
