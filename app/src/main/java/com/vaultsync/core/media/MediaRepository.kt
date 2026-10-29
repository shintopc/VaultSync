package com.vaultsync.core.media

import android.net.Uri

interface MediaRepository {
    suspend fun loadMediaStoreItems(): List<DeviceMediaItem>

    suspend fun loadSafFolderItems(folderUri: Uri): List<DeviceMediaItem>

    suspend fun deleteItem(item: DeviceMediaItem): Boolean = false

    suspend fun createDeleteRequest(items: List<DeviceMediaItem>): android.content.IntentSender? = null

    suspend fun renameItem(item: DeviceMediaItem, newName: String): Boolean = false
}
