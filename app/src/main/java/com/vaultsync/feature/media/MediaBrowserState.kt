package com.vaultsync.feature.media

import android.content.IntentSender
import com.vaultsync.core.media.DeviceMediaItem
import com.vaultsync.core.media.MediaCategory
import com.vaultsync.core.media.MediaFolder
import com.vaultsync.core.media.MediaPermissionStatus
import com.vaultsync.core.media.MediaQuery
import com.vaultsync.core.media.MediaSort
import com.vaultsync.core.trash.TrashEntity

enum class MediaViewMode {
    TIMELINE,
    FOLDERS,
}

data class PendingDeleteRequest(
    val intentSender: IntentSender,
    val items: List<DeviceMediaItem>,
    val trashEntities: List<TrashEntity>? = null,
    val isTrash: Boolean = false,
)

data class MediaBrowserState(
    val permissionStatus: MediaPermissionStatus = MediaPermissionStatus.Unknown,
    val allItems: List<DeviceMediaItem> = emptyList(),
    val visibleItems: List<DeviceMediaItem> = emptyList(),
    val folders: List<MediaFolder> = emptyList(),
    val query: MediaQuery = MediaQuery(includeVideos = true),
    val selectedItemIds: Set<String> = emptySet(),
    val previewItem: DeviceMediaItem? = null,
    val infoItem: DeviceMediaItem? = null,
    val renameItem: DeviceMediaItem? = null,
    val viewMode: MediaViewMode = MediaViewMode.TIMELINE,
    val activeFolderName: String? = null,
    val isVaultPickerOpen: Boolean = false,
    val isOperationInProgress: Boolean = false,
    val operationMessage: String? = null,
    val operationProgress: Float? = null,
    val operationCurrent: Int = 0,
    val operationTotal: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSafMode: Boolean = false,
    val pendingDeleteRequest: PendingDeleteRequest? = null,
    val trashCount: Int = 0,
    val isTrashEnabled: Boolean = true,
    val isFavoritesOnly: Boolean = false,
    val favoritedIds: Set<String> = emptySet(),
    val gridColumnCount: Int = 3,
) {
    val hasSelection: Boolean = selectedItemIds.isNotEmpty()
    val category: MediaCategory = query.category
    val sort: MediaSort = query.sort
}
