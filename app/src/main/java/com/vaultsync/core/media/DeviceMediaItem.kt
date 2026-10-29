package com.vaultsync.core.media

import android.net.Uri

data class DeviceMediaItem(
    val id: String,
    val uri: Uri? = null,
    val displayName: String,
    val category: MediaCategory,
    val mimeType: String?,
    val folderName: String,
    val folderKey: String,
    val sizeBytes: Long,
    val dateModifiedMillis: Long,
    val source: MediaSource,
    val durationMillis: Long = 0L,
)

