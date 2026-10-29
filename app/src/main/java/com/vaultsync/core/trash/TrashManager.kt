package com.vaultsync.core.trash

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vaultsync.core.media.DeviceMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class TrashManager(
    private val context: Context,
    private val database: TrashDatabase,
    private val preferences: TrashPreferences,
) {
    val trashDir: File by lazy {
        File(context.filesDir, "trash").apply {
            mkdirs()
            ensureNoMedia(this)
        }
    }

    private fun ensureNoMedia(dir: File) {
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Copies the given media items into the local trash directory and creates
     * pending TrashEntity entries set to expire in 15 days.
     */
    suspend fun prepareMoveToTrash(items: List<DeviceMediaItem>): List<TrashEntity> = withContext(Dispatchers.IO) {
        val entities = mutableListOf<TrashEntity>()
        val retentionDays = preferences.retentionDays.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val autoDeleteAt = now + (retentionDays.toLong() * 24L * 60L * 60L * 1000L)

        for (item in items) {
            val uri = item.uri ?: continue
            val id = UUID.randomUUID().toString()
            val sanitizedName = item.displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(trashDir, "${id}_$sanitizedName")

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val entity = TrashEntity(
                    id = id,
                    displayName = item.displayName,
                    originalUriString = uri.toString(),
                    originalPath = item.folderName,
                    mimeType = item.mimeType ?: "application/octet-stream",
                    sizeBytes = if (targetFile.exists()) targetFile.length() else item.sizeBytes,
                    localFilePath = targetFile.absolutePath,
                    deletedAt = now,
                    autoDeleteAt = autoDeleteAt,
                )
                entities.add(entity)
            } catch (e: Exception) {
                targetFile.delete()
            }
        }

        if (entities.isNotEmpty()) {
            database.trashDao().insertAll(entities)
        }

        entities
    }

    /**
     * If the user cancels the OS deletion dialog, discard the temporary files in trash.
     */
    suspend fun discardPending(entries: List<TrashEntity>) = withContext(Dispatchers.IO) {
        val ids = entries.map { it.id }
        for (entry in entries) {
            try {
                File(entry.localFilePath).delete()
            } catch (_: Exception) {
            }
        }
        database.trashDao().deleteByIds(ids)
    }

    /**
     * Restores a trashed item back to the device's public media library.
     */
    suspend fun restoreItem(item: TrashItem): Boolean = withContext(Dispatchers.IO) {
        val file = item.file
        if (!file.exists()) {
            database.trashDao().deleteById(item.id)
            return@withContext false
        }

        try {
            val restoredUri: Uri?
            if (item.isVideo) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, item.displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, item.mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Restored")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                restoredUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, item.displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, item.mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restored")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                restoredUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }

            if (restoredUri != null) {
                file.inputStream().use { input ->
                    context.contentResolver.openOutputStream(restoredUri)?.use { output ->
                        input.copyTo(output)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finishValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    context.contentResolver.update(restoredUri, finishValues, null, null)
                }

                file.delete()
                database.trashDao().deleteById(item.id)
                return@withContext true
            }
        } catch (_: Exception) {
        }
        false
    }

    suspend fun restoreItems(items: List<TrashItem>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (item in items) {
            if (restoreItem(item)) {
                count++
            }
        }
        count
    }

    suspend fun deletePermanently(item: TrashItem): Boolean = withContext(Dispatchers.IO) {
        try {
            item.file.delete()
            database.trashDao().deleteById(item.id)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deletePermanently(items: List<TrashItem>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (item in items) {
            if (deletePermanently(item)) {
                count++
            }
        }
        count
    }

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        val all = database.trashDao().getAll()
        val count = all.size
        for (entry in all) {
            try {
                File(entry.localFilePath).delete()
            } catch (_: Exception) {
            }
        }
        val remainingFiles = trashDir.listFiles() ?: emptyArray()
        for (file in remainingFiles) {
            if (file.name != ".nomedia") {
                file.delete()
            }
        }
        database.trashDao().deleteAll()
        count
    }

    /**
     * Automatically deletes all files in Trash that have reached their 15-day limit.
     */
    suspend fun cleanupExpired(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val expired = database.trashDao().getExpired(now)
        var cleaned = 0
        for (entry in expired) {
            try {
                File(entry.localFilePath).delete()
                cleaned++
            } catch (_: Exception) {
            }
        }
        if (expired.isNotEmpty()) {
            database.trashDao().deleteExpired(now)
        }
        cleaned
    }

    suspend fun getTrashItems(): List<TrashItem> = withContext(Dispatchers.IO) {
        cleanupExpired()
        val entities = database.trashDao().getAll()
        entities.map { entity ->
            TrashItem(
                id = entity.id,
                displayName = entity.displayName,
                originalUriString = entity.originalUriString,
                originalPath = entity.originalPath,
                mimeType = entity.mimeType,
                sizeBytes = entity.sizeBytes,
                localFilePath = entity.localFilePath,
                deletedAt = entity.deletedAt,
                autoDeleteAt = entity.autoDeleteAt,
            )
        }
    }

    suspend fun getTrashStats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        cleanupExpired()
        val count = database.trashDao().getCount()
        val totalBytes = database.trashDao().getTotalSizeBytes() ?: 0L
        Pair(count, totalBytes)
    }

    companion object {
        @Volatile
        private var instance: TrashManager? = null

        fun get(context: Context): TrashManager {
            return instance ?: synchronized(this) {
                instance ?: TrashManager(
                    context = context.applicationContext,
                    database = TrashDatabase.getInstance(context),
                    preferences = TrashPreferences(context),
                ).also { instance = it }
            }
        }
    }
}
