package com.vaultsync.core.media

import android.net.Uri

data class MediaFolder(
    val key: String,
    val name: String,
    val itemCount: Int,
    val coverUri: Uri? = null,
    val previewItems: List<DeviceMediaItem> = emptyList(),
    val allItems: List<DeviceMediaItem> = emptyList(),
)
