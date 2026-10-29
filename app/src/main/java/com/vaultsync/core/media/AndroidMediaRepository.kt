package com.vaultsync.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class AndroidMediaRepository(
    private val context: Context,
) : MediaRepository {
    private val contentResolver: ContentResolver = context.contentResolver

    override suspend fun loadMediaStoreItems(): List<DeviceMediaItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.DURATION)
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()

        val mediaTypes = listOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${mediaTypes.joinToString { "?" }})"
        val selectionArgs = mediaTypes.map { it.toString() }.toTypedArray()
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val durationColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            } else -1
            val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            }

            buildList {
                while (cursor.moveToNext()) {
                    val category = cursor.getInt(mediaTypeColumn).toMediaCategory() ?: continue
                    val id = cursor.getLong(idColumn)
                    val folderPath = cursor.getStringOrNull(folderColumn).orEmpty()
                    val folderName = folderNameFromPath(folderPath)
                    val duration = if (durationColumn >= 0) cursor.getLongOrZero(durationColumn) else 0L

                    val itemUri = when (category) {
                        MediaCategory.Videos -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        MediaCategory.Images -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        MediaCategory.Audio -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    }

                    add(
                        DeviceMediaItem(
                            id = "mediastore:$id",
                            uri = itemUri,
                            displayName = cursor.getStringOrNull(nameColumn).orEmpty().ifBlank { "Untitled" },
                            category = category,
                            mimeType = cursor.getStringOrNull(mimeColumn),
                            folderName = folderName,
                            folderKey = folderPath.ifBlank { folderName },
                            sizeBytes = cursor.getLongOrZero(sizeColumn),
                            dateModifiedMillis = cursor.getLongOrZero(dateColumn).toMillisFromMediaStoreSeconds(),
                            source = MediaSource.MediaStore,
                            durationMillis = duration,
                        ),
                    )
                }

            }
        }.orEmpty()
    }

    override suspend fun loadSafFolderItems(folderUri: Uri): List<DeviceMediaItem> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        val pending = ArrayDeque<Pair<DocumentFile, String>>()
        pending.add(root to (root.name ?: "Selected folder"))
        val items = mutableListOf<DeviceMediaItem>()

        while (pending.isNotEmpty()) {
            val (directory, folderName) = pending.removeFirst()
            directory.listFiles().forEach { document ->
                when {
                    document.isDirectory -> pending.add(document to (document.name ?: folderName))
                    document.isFile -> {
                        val category = document.type.toMediaCategoryFromMime() ?: return@forEach
                        items += DeviceMediaItem(
                            id = "saf:${document.uri}",
                            uri = document.uri,
                            displayName = document.name ?: "Untitled",
                            category = category,
                            mimeType = document.type,
                            folderName = folderName,
                            folderKey = directory.uri.toString(),
                            sizeBytes = document.length(),
                            dateModifiedMillis = document.lastModified(),
                            source = MediaSource.StorageAccessFramework,
                        )
                    }
                }
            }
        }

        items
    }

    override suspend fun deleteItem(item: DeviceMediaItem): Boolean = withContext(Dispatchers.IO) {
        val uri = item.uri ?: return@withContext false
        try {
            if (item.source == MediaSource.StorageAccessFramework) {
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.delete() == true
            } else {
                contentResolver.delete(uri, null, null) > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun createDeleteRequest(items: List<DeviceMediaItem>): android.content.IntentSender? = withContext(Dispatchers.IO) {
        val mediaStoreUris = items
            .filter { it.source != MediaSource.StorageAccessFramework }
            .mapNotNull { it.uri }

        if (mediaStoreUris.isEmpty()) return@withContext null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, mediaStoreUris)
                return@withContext pendingIntent.intentSender
            } catch (_: Exception) {
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (uri in mediaStoreUris) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (e: android.app.RecoverableSecurityException) {
                    return@withContext e.userAction.actionIntent.intentSender
                } catch (_: Exception) {
                }
            }
        }

        null
    }

    override suspend fun renameItem(item: DeviceMediaItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        val uri = item.uri ?: return@withContext false
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return@withContext false
        try {
            if (item.source == MediaSource.StorageAccessFramework) {
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.renameTo(cleanName) == true
            } else {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                }
                contentResolver.update(uri, values, null, null) > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun Int.toMediaCategory(): MediaCategory? = when (this) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaCategory.Images
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaCategory.Videos
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaCategory.Audio
        else -> null
    }

    private fun String?.toMediaCategoryFromMime(): MediaCategory? = when {
        this == null -> null
        startsWith("image/") -> MediaCategory.Images
        startsWith("video/") -> MediaCategory.Videos
        startsWith("audio/") -> MediaCategory.Audio
        else -> null
    }

    private fun folderNameFromPath(path: String): String {
        val cleanPath = path.trim('/').replace('\\', '/')
        if (cleanPath.isBlank()) return "Device media"
        return cleanPath.substringAfterLast('/').ifBlank { "Device media" }
    }

    private fun Long.toMillisFromMediaStoreSeconds(): Long =
        if (this in 1 until 100_000_000_000L) this * 1_000L else this
}

private fun android.database.Cursor.getStringOrNull(columnIndex: Int): String? =
    if (isNull(columnIndex)) null else getString(columnIndex)

private fun android.database.Cursor.getLongOrZero(columnIndex: Int): Long =
    if (isNull(columnIndex)) 0L else getLong(columnIndex)
