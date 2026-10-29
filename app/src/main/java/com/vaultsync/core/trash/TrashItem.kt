package com.vaultsync.core.trash

import java.io.File

data class TrashItem(
    val id: String,
    val displayName: String,
    val originalUriString: String?,
    val originalPath: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val localFilePath: String,
    val deletedAt: Long,
    val autoDeleteAt: Long,
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")

    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val file: File
        get() = File(localFilePath)

    val exists: Boolean
        get() = file.exists()

    /**
     * Number of full or partial days remaining before 15-day auto deletion.
     */
    val daysRemaining: Int
        get() {
            val remainingMs = autoDeleteAt - System.currentTimeMillis()
            if (remainingMs <= 0) return 0
            return ((remainingMs + 86_399_999L) / 86_400_000L).toInt()
        }

    val remainingStatusText: String
        get() = when {
            daysRemaining <= 0 -> "Expires today"
            daysRemaining == 1 -> "1 day left"
            else -> "$daysRemaining days left"
        }

    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "0 B"
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
                else -> "$sizeBytes B"
            }
        }
}
