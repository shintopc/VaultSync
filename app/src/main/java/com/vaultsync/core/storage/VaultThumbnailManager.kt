package com.vaultsync.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.security.VaultFileDecryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class VaultThumbnailManager(
    val thumbnailDir: File,
    private val storageManager: VaultStorageManager,
) {
    constructor(context: Context, storageManager: VaultStorageManager) : this(
        thumbnailDir = resolveThumbnailDir(context),
        storageManager = storageManager,
    )

    init {
        ensureThumbnailDir()
    }

    private fun ensureThumbnailDir() {
        if (!thumbnailDir.exists()) {
            thumbnailDir.mkdirs()
        }
        try {
            File(thumbnailDir, ".nomedia").createNewFile()
        } catch (_: Exception) {}
    }

    suspend fun getOrCreateThumbnail(
        entry: VaultEntry,
        vmk: ByteArray,
    ): File? = withContext(Dispatchers.IO) {
        if (entry.category != VaultCategory.Images && entry.category != VaultCategory.Videos) {
            return@withContext null
        }

        ensureThumbnailDir()
        val targetFile = File(thumbnailDir, "thumb_${entry.id}.jpg")
        if (targetFile.exists() && targetFile.length() > 0) {
            return@withContext targetFile
        }

        val encryptedBlob = storageManager.getEncryptedBlobFile(entry.id)
        if (!encryptedBlob.exists()) {
            return@withContext null
        }

        try {
            if (entry.category == VaultCategory.Images) {
                val tempPlain = File(thumbnailDir, "tmp_${UUID.randomUUID()}.part")
                try {
                    VaultFileDecryptor.decryptFile(encryptedBlob, tempPlain, vmk)

                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tempPlain.absolutePath, boundsOptions)

                    val reqSize = 360
                    var inSampleSize = 1
                    if (boundsOptions.outHeight > reqSize || boundsOptions.outWidth > reqSize) {
                        val halfHeight = boundsOptions.outHeight / 2
                        val halfWidth = boundsOptions.outWidth / 2
                        while ((halfHeight / inSampleSize) >= reqSize && (halfWidth / inSampleSize) >= reqSize) {
                            inSampleSize *= 2
                        }
                    }

                    val decodeOptions = BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                        this.inPreferredConfig = Bitmap.Config.RGB_565
                    }

                    val bitmap = BitmapFactory.decodeFile(tempPlain.absolutePath, decodeOptions)
                    if (bitmap != null) {
                        FileOutputStream(targetFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        bitmap.recycle()
                        targetFile
                    } else {
                        null
                    }
                } finally {
                    if (tempPlain.exists()) {
                        tempPlain.delete()
                    }
                }
            } else {
                // Video thumbnail extraction: Use MediaDataSource to stream only the first few KB/MB
                // directly from VaultFileDecryptor.readRange without decrypting whole gigabyte files to disk!
                val retriever = MediaMetadataRetriever()
                try {
                    var frame: Bitmap? = null
                    try {
                        val mediaDataSource = object : android.media.MediaDataSource() {
                            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                                if (position >= entry.originalSizeBytes) return -1
                                val lengthToRead = minOf(size.toLong(), entry.originalSizeBytes - position)
                                if (lengthToRead <= 0) return 0

                                val baos = object : java.io.OutputStream() {
                                    var written = 0
                                    override fun write(b: Int) {
                                        if (offset + written < buffer.size) {
                                            buffer[offset + written] = b.toByte()
                                            written++
                                        }
                                    }
                                    override fun write(b: ByteArray, off: Int, len: Int) {
                                        System.arraycopy(b, off, buffer, offset + written, len)
                                        written += len
                                    }
                                }
                                val read = VaultFileDecryptor.readRange(
                                    encryptedFile = encryptedBlob,
                                    vmk = vmk,
                                    startOffset = position,
                                    length = lengthToRead,
                                    outputStream = baos,
                                )
                                return if (read > 0) read.toInt() else -1
                            }

                            override fun getSize(): Long = entry.originalSizeBytes
                            override fun close() {}
                        }
                        retriever.setDataSource(mediaDataSource)
                        frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.frameAtTime
                    } catch (_: Throwable) {
                        // Bounded fallback: Only decrypt the first 4MB of the video rather than 2GB
                        val tempPlain = File(thumbnailDir, "tmp_${UUID.randomUUID()}.part")
                        try {
                            FileOutputStream(tempPlain).use { out ->
                                VaultFileDecryptor.readRange(
                                    encryptedFile = encryptedBlob,
                                    vmk = vmk,
                                    startOffset = 0L,
                                    length = minOf(entry.originalSizeBytes, 4 * 1024 * 1024L),
                                    outputStream = out,
                                )
                            }
                            val fallbackRetriever = MediaMetadataRetriever()
                            try {
                                fallbackRetriever.setDataSource(tempPlain.absolutePath)
                                frame = fallbackRetriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: fallbackRetriever.frameAtTime
                            } finally {
                                try { fallbackRetriever.release() } catch (_: Exception) {}
                            }
                        } finally {
                            if (tempPlain.exists()) tempPlain.delete()
                        }
                    }

                    if (frame != null) {
                        val targetWidth = 360
                        val targetHeight = (360f * frame.height / frame.width.coerceAtLeast(1)).toInt().coerceIn(120, 720)
                        val scaled = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
                        FileOutputStream(targetFile).use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        if (scaled != frame) scaled.recycle()
                        frame.recycle()
                        targetFile
                    } else {
                        null
                    }
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteThumbnail(entryId: String) = withContext(Dispatchers.IO) {
        val file = File(thumbnailDir, "thumb_${entryId}.jpg")
        if (file.exists()) {
            file.delete()
        }
    }

    suspend fun clearAllThumbnails() = withContext(Dispatchers.IO) {
        try {
            val files = thumbnailDir.listFiles() ?: return@withContext
            for (file in files) {
                if (file.name != ".nomedia") {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }
}

private fun resolveThumbnailDir(context: Context): File {
    val cache = try { context.cacheDir } catch (_: Exception) { null }
        ?: File(System.getProperty("java.io.tmpdir", "."), "vault_thumbnails")
    return File(cache, "vault_thumbnails")
}

