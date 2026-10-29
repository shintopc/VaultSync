package com.vaultsync.feature.media

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.vaultsync.core.media.AndroidMediaRepository
import com.vaultsync.core.media.DeviceMediaItem
import com.vaultsync.core.media.MediaCategory
import com.vaultsync.core.media.MediaFolder
import com.vaultsync.core.media.MediaPermissionStatus
import com.vaultsync.core.media.MediaQuery
import com.vaultsync.core.media.MediaRepository
import com.vaultsync.core.media.MediaSort
import com.vaultsync.core.media.MediaSource
import com.vaultsync.core.media.filterAndSortMedia
import com.vaultsync.core.media.foldersForCategory
import com.vaultsync.core.media.foldersForGallery
import com.vaultsync.core.model.VaultFolder
import com.vaultsync.core.trash.TrashEntity
import com.vaultsync.core.trash.TrashManager
import com.vaultsync.core.trash.TrashPreferences
import com.vaultsync.core.vault.VaultContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaViewModel(
    application: Application,
    private val repository: MediaRepository,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        repository = AndroidMediaRepository(application),
    )

    private val vaultContainer = VaultContainer.get(application)
    private val trashManager = TrashManager.get(application)
    private val trashPreferences = TrashPreferences(application)
    val favoritesManager = com.vaultsync.core.media.FavoritesManager.get(application)
    val vaultFolders: Flow<List<VaultFolder>> = vaultContainer.repository.getAllFolders()
    val isVaultUnlocked: StateFlow<Boolean> = vaultContainer.session.isUnlocked

    private val _state = MutableStateFlow(MediaBrowserState())
    val state: StateFlow<MediaBrowserState> = _state.asStateFlow()

    private var mediaStoreObserver: ContentObserver? = null
    private var observerReloadJob: Job? = null

    init {
        refreshTrashStats()
        viewModelScope.launch {
            favoritesManager.favoritesFlow.collect { favs ->
                _state.update { current ->
                    current.withDerivedMedia(favIds = favs)
                }
            }
        }
        registerMediaStoreObserver()
    }

    override fun onCleared() {
        super.onCleared()
        unregisterMediaStoreObserver()
    }

    private fun registerMediaStoreObserver() {
        if (mediaStoreObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                observerReloadJob?.cancel()
                observerReloadJob = viewModelScope.launch {
                    delay(800) // Debounce rapid writes
                    loadSilent()
                }
            }
        }
        mediaStoreObserver = observer
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (_: Exception) {}
    }

    private fun unregisterMediaStoreObserver() {
        mediaStoreObserver?.let { observer ->
            try {
                getApplication<Application>().contentResolver.unregisterContentObserver(observer)
            } catch (_: Exception) {}
            mediaStoreObserver = null
        }
    }

    fun loadSilent() {
        viewModelScope.launch {
            if (_state.value.isSafMode) return@launch
            runCatching { repository.loadMediaStoreItems() }
                .onSuccess { media ->
                    _state.update { current ->
                        if (current.isSafMode) current
                        else current.copy(allItems = media).withDerivedMedia()
                    }
                }
        }
    }

    fun toggleFavoritesFilter() {
        _state.update { current ->
            current.withDerivedMedia(favoritesOnly = !current.isFavoritesOnly)
        }
    }

    fun toggleFavorite(item: DeviceMediaItem) {
        favoritesManager.toggleFavorite(item.id)
    }

    fun toggleFavoriteSelected() {
        val selected = _state.value.selectedItemIds
        if (selected.isEmpty()) return
        val currentFavs = favoritesManager.favoritesFlow.value
        val shouldFavorite = !selected.all { it in currentFavs }
        selected.forEach { id ->
            favoritesManager.setFavorite(id, shouldFavorite)
        }
    }

    fun isFavorite(mediaId: String): Boolean {
        return favoritesManager.isFavorite(mediaId)
    }

    fun setGridColumnCount(columns: Int) {
        _state.update { it.copy(gridColumnCount = columns.coerceIn(2, 5)) }
    }

    fun refreshTrashStats() {
        viewModelScope.launch {
            val stats = trashManager.getTrashStats()
            _state.update {
                it.copy(
                    trashCount = stats.first,
                    isTrashEnabled = trashPreferences.isTrashEnabled,
                )
            }
        }
    }

    fun setPermissionStatus(status: MediaPermissionStatus) {
        _state.update { it.copy(permissionStatus = status) }
        if (status == MediaPermissionStatus.Granted) {
            registerMediaStoreObserver()
            if (_state.value.allItems.isEmpty()) {
                refreshMediaStore()
            }
        }
    }

    fun refreshMediaStore() {
        load(isSafMode = false) {
            repository.loadMediaStoreItems()
        }
    }

    fun loadSafFolder(folderUri: Uri) {
        load(isSafMode = true) {
            repository.loadSafFolderItems(folderUri)
        }
    }

    fun setViewMode(mode: MediaViewMode) {
        _state.update { it.copy(viewMode = mode) }
    }

    fun setCategory(category: MediaCategory) {
        reduceQuery {
            it.copy(
                category = category,
                folderKey = null,
                includeVideos = (category == MediaCategory.Images),
            )
        }
        _state.update { it.copy(activeFolderName = null) }
    }


    fun setSearchText(searchText: String) {
        reduceQuery { it.copy(searchText = searchText) }
    }

    fun setSort(sort: MediaSort) {
        reduceQuery { it.copy(sort = sort) }
    }

    fun setFolder(folderKey: String?) {
        reduceQuery { it.copy(folderKey = folderKey) }
    }

    fun openFolder(folder: MediaFolder) {
        reduceQuery { it.copy(folderKey = folder.key) }
        _state.update {
            it.copy(
                activeFolderName = folder.name,
                viewMode = MediaViewMode.TIMELINE,
            )
        }
    }

    fun exitFolder() {
        reduceQuery { it.copy(folderKey = null) }
        _state.update { it.copy(activeFolderName = null) }
    }

    fun toggleSelection(item: DeviceMediaItem) {
        _state.update { current ->
            val nextSelection = current.selectedItemIds.toMutableSet()
            if (!nextSelection.add(item.id)) {
                nextSelection.remove(item.id)
            }
            current.copy(selectedItemIds = nextSelection)
        }
    }

    fun selectAll() {
        _state.update { current ->
            val allIds = current.visibleItems.map { it.id }.toSet()
            current.copy(selectedItemIds = allIds)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedItemIds = emptySet()) }
    }

    fun openPreview(item: DeviceMediaItem) {
        _state.update { it.copy(previewItem = item) }
    }

    fun closePreview() {
        _state.update { it.copy(previewItem = null) }
    }

    fun setInfoItem(item: DeviceMediaItem?) {
        _state.update { it.copy(infoItem = item) }
    }

    fun setRenameItem(item: DeviceMediaItem?) {
        _state.update { it.copy(renameItem = item) }
    }

    fun setVaultPickerOpen(open: Boolean) {
        _state.update { it.copy(isVaultPickerOpen = open) }
    }

    fun deleteSelected(onFinished: (Int, String) -> Unit) {
        val selectedIds = _state.value.selectedItemIds
        if (selectedIds.isEmpty()) return

        val itemsToDelete = _state.value.allItems.filter { it.id in selectedIds }
        val isTrash = trashPreferences.isTrashEnabled

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isOperationInProgress = true,
                    operationMessage = if (isTrash) "Moving to Trash..." else "Deleting files...",
                )
            }

            var trashEntities: List<TrashEntity>? = null
            if (isTrash) {
                trashEntities = trashManager.prepareMoveToTrash(itemsToDelete)
            }

            val intentSender = repository.createDeleteRequest(itemsToDelete)
            if (intentSender != null) {
                _state.update {
                    it.copy(
                        isOperationInProgress = false,
                        operationMessage = null,
                        pendingDeleteRequest = PendingDeleteRequest(
                            intentSender = intentSender,
                            items = itemsToDelete,
                            trashEntities = trashEntities,
                            isTrash = isTrash,
                        ),
                    )
                }
                return@launch
            }

            var deletedCount = 0
            val deletedIds = mutableSetOf<String>()
            withContext(Dispatchers.IO) {
                for (item in itemsToDelete) {
                    if (repository.deleteItem(item)) {
                        deletedCount++
                        deletedIds.add(item.id)
                    }
                }
            }

            _state.update { current ->
                val updatedAll = current.allItems.filterNot { it.id in deletedIds }
                current.copy(
                    allItems = updatedAll,
                    isOperationInProgress = false,
                    operationMessage = null,
                ).withDerivedMedia()
            }
            clearSelection()
            loadSilent()
            refreshTrashStats()
            val msg = if (isTrash) {
                "Moved $deletedCount file(s) to Trash (auto-deletes in 15 days)"
            } else {
                "Deleted $deletedCount file(s)"
            }
            onFinished(deletedCount, msg)
        }
    }

    fun deleteSingleItem(item: DeviceMediaItem, onFinished: (Boolean, String) -> Unit) {
        val isTrash = trashPreferences.isTrashEnabled
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isOperationInProgress = true,
                    operationMessage = if (isTrash) "Moving to Trash..." else "Deleting file...",
                )
            }

            var trashEntities: List<TrashEntity>? = null
            if (isTrash) {
                trashEntities = trashManager.prepareMoveToTrash(listOf(item))
            }

            val intentSender = repository.createDeleteRequest(listOf(item))
            if (intentSender != null) {
                _state.update {
                    it.copy(
                        isOperationInProgress = false,
                        operationMessage = null,
                        pendingDeleteRequest = PendingDeleteRequest(
                            intentSender = intentSender,
                            items = listOf(item),
                            trashEntities = trashEntities,
                            isTrash = isTrash,
                        ),
                    )
                }
                return@launch
            }

            val success = withContext(Dispatchers.IO) {
                repository.deleteItem(item)
            }

            if (!success && trashEntities != null) {
                trashManager.discardPending(trashEntities)
            }

            _state.update { current ->
                val updatedAll = if (success) {
                    current.allItems.filterNot { it.id == item.id }
                } else {
                    current.allItems
                }
                current.copy(
                    allItems = updatedAll,
                    isOperationInProgress = false,
                    operationMessage = null,
                    previewItem = null,
                ).withDerivedMedia()
            }
            if (success) {
                loadSilent()
            }
            refreshTrashStats()
            val msg = if (success) {
                if (isTrash) "Moved to Trash (auto-deletes in 15 days)" else "Deleted successfully"
            } else {
                "Could not delete file"
            }
            onFinished(success, msg)
        }
    }

    fun onSystemDeleteApproved(request: PendingDeleteRequest, onFinished: (String) -> Unit) {
        viewModelScope.launch {
            val safItems = request.items.filter { it.source == MediaSource.StorageAccessFramework }
            val deletedIds = request.items.map { it.id }.toSet()
            withContext(Dispatchers.IO) {
                for (item in safItems) {
                    repository.deleteItem(item)
                }
            }

            _state.update { current ->
                val updatedAll = current.allItems.filterNot { it.id in deletedIds }
                current.copy(
                    allItems = updatedAll,
                    pendingDeleteRequest = null,
                    previewItem = null,
                ).withDerivedMedia()
            }
            clearSelection()
            loadSilent()
            refreshTrashStats()

            val msg = if (request.isTrash) {
                if (request.items.size == 1) {
                    "Moved to Trash (auto-deletes in 15 days)"
                } else {
                    "Moved ${request.items.size} file(s) to Trash (auto-deletes in 15 days)"
                }
            } else {
                if (request.items.size == 1) "Deleted successfully" else "Deleted ${request.items.size} files"
            }
            onFinished(msg)
        }
    }

    fun onSystemDeleteCancelled(request: PendingDeleteRequest) {
        viewModelScope.launch {
            if (request.trashEntities != null) {
                trashManager.discardPending(request.trashEntities)
            }
            _state.update { it.copy(pendingDeleteRequest = null) }
            refreshTrashStats()
        }
    }

    fun renameMediaItem(item: DeviceMediaItem, newName: String, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(isOperationInProgress = true, operationMessage = "Renaming file...") }
            val success = withContext(Dispatchers.IO) {
                repository.renameItem(item, newName)
            }
            _state.update { it.copy(isOperationInProgress = false, operationMessage = null, renameItem = null) }
            refreshMediaStore()
            onFinished(success)
        }
    }

    fun importSelectedToVault(
        targetFolderId: String?,
        deleteOriginals: Boolean,
        onResult: (Boolean, String) -> Unit,
    ) {
        val selectedIds = _state.value.selectedItemIds
        val itemsToImport = if (selectedIds.isNotEmpty()) {
            _state.value.allItems.filter { it.id in selectedIds }
        } else {
            _state.value.previewItem?.let { listOf(it) } ?: emptyList()
        }

        if (itemsToImport.isEmpty()) {
            onResult(false, "No items selected to import")
            return
        }

        if (!vaultContainer.session.isUnlocked.value) {
            onResult(false, "Vault is locked. Please unlock your vault in the Vault tab first.")
            return
        }

        viewModelScope.launch {
            val totalCount = itemsToImport.size
            _state.update {
                it.copy(
                    isOperationInProgress = true,
                    operationMessage = "Starting vault import (0 of $totalCount)...",
                    operationProgress = 0f,
                    operationCurrent = 0,
                    operationTotal = totalCount,
                    isVaultPickerOpen = false,
                )
            }

            var successCount = 0
            var failCount = 0
            val deletedIds = mutableSetOf<String>()
            val contentResolver = getApplication<Application>().contentResolver

            withContext(Dispatchers.IO) {
                vaultContainer.session.withVmkSuspend { vmk ->
                    itemsToImport.forEachIndexed { index, item ->
                        val currentIdx = index + 1
                        _state.update {
                            it.copy(
                                operationMessage = "Encrypting & importing $currentIdx of $totalCount: ${item.displayName}",
                                operationProgress = currentIdx.toFloat() / totalCount.toFloat(),
                                operationCurrent = currentIdx,
                                operationTotal = totalCount,
                            )
                        }
                        try {
                            val uri = item.uri ?: return@forEachIndexed
                            contentResolver.openInputStream(uri)?.use { stream ->
                                vaultContainer.repository.importStream(
                                    inputStream = stream,
                                    displayName = item.displayName,
                                    originalSize = item.sizeBytes,
                                    mimeType = item.mimeType,
                                    destinationFolderId = targetFolderId,
                                    vmk = vmk,
                                )
                                successCount++
                                if (deleteOriginals) {
                                    if (repository.deleteItem(item)) {
                                        deletedIds.add(item.id)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            failCount++
                        }
                    }
                }
            }

            _state.update { current ->
                val updatedAll = if (deleteOriginals && deletedIds.isNotEmpty()) {
                    current.allItems.filterNot { it.id in deletedIds }
                } else {
                    current.allItems
                }
                current.copy(
                    allItems = updatedAll,
                    isOperationInProgress = false,
                    operationMessage = null,
                    operationProgress = null,
                    operationCurrent = 0,
                    operationTotal = 0,
                    previewItem = null,
                ).withDerivedMedia()
            }
            clearSelection()
            if (deleteOriginals && deletedIds.isNotEmpty()) {
                loadSilent()
            }

            if (successCount > 0) {
                onResult(true, "Successfully imported $successCount file(s) into vault${if (failCount > 0) " ($failCount failed)" else ""}")
            } else {
                onResult(false, "Failed to import selected files into vault")
            }
        }
    }

    private fun reduceQuery(reducer: (MediaQuery) -> MediaQuery) {
        _state.update { current ->
            current.withDerivedMedia(query = reducer(current.query))
        }
    }

    private fun load(
        isSafMode: Boolean,
        loader: suspend () -> List<DeviceMediaItem>,
    ) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    selectedItemIds = emptySet(),
                    previewItem = null,
                )
            }

            runCatching { loader() }
                .onSuccess { media ->
                    _state.update { current ->
                        current
                            .copy(
                                allItems = media,
                                isLoading = false,
                                errorMessage = null,
                                isSafMode = isSafMode,
                            )
                            .withDerivedMedia()
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.message ?: "Unable to load media.",
                        )
                    }
                }
        }
    }

    private fun MediaBrowserState.withDerivedMedia(
        query: MediaQuery = this.query,
        favoritesOnly: Boolean = this.isFavoritesOnly,
        favIds: Set<String> = this.favoritedIds,
    ): MediaBrowserState {
        var visible = filterAndSortMedia(allItems, query)
        if (favoritesOnly) {
            visible = visible.filter { it.id in favIds }
        }
        val derivedFolders = if (query.category == MediaCategory.Audio) {
            foldersForCategory(allItems, MediaCategory.Audio)
        } else {
            foldersForGallery(allItems)
        }
        return copy(
            query = query,
            isFavoritesOnly = favoritesOnly,
            favoritedIds = favIds,
            visibleItems = visible,
            folders = derivedFolders,
            selectedItemIds = selectedItemIds.intersect(visible.map { it.id }.toSet()),
        )
    }

}
