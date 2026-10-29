package com.vaultsync.feature.vault

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.model.VaultFolder
import com.vaultsync.core.model.VaultSortOption
import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.security.VaultRecoveryKeyHelper
import com.vaultsync.core.security.VaultSession
import com.vaultsync.core.storage.VaultPreviewManager
import com.vaultsync.core.vault.VaultContainer
import com.vaultsync.core.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import javax.crypto.Cipher

enum class VaultViewTab {
    PHOTOS,
    ALBUMS,
    AUDIO,
}

data class VaultAlbumDisplay(
    val folder: VaultFolder,
    val previewEntries: List<VaultEntry>,
    val totalCount: Int,
)

data class VaultUiState(
    val isInitialized: Boolean = false,
    val isUnlocked: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val canUseBiometric: Boolean = false,
    val viewTab: VaultViewTab = VaultViewTab.ALBUMS,
    val selectedEntryIds: Set<String> = emptySet(),
    val currentFolderId: String? = null,
    val currentFolder: VaultFolder? = null,
    val breadcrumbs: List<VaultFolder> = emptyList(),
    val selectedCategory: VaultCategory = VaultCategory.All,
    val searchQuery: String = "",
    val sortOption: VaultSortOption = VaultSortOption.NAME_ASC,
    val isGridView: Boolean = true,
    val isImporting: Boolean = false,
    val importProgressText: String? = null,
    val activePreviewEntry: VaultEntry? = null,
    val previewFile: File? = null,
    val isDecryptingPreview: Boolean = false,
    val previewError: String? = null,
    val activeViewerItems: List<VaultEntry> = emptyList(),
    val activeViewerIndex: Int? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val isCreateFolderOpen: Boolean = false,
    val folderToRename: VaultFolder? = null,
    val folderToMove: VaultFolder? = null,
    val folderToDelete: VaultFolder? = null,
    val entryToRename: VaultEntry? = null,
    val entryToMove: VaultEntry? = null,
    val entryToCopy: VaultEntry? = null,
    val entryToDelete: VaultEntry? = null,
    val entryToShowDetails: VaultEntry? = null,
    val isStorageDialogOpen: Boolean = false,
    val totalFilesCount: Int = 0,
    val totalVaultSizeBytes: Long = 0L,
    val availableStorageBytes: Long = 0L,
    val hasRecoveryKey: Boolean = false,
    val showRecoveryKeyDialog: Boolean = false,
    val activeRecoveryKey: String? = null,
    val showForgotPinDialog: Boolean = false,
    val isRecoveryVerified: Boolean = false,
    val recoveryErrorMessage: String? = null,
    val detectedVault: com.vaultsync.core.recovery.VaultDiscoveryResult.Detected? = null,
    val isSharedStorageActive: Boolean = false,
    val isMigrationInProgress: Boolean = false,
    val migrationProgress: com.vaultsync.core.migration.MigrationProgress? = null,
    val showMigrationDialog: Boolean = false,
    val showRestoreDialog: Boolean = false,
) {
    val hasSelection: Boolean get() = selectedEntryIds.isNotEmpty()
}

class VaultViewModel(
    application: Application,
    private val repository: VaultRepository,
    private val session: VaultSession,
    val previewManager: VaultPreviewManager,
    private val container: com.vaultsync.core.vault.VaultContainerInstance,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        repository = VaultContainer.get(application).repository,
        session = VaultContainer.get(application).session,
        previewManager = VaultContainer.get(application).previewManager,
        container = VaultContainer.get(application),
    )

    private val _uiState = MutableStateFlow(
        VaultUiState(
            isInitialized = container.credentialStore.isInitialized,
            isUnlocked = session.isUnlocked.value,
            isBiometricEnabled = container.credentialStore.isBiometricEnabled,
            canUseBiometric = container.credentialStore.canUseBiometric(),
            hasRecoveryKey = container.credentialStore.hasRecoveryKey,
            isSharedStorageActive = container.storageManager.isSharedStorageActive,
        ),
    )
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    init {
        if (!container.credentialStore.isInitialized) {
            checkForExistingVault()
        }
    }

    val allFolders: StateFlow<List<VaultFolder>> = repository.getAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredFolders: StateFlow<List<VaultFolder>> =
        combine(repository.getAllFolders(), _uiState) { all, state ->
            if (state.selectedCategory != VaultCategory.All && state.currentFolderId == null) {
                emptyList()
            } else {
                val currentLevel = all.filter { it.parentId == state.currentFolderId }
                val query = state.searchQuery.trim().lowercase()
                val searched = if (query.isBlank()) currentLevel else currentLevel.filter { it.name.lowercase().contains(query) }
                sortFolders(searched, state.sortOption)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredEntries: StateFlow<List<VaultEntry>> =
        combine(repository.getAllEntries(), _uiState) { allEntries, state ->
            val inScope = allEntries.filter { entry ->
                entry.folderId == state.currentFolderId &&
                    (state.selectedCategory == VaultCategory.All || entry.category == state.selectedCategory)
            }
            val query = state.searchQuery.trim().lowercase()
            val searched = if (query.isBlank()) inScope else inScope.filter { it.originalFileName.lowercase().contains(query) }
            sortEntries(searched, state.sortOption)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val thumbnailManager: com.vaultsync.core.storage.VaultThumbnailManager = container.thumbnailManager
    private val _thumbnailCache = MutableStateFlow<Map<String, File>>(emptyMap())
    val thumbnailCache: StateFlow<Map<String, File>> = _thumbnailCache.asStateFlow()

    fun loadThumbnail(entry: VaultEntry) {
        if (_thumbnailCache.value.containsKey(entry.id)) return
        if (entry.category != VaultCategory.Images && entry.category != VaultCategory.Videos) return

        viewModelScope.launch(Dispatchers.IO) {
            val thumb = session.withVmkSuspend { vmk ->
                thumbnailManager.getOrCreateThumbnail(entry, vmk)
            }
            if (thumb != null) {
                _thumbnailCache.update { it + (entry.id to thumb) }
            }
        }
    }

    val photoEntries: StateFlow<List<VaultEntry>> =
        combine(repository.getAllEntries(), _uiState) { entries, state ->
            val media = entries.filter {
                (it.category == VaultCategory.Images || it.category == VaultCategory.Videos) &&
                it.folderId == state.currentFolderId
            }
            val query = state.searchQuery.trim().lowercase()
            val filtered = if (query.isBlank()) media else media.filter { it.originalFileName.lowercase().contains(query) }
            sortEntries(filtered, state.sortOption)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val audioEntries: StateFlow<List<VaultEntry>> =
        combine(repository.getAllEntries(), _uiState) { entries, state ->
            val audios = entries.filter {
                it.category == VaultCategory.Audio &&
                (state.currentFolderId == null || it.folderId == state.currentFolderId)
            }
            val query = state.searchQuery.trim().lowercase()
            val filtered = if (query.isBlank()) audios else audios.filter { it.originalFileName.lowercase().contains(query) }
            sortEntries(filtered, state.sortOption)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vaultAlbums: StateFlow<List<VaultAlbumDisplay>> =
        combine(repository.getAllFolders(), repository.getAllEntries(), _uiState) { folders, entries, state ->
            val query = state.searchQuery.trim().lowercase()
            val list = mutableListOf<VaultAlbumDisplay>()

            // 1. Root folder for unfiled vault media
            val rootMedia = entries.filter { it.folderId == null && it.category != VaultCategory.Audio }
            if (rootMedia.isNotEmpty()) {
                val rootFolder = VaultFolder(
                    id = "__root__",
                    name = "Main Vault",
                    parentId = null,
                    createdAtMillis = 0L,
                    updatedAtMillis = 0L,
                    itemCount = rootMedia.size,
                )
                if (query.isBlank() || rootFolder.name.lowercase().contains(query)) {
                    list.add(VaultAlbumDisplay(rootFolder, rootMedia.take(3), rootMedia.size))
                }
            }

            // 2. Subfolders
            val topLevelFolders = folders.filter { it.parentId == null }
            for (folder in topLevelFolders) {
                val folderMedia = entries.filter { it.folderId == folder.id && it.category != VaultCategory.Audio }
                if (query.isBlank() || folder.name.lowercase().contains(query)) {
                    list.add(VaultAlbumDisplay(folder, folderMedia.take(3), folderMedia.size))
                }
            }
            list
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setViewTab(tab: VaultViewTab) {
        _uiState.update { it.copy(viewTab = tab, currentFolderId = null, currentFolder = null) }
        updateBreadcrumbs()
    }

    fun openViewer(items: List<VaultEntry>, index: Int) {
        _uiState.update { it.copy(activeViewerItems = items, activeViewerIndex = index) }
    }

    fun closeViewer() {
        val items = _uiState.value.activeViewerItems
        _uiState.update { it.copy(activeViewerItems = emptyList(), activeViewerIndex = null) }
        viewModelScope.launch {
            for (item in items) {
                try {
                    repository.setEntryInUse(item.id, false)
                } catch (_: Exception) { }
            }
        }
    }


    init {
        viewModelScope.launch {
            session.isUnlocked.collect { unlocked ->
                _uiState.update { it.copy(isUnlocked = unlocked) }
                if (unlocked) {
                    refreshFolderMetadata()
                }
            }
        }

        viewModelScope.launch {
            allFolders.collect {
                updateBreadcrumbs()
            }
        }

        if (!container.credentialStore.isInitialized) {
            checkForExistingVault()
        }
    }

    fun setupVault(pinOrPassword: String, enableBiometric: Boolean) {
        val cleanPin = pinOrPassword.trim()
        if (cleanPin.length < 4) {
            _uiState.update { it.copy(errorMessage = "PIN / Password must be at least 4 characters.") }
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val vmk = VaultKeyManager.generateVaultMasterKey()
            try {
                // 1. Wrap VMK with user PIN
                val wrapped = VaultKeyManager.wrapVaultMasterKey(vmk, cleanPin.toCharArray())
                container.credentialStore.saveCredentials(wrapped)

                // 2. Generate and wrap with secure Recovery Key
                val recoveryKey = VaultRecoveryKeyHelper.generateRecoveryKey()
                val cleanRecoveryKey = VaultRecoveryKeyHelper.normalizeKey(recoveryKey)
                val recoveryWrapped = VaultKeyManager.wrapVaultMasterKey(vmk, cleanRecoveryKey.toCharArray())
                val encryptedDisplayKey = VaultRecoveryKeyHelper.encryptRecoveryKeyForDisplay(recoveryKey, vmk)
                container.credentialStore.saveRecoveryCredentials(recoveryWrapped, encryptedDisplayKey)

                // 3. Biometric setup if requested
                if (enableBiometric) {
                    val success = container.credentialStore.enableBiometric(vmk)
                    _uiState.update { it.copy(isBiometricEnabled = success, canUseBiometric = success) }
                }

                // 4. Unlock session
                session.unlock(vmk)
                syncManifestIfSharedStorage()
                _uiState.update {
                    it.copy(
                        isInitialized = true,
                        isUnlocked = true,
                        errorMessage = null,
                        infoMessage = "Vault initialized and secured.",
                        hasRecoveryKey = true,
                        activeRecoveryKey = recoveryKey,
                        showRecoveryKeyDialog = true,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Setup failed: ${e.message}") }
            } finally {
                VaultKeyManager.zeroize(vmk)
            }
        }
    }

    fun unlockWithPin(pinOrPassword: String) {
        val clean = pinOrPassword.trim()
        if (clean.isEmpty()) return

        val wrappedVmk = container.credentialStore.getWrappedVmk()
        if (wrappedVmk == null) {
            _uiState.update { it.copy(errorMessage = "Vault credentials not found. Please setup vault.") }
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val vmk = VaultKeyManager.unwrapVaultMasterKey(wrappedVmk, clean.toCharArray())
                session.unlock(vmk)
                VaultKeyManager.zeroize(vmk)
                _uiState.update { it.copy(isUnlocked = true, errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Incorrect PIN or password. Please try again.") }
            }
        }
    }

    fun unlockWithBiometric(cipher: Cipher) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val vmk = container.credentialStore.unwrapBiometricVmk(cipher)
                if (vmk != null && vmk.size == 32) {
                    session.unlock(vmk)
                    VaultKeyManager.zeroize(vmk)
                    _uiState.update { it.copy(isUnlocked = true, errorMessage = null) }
                } else {
                    _uiState.update { it.copy(errorMessage = "Biometric authentication failed to unwrap key.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Biometric error: ${e.message}") }
            }
        }
    }

    fun lockVault() {
        viewModelScope.launch {
            closePreview()
            closeViewer()
            try {
                com.vaultsync.core.floating.FloatingWindowManager.get(getApplication()).closeVaultWindows()
            } catch (_: Exception) {}
            previewManager.clearAllPreviews()
            thumbnailManager.clearAllThumbnails()
            _thumbnailCache.value = emptyMap()
            session.lock()
            _uiState.update {
                it.copy(
                    isUnlocked = false,
                    currentFolderId = null,
                    currentFolder = null,
                    breadcrumbs = emptyList(),
                    selectedCategory = VaultCategory.All,
                    searchQuery = "",
                )
            }
        }
    }

    fun openInFloatingWindow(entry: VaultEntry) {
        viewModelScope.launch {
            try {
                val file = decryptEntryForPreview(entry)
                com.vaultsync.core.floating.FloatingWindowManager.get(getApplication()).openVaultMedia(entry, file)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to open in floating window: ${e.message}") }
            }
        }
    }

    fun openMultipleInFloatingWindow(entries: List<VaultEntry>) {
        viewModelScope.launch {
            val pairs = mutableListOf<Pair<VaultEntry, java.io.File>>()
            for (entry in entries) {
                try {
                    val file = decryptEntryForPreview(entry)
                    pairs.add(entry to file)
                } catch (_: Exception) {}
            }
            if (pairs.isNotEmpty()) {
                com.vaultsync.core.floating.FloatingWindowManager.get(getApplication()).openVaultMediaMultiple(pairs)
            }
        }
    }

    fun navigateToFolder(folderId: String?) {
        _uiState.update { it.copy(currentFolderId = folderId, selectedCategory = VaultCategory.All) }
        updateBreadcrumbs()
    }

    fun navigateUp() {
        val currentId = _uiState.value.currentFolderId ?: return
        val current = allFolders.value.firstOrNull { it.id == currentId }
        navigateToFolder(current?.parentId)
    }

    fun selectCategory(category: VaultCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSortOption(option: VaultSortOption) {
        _uiState.update { it.copy(sortOption = option) }
    }

    fun toggleViewMode() {
        _uiState.update { it.copy(isGridView = !it.isGridView) }
    }

    fun openCreateFolderDialog() = _uiState.update { it.copy(isCreateFolderOpen = true) }
    fun closeCreateFolderDialog() = _uiState.update { it.copy(isCreateFolderOpen = false) }

    fun createFolder(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            try {
                repository.createFolder(clean, _uiState.value.currentFolderId)
                closeCreateFolderDialog()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Could not create folder: ${e.message}") }
            }
        }
    }

    fun openRenameFolderDialog(folder: VaultFolder) = _uiState.update { it.copy(folderToRename = folder) }
    fun closeRenameFolderDialog() = _uiState.update { it.copy(folderToRename = null) }

    fun renameFolder(folderId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            try {
                repository.renameFolder(folderId, clean)
                closeRenameFolderDialog()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Could not rename folder: ${e.message}") }
            }
        }
    }

    fun openMoveFolderDialog(folder: VaultFolder) = _uiState.update { it.copy(folderToMove = folder) }
    fun closeMoveFolderDialog() = _uiState.update { it.copy(folderToMove = null) }

    fun moveFolder(folderId: String, newParentId: String?) {
        viewModelScope.launch {
            try {
                repository.moveFolder(folderId, newParentId)
                closeMoveFolderDialog()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Cannot move folder: ${e.message}") }
            }
        }
    }

    fun openDeleteFolderDialog(folder: VaultFolder) = _uiState.update { it.copy(folderToDelete = folder) }
    fun closeDeleteFolderDialog() = _uiState.update { it.copy(folderToDelete = null) }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            try {
                repository.deleteFolder(folderId)
                closeDeleteFolderDialog()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        folderToDelete = null,
                        errorMessage = e.message ?: "Failed to delete folder",
                    )
                }
            }
        }
    }

    fun openRenameEntryDialog(entry: VaultEntry) = _uiState.update { it.copy(entryToRename = entry) }
    fun closeRenameEntryDialog() = _uiState.update { it.copy(entryToRename = null) }

    fun renameEntry(entryId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            try {
                repository.renameEntry(entryId, clean)
                closeRenameEntryDialog()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Could not rename file: ${e.message}") }
            }
        }
    }

    fun openMoveEntryDialog(entry: VaultEntry) = _uiState.update { it.copy(entryToMove = entry) }
    fun closeMoveEntryDialog() = _uiState.update { it.copy(entryToMove = null) }

    fun moveEntry(entryId: String, targetFolderId: String?) {
        viewModelScope.launch {
            try {
                repository.moveEntry(entryId, targetFolderId)
                closeMoveEntryDialog()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Cannot move file: ${e.message}") }
            }
        }
    }

    fun openCopyEntryDialog(entry: VaultEntry) = _uiState.update { it.copy(entryToCopy = entry) }
    fun closeCopyEntryDialog() = _uiState.update { it.copy(entryToCopy = null) }

    fun copyEntry(entryId: String, targetFolderId: String?) {
        viewModelScope.launch {
            try {
                session.withVmkSuspend { vmk ->
                    repository.copyEntry(entryId, targetFolderId, vmk)
                }
                closeCopyEntryDialog()
                _uiState.update { it.copy(infoMessage = "File copied successfully.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Cannot copy file: ${e.message}") }
            }
        }
    }

    fun openDeleteEntryDialog(entry: VaultEntry) = _uiState.update { it.copy(entryToDelete = entry) }
    fun closeDeleteEntryDialog() = _uiState.update { it.copy(entryToDelete = null) }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            try {
                // If viewer is currently showing this entry, update viewer items or close viewer
                val currentViewerItems = _uiState.value.activeViewerItems
                if (currentViewerItems.any { it.id == entryId }) {
                    val remaining = currentViewerItems.filter { it.id != entryId }
                    if (remaining.isEmpty()) {
                        closeViewer()
                    } else {
                        val currentIdx = _uiState.value.activeViewerIndex ?: 0
                        val nextIdx = currentIdx.coerceAtMost(remaining.size - 1)
                        _uiState.update {
                            it.copy(
                                activeViewerItems = remaining,
                                activeViewerIndex = nextIdx,
                            )
                        }
                    }
                }
                if (_uiState.value.activePreviewEntry?.id == entryId) {
                    closePreview()
                }

                try {
                    repository.setEntryInUse(entryId, false)
                } catch (_: Exception) {}

                repository.deleteEntry(entryId)
                thumbnailManager.deleteThumbnail(entryId)
                _thumbnailCache.update { it - entryId }
                closeDeleteEntryDialog()
                _uiState.update { it.copy(infoMessage = "File deleted successfully") }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        entryToDelete = null,
                        errorMessage = e.message ?: "Failed to delete file",
                    )
                }
            }
        }
    }

    // --- Multi-Selection & Batch Operations ---
    fun toggleSelectEntry(entryId: String) {
        _uiState.update { state ->
            val current = state.selectedEntryIds
            val updated = if (entryId in current) current - entryId else current + entryId
            state.copy(selectedEntryIds = updated)
        }
    }

    fun selectAllEntries(entryIds: List<String>) {
        _uiState.update { it.copy(selectedEntryIds = entryIds.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedEntryIds = emptySet()) }
    }

    fun moveSelectedEntries(targetFolderId: String?) {
        val selectedIds = _uiState.value.selectedEntryIds
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            try {
                for (id in selectedIds) {
                    repository.moveEntry(id, targetFolderId)
                }
                val count = selectedIds.size
                clearSelection()
                _uiState.update { it.copy(infoMessage = "Moved $count file(s)") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Move failed: ${e.message}") }
            }
        }
    }

    fun copySelectedEntries(targetFolderId: String?) {
        val selectedIds = _uiState.value.selectedEntryIds
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            try {
                session.withVmkSuspend { vmk ->
                    for (id in selectedIds) {
                        repository.copyEntry(id, targetFolderId, vmk)
                    }
                }
                val count = selectedIds.size
                clearSelection()
                _uiState.update { it.copy(infoMessage = "Copied $count file(s)") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Copy failed: ${e.message}") }
            }
        }
    }

    fun deleteSelectedEntries() {
        val selectedIds = _uiState.value.selectedEntryIds
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            try {
                for (id in selectedIds) {
                    try {
                        repository.setEntryInUse(id, false)
                    } catch (_: Exception) {}
                    repository.deleteEntry(id)
                    thumbnailManager.deleteThumbnail(id)
                    _thumbnailCache.update { it - id }
                }
                val count = selectedIds.size
                clearSelection()
                _uiState.update { it.copy(infoMessage = "Deleted $count file(s)") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Delete failed: ${e.message}") }
            }
        }
    }

    // --- Move Out of Vault (Restore to Gallery) ---
    fun moveOutOfVault(entry: VaultEntry) {
        moveMultipleOutOfVault(listOf(entry))
    }

    fun moveSelectedOutOfVault() {
        val selectedIds = _uiState.value.selectedEntryIds
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            val all = repository.getAllEntries().first()
            val entriesToMove = all.filter { it.id in selectedIds }
            if (entriesToMove.isNotEmpty()) {
                moveMultipleOutOfVault(entriesToMove)
            }
        }
    }

    private fun moveMultipleOutOfVault(entriesToMove: List<VaultEntry>) {
        if (entriesToMove.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importProgressText = "Restoring to gallery...") }
            var successCount = 0
            var errorCount = 0

            withContext(Dispatchers.IO) {
                session.withVmkSuspend { vmk ->
                    val context = getApplication<Application>()
                    val resolver = context.contentResolver
                    for (entry in entriesToMove) {
                        try {
                            val success = restoreEntryToGallery(context, resolver, entry, vmk)
                            if (success) {
                                try {
                                    repository.setEntryInUse(entry.id, false)
                                } catch (_: Exception) {}
                                repository.deleteEntry(entry.id)
                                thumbnailManager.deleteThumbnail(entry.id)
                                _thumbnailCache.update { it - entry.id }
                                successCount++
                            } else {
                                errorCount++
                            }
                        } catch (e: Exception) {
                            errorCount++
                        }
                    }
                }
            }

            val remainingViewer = _uiState.value.activeViewerItems.filter { item ->
                entriesToMove.none { it.id == item.id }
            }
            if (_uiState.value.activeViewerItems.isNotEmpty()) {
                if (remainingViewer.isEmpty()) {
                    closeViewer()
                } else {
                    val currentIdx = _uiState.value.activeViewerIndex ?: 0
                    val nextIdx = currentIdx.coerceAtMost(remainingViewer.size - 1)
                    _uiState.update {
                        it.copy(
                            activeViewerItems = remainingViewer,
                            activeViewerIndex = nextIdx,
                        )
                    }
                }
            }
            if (entriesToMove.any { it.id == _uiState.value.activePreviewEntry?.id }) {
                closePreview()
            }

            clearSelection()
            _uiState.update {
                it.copy(
                    isImporting = false,
                    importProgressText = null,
                    infoMessage = if (errorCount == 0) "Restored $successCount file(s) to gallery"
                    else "Restored $successCount file(s), $errorCount failed",
                )
            }
        }
    }

    private suspend fun restoreEntryToGallery(
        context: Context,
        resolver: ContentResolver,
        entry: VaultEntry,
        vmk: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("restore_", ".tmp", context.cacheDir)
        try {
            repository.exportOrDecrypt(entry.id, tempFile, vmk)

            val isVideo = entry.category == VaultCategory.Videos
            val isAudio = entry.category == VaultCategory.Audio
            val isImage = entry.category == VaultCategory.Images

            val collectionUri = when {
                isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                isAudio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                }
            }

            val relativeSubfolder = when {
                isVideo -> Environment.DIRECTORY_MOVIES + "/VaultSync"
                isAudio -> Environment.DIRECTORY_MUSIC + "/VaultSync"
                isImage -> Environment.DIRECTORY_PICTURES + "/VaultSync"
                else -> Environment.DIRECTORY_DOCUMENTS + "/VaultSync"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.originalFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeSubfolder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(collectionUri, values) ?: return@withContext false

            resolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: run {
                resolver.delete(uri, null, null)
                return@withContext false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(tempFile.absolutePath),
                    arrayOf(entry.mimeType),
                    null,
                )
            }

            true
        } catch (e: Exception) {
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun showEntryDetails(entry: VaultEntry) = _uiState.update { it.copy(entryToShowDetails = entry) }
    fun closeEntryDetails() = _uiState.update { it.copy(entryToShowDetails = null) }

    fun importUris(uris: List<Uri>, contentResolver: ContentResolver) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importProgressText = "Preparing import...") }
            var successCount = 0
            var failureCount = 0

            var lastErrorMessage: String? = null

            withContext(Dispatchers.IO) {
                for ((index, uri) in uris.withIndex()) {
                    _uiState.update {
                        it.copy(importProgressText = "Encrypting file ${index + 1} of ${uris.size}...")
                    }

                    var displayName = "file_${System.currentTimeMillis()}"
                    var size = -1L
                    val mimeType = contentResolver.getType(uri)

                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                        }
                    }

                    try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            session.withVmkSuspend { vmk ->
                                repository.importStream(
                                    inputStream = stream,
                                    displayName = displayName,
                                    originalSize = if (size > 0) size else -1L,
                                    mimeType = mimeType,
                                    destinationFolderId = _uiState.value.currentFolderId,
                                    vmk = vmk,
                                )
                            }
                        }
                        successCount++
                    } catch (e: Exception) {
                        e.printStackTrace()
                        failureCount++
                        lastErrorMessage = e.message
                    }
                }
            }

            _uiState.update {
                it.copy(
                    isImporting = false,
                    importProgressText = null,
                    errorMessage = if (successCount == 0 && failureCount > 0) {
                        "Import failed: ${lastErrorMessage ?: "insufficient storage or invalid file"}"
                    } else null,
                    infoMessage = if (successCount > 0) {
                        if (failureCount == 0) {
                            "Successfully imported $successCount file(s)."
                        } else {
                            "Imported $successCount file(s). Failed: $failureCount (${lastErrorMessage ?: "error"})."
                        }
                    } else null,
                )
            }
        }
    }

    fun importMediaItems(
        items: List<com.vaultsync.core.media.DeviceMediaItem>,
        contentResolver: ContentResolver,
        onFinished: (Boolean, String) -> Unit = { _, _ -> },
    ) {
        if (items.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importProgressText = "Preparing import...") }
            var successCount = 0
            var failureCount = 0
            var lastErrorMessage: String? = null

            withContext(Dispatchers.IO) {
                for ((index, item) in items.withIndex()) {
                    _uiState.update {
                        it.copy(importProgressText = "Encrypting file ${index + 1} of ${items.size}...")
                    }

                    val uri = item.uri
                    if (uri == null) {
                        failureCount++
                        continue
                    }

                    try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            session.withVmkSuspend { vmk ->
                                repository.importStream(
                                    inputStream = stream,
                                    displayName = item.displayName,
                                    originalSize = if (item.sizeBytes >= 0) item.sizeBytes else 0L,
                                    mimeType = item.mimeType,
                                    destinationFolderId = _uiState.value.currentFolderId,
                                    vmk = vmk,
                                )
                            }
                        }
                        successCount++
                    } catch (e: Exception) {
                        e.printStackTrace()
                        failureCount++
                        lastErrorMessage = e.message
                    }
                }
            }

            val message = if (successCount > 0) {
                if (failureCount == 0) "Successfully imported $successCount file(s) into vault."
                else "Imported $successCount file(s). Failed: $failureCount (${lastErrorMessage ?: "error"})."
            } else {
                "Import failed: ${lastErrorMessage ?: "insufficient storage or invalid file"}"
            }

            _uiState.update {
                it.copy(
                    isImporting = false,
                    importProgressText = null,
                    errorMessage = if (successCount == 0 && failureCount > 0) message else null,
                    infoMessage = if (successCount > 0) message else null,
                )
            }

            onFinished(successCount > 0, message)
        }
    }


    fun openPreview(entry: VaultEntry) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activePreviewEntry = entry,
                    previewFile = null,
                    isDecryptingPreview = true,
                    previewError = null,
                )
            }

            try {
                val file = session.withVmkSuspend { vmk ->
                    previewManager.decryptForPreview(entry, vmk)
                }
                _uiState.update {
                    it.copy(
                        previewFile = file,
                        isDecryptingPreview = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDecryptingPreview = false,
                        previewError = "Failed to decrypt preview: ${e.message}",
                    )
                }
            }
        }
    }

    fun closePreview() {
        val entry = _uiState.value.activePreviewEntry
        val file = _uiState.value.previewFile
        viewModelScope.launch {
            if (entry != null) {
                previewManager.releasePreview(entry, file)
            }
            _uiState.update {
                it.copy(
                    activePreviewEntry = null,
                    previewFile = null,
                    isDecryptingPreview = false,
                    previewError = null,
                )
            }
        }
    }

    suspend fun decryptEntryForPreview(entry: VaultEntry): File {
        return session.withVmkSuspend { vmk ->
            previewManager.decryptForPreview(entry, vmk)
        }
    }

    suspend fun releasePreviewFile(entry: VaultEntry, file: File?) {
        previewManager.releasePreview(entry, file)
    }

    fun exportEntry(entry: VaultEntry, destinationUri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(destinationUri)?.use { output ->
                    val temp = File.createTempFile("export_", ".tmp", getApplication<Application>().cacheDir)
                    try {
                        session.withVmkSuspend { vmk ->
                            repository.exportOrDecrypt(entry.id, temp, vmk)
                        }
                        temp.inputStream().use { input ->
                            input.copyTo(output)
                        }
                        _uiState.update { it.copy(infoMessage = "File exported successfully.") }
                    } finally {
                        temp.delete()
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Export failed: ${e.message}") }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun setErrorMessage(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun toggleBiometric(enable: Boolean) {
        viewModelScope.launch {
            if (enable) {
                try {
                    session.withVmk { vmk ->
                        val success = container.credentialStore.enableBiometric(vmk)
                        _uiState.update {
                            it.copy(
                                isBiometricEnabled = success,
                                canUseBiometric = success,
                                infoMessage = if (success) "Biometric unlock enabled." else "Failed to enable biometric.",
                            )
                        }
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = "Cannot enable biometric: ${e.message}") }
                }
            } else {
                container.credentialStore.disableBiometric()
                _uiState.update {
                    it.copy(
                        isBiometricEnabled = false,
                        canUseBiometric = false,
                        infoMessage = "Biometric unlock disabled.",
                    )
                }
            }
        }
    }

    fun openStorageDialog() {
        viewModelScope.launch {
            val allEntries = repository.getAllEntries().first()
            val totalSize = allEntries.sumOf { it.encryptedSizeBytes }
            val available = container.storageManager.getAvailableStorageBytes()
            _uiState.update {
                it.copy(
                    isStorageDialogOpen = true,
                    totalFilesCount = allEntries.size,
                    totalVaultSizeBytes = totalSize,
                    availableStorageBytes = available,
                )
            }
        }
    }

    fun closeStorageDialog() {
        _uiState.update { it.copy(isStorageDialogOpen = false) }
    }

    fun getBiometricCipher(): Cipher? {
        return container.credentialStore.getBiometricCipherForDecryption()
    }

    private fun updateBreadcrumbs() {
        val currentId = _uiState.value.currentFolderId
        if (currentId == null) {
            _uiState.update { it.copy(breadcrumbs = emptyList(), currentFolder = null) }
            return
        }

        val all = allFolders.value
        val chain = mutableListOf<VaultFolder>()
        var curr: VaultFolder? = all.firstOrNull { it.id == currentId }
        val targetFolder = curr

        while (curr != null) {
            chain.add(0, curr)
            curr = curr.parentId?.let { pId -> all.firstOrNull { it.id == pId } }
        }

        _uiState.update { it.copy(breadcrumbs = chain, currentFolder = targetFolder) }
    }

    private fun refreshFolderMetadata() {
        updateBreadcrumbs()
    }

    private fun sortFolders(folders: List<VaultFolder>, option: VaultSortOption): List<VaultFolder> {
        return when (option) {
            VaultSortOption.NAME_ASC -> folders.sortedBy { it.name.lowercase() }
            VaultSortOption.NAME_DESC -> folders.sortedByDescending { it.name.lowercase() }
            VaultSortOption.DATE_DESC -> folders.sortedByDescending { it.updatedAtMillis }
            VaultSortOption.DATE_ASC -> folders.sortedBy { it.updatedAtMillis }
            VaultSortOption.SIZE_DESC, VaultSortOption.SIZE_ASC -> folders.sortedBy { it.name.lowercase() }
        }
    }

    private fun sortEntries(entries: List<VaultEntry>, option: VaultSortOption): List<VaultEntry> {
        return when (option) {
            VaultSortOption.NAME_ASC -> entries.sortedBy { it.originalFileName.lowercase() }
            VaultSortOption.NAME_DESC -> entries.sortedByDescending { it.originalFileName.lowercase() }
            VaultSortOption.DATE_DESC -> entries.sortedByDescending { it.dateModifiedMillis }
            VaultSortOption.DATE_ASC -> entries.sortedBy { it.dateModifiedMillis }
            VaultSortOption.SIZE_DESC -> entries.sortedByDescending { it.originalSizeBytes }
            VaultSortOption.SIZE_ASC -> entries.sortedBy { it.originalSizeBytes }
        }
    }

    private var verifiedRecoveryVmk: ByteArray? = null

    override fun onCleared() {
        super.onCleared()
        verifiedRecoveryVmk?.let {
            VaultKeyManager.zeroize(it)
            verifiedRecoveryVmk = null
        }
    }

    fun loadOrGenerateRecoveryKey() {
        if (!session.isUnlocked.value) return

        viewModelScope.launch(Dispatchers.Default) {
            session.withVmkSuspend { vmk ->
                val existingEncrypted = container.credentialStore.getEncryptedDisplayRecoveryKey()
                var key: String? = null
                if (existingEncrypted != null) {
                    key = VaultRecoveryKeyHelper.decryptRecoveryKeyForDisplay(existingEncrypted, vmk)
                }
                if (key == null) {
                    val newKey = VaultRecoveryKeyHelper.generateRecoveryKey()
                    val clean = VaultRecoveryKeyHelper.normalizeKey(newKey)
                    val wrapped = VaultKeyManager.wrapVaultMasterKey(vmk, clean.toCharArray())
                    val encDisplay = VaultRecoveryKeyHelper.encryptRecoveryKeyForDisplay(newKey, vmk)
                    container.credentialStore.saveRecoveryCredentials(wrapped, encDisplay)
                    key = newKey
                }
                _uiState.update {
                    it.copy(
                        activeRecoveryKey = key,
                        showRecoveryKeyDialog = true,
                        hasRecoveryKey = true,
                    )
                }
            }
        }
    }

    fun dismissRecoveryKeyDialog() {
        _uiState.update { it.copy(showRecoveryKeyDialog = false) }
    }

    fun openForgotPinDialog() {
        _uiState.update {
            it.copy(
                showForgotPinDialog = true,
                isRecoveryVerified = false,
                recoveryErrorMessage = null,
            )
        }
    }

    fun dismissForgotPinDialog() {
        verifiedRecoveryVmk?.let { VaultKeyManager.zeroize(it) }
        verifiedRecoveryVmk = null
        _uiState.update {
            it.copy(
                showForgotPinDialog = false,
                isRecoveryVerified = false,
                recoveryErrorMessage = null,
            )
        }
    }

    fun verifyRecoveryKey(enteredKey: String, onResult: (Boolean, String?) -> Unit) {
        val clean = VaultRecoveryKeyHelper.normalizeKey(enteredKey)
        if (!VaultRecoveryKeyHelper.isValidFormat(enteredKey)) {
            _uiState.update { it.copy(recoveryErrorMessage = "Invalid key format. Expected 32 hex characters (e.g. VSREC-XXXX-...).") }
            onResult(false, "Invalid recovery key format")
            return
        }

        val recoveryWrapped = container.credentialStore.getRecoveryWrappedVmk()
        if (recoveryWrapped == null) {
            _uiState.update { it.copy(recoveryErrorMessage = "No recovery key found on this device.") }
            onResult(false, "No recovery key found")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val vmk = VaultKeyManager.unwrapVaultMasterKey(recoveryWrapped, clean.toCharArray())
                verifiedRecoveryVmk = vmk
                _uiState.update {
                    it.copy(
                        isRecoveryVerified = true,
                        recoveryErrorMessage = null,
                    )
                }
                withContext(Dispatchers.Main) {
                    onResult(true, null)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        recoveryErrorMessage = "Incorrect recovery key. Could not unlock vault credentials.",
                    )
                }
                withContext(Dispatchers.Main) {
                    onResult(false, "Incorrect recovery key")
                }
            }
        }
    }

    fun resetPinWithRecoveryKey(
        newPin: String,
        enableBiometric: Boolean,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val cleanPin = newPin.trim()
        if (cleanPin.length < 4) {
            _uiState.update { it.copy(recoveryErrorMessage = "New PIN must be at least 4 digits.") }
            onResult(false, "PIN must be at least 4 digits")
            return
        }

        val vmk = verifiedRecoveryVmk
        if (vmk == null) {
            _uiState.update { it.copy(recoveryErrorMessage = "Recovery session expired. Please verify recovery key again.") }
            onResult(false, "Verification expired")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // 1. Wrap VMK with new PIN and persist
                val newWrapped = VaultKeyManager.wrapVaultMasterKey(vmk, cleanPin.toCharArray())
                container.credentialStore.saveCredentials(newWrapped)

                // 2. Handle Biometrics
                if (enableBiometric) {
                    val success = container.credentialStore.enableBiometric(vmk)
                    _uiState.update { it.copy(isBiometricEnabled = success, canUseBiometric = success) }
                } else {
                    container.credentialStore.disableBiometric()
                    _uiState.update { it.copy(isBiometricEnabled = false, canUseBiometric = false) }
                }

                // 3. Unlock session with recovered VMK
                session.unlock(vmk)

                // 4. Zeroize verified VMK
                VaultKeyManager.zeroize(vmk)
                verifiedRecoveryVmk = null

                _uiState.update {
                    it.copy(
                        isUnlocked = true,
                        showForgotPinDialog = false,
                        isRecoveryVerified = false,
                        recoveryErrorMessage = null,
                        infoMessage = "PIN reset successfully! Vault unlocked.",
                    )
                }

                withContext(Dispatchers.Main) {
                    onResult(true, "PIN reset successfully!")
                }
                syncManifestIfSharedStorage()
            } catch (e: Exception) {
                _uiState.update { it.copy(recoveryErrorMessage = "PIN reset failed: ${e.message}") }
                withContext(Dispatchers.Main) {
                    onResult(false, "Reset failed: ${e.message}")
                }
            }
        }
    }

    fun checkForExistingVault(explicitDir: File? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val discovery = com.vaultsync.core.recovery.VaultRecoveryManager.detectExistingVault(
                context = getApplication(),
                explicitDir = explicitDir,
            )
            withContext(Dispatchers.Main) {
                when (discovery) {
                    is com.vaultsync.core.recovery.VaultDiscoveryResult.Detected -> {
                        _uiState.update {
                            it.copy(
                                detectedVault = discovery,
                                infoMessage = "Existing vault detected with ${discovery.blobCount} files!",
                                errorMessage = null,
                            )
                        }
                    }
                    is com.vaultsync.core.recovery.VaultDiscoveryResult.PermissionRequired -> {
                        _uiState.update {
                            it.copy(errorMessage = "Storage access needed to read Documents/VaultSync. Please tap scan to authorize.")
                        }
                    }
                    is com.vaultsync.core.recovery.VaultDiscoveryResult.Corrupted -> {
                        _uiState.update { it.copy(errorMessage = discovery.errorMessage) }
                    }
                    is com.vaultsync.core.recovery.VaultDiscoveryResult.NotFound -> {
                        _uiState.update { it.copy(infoMessage = "No vault found in Documents/VaultSync.") }
                    }
                }
            }
        }
    }

    fun dismissDetectedVault() {
        _uiState.update { it.copy(detectedVault = null) }
    }

    fun recoverDetectedVault(
        credentialInput: String,
        isRecoveryKey: Boolean,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val detected = _uiState.value.detectedVault ?: run {
            onResult(false, "No detected vault to recover")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = com.vaultsync.core.recovery.VaultRecoveryManager.recoverVault(
                context = getApplication(),
                database = container.database,
                credentialStore = container.credentialStore,
                session = session,
                manifest = detected.manifest,
                vaultDir = detected.vaultDir,
                credentialInput = credentialInput,
                isRecoveryKey = isRecoveryKey,
            )

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    container.storageManager.initDirectories()
                    _thumbnailCache.update { emptyMap() }
                    _uiState.update {
                        it.copy(
                            isInitialized = true,
                            isUnlocked = true,
                            detectedVault = null,
                            showRestoreDialog = false,
                            isSharedStorageActive = true,
                            infoMessage = "Vault recovered successfully! Restored ${result.restoredFilesCount} files.",
                            errorMessage = null,
                        )
                    }
                    onResult(true, null)
                } else {
                    _uiState.update { it.copy(errorMessage = result.errorMessage) }
                    onResult(false, result.errorMessage)
                }
            }
        }
    }

    fun openRestoreDialog() {
        _uiState.update { it.copy(showRestoreDialog = true) }
    }

    fun closeRestoreDialog() {
        _uiState.update { it.copy(showRestoreDialog = false) }
    }

    fun openMigrationDialog() {
        _uiState.update { it.copy(showMigrationDialog = true) }
    }

    fun closeMigrationDialog() {
        _uiState.update { it.copy(showMigrationDialog = false) }
    }

    fun startMigrationToSharedStorage(
        destinationDir: File,
        onResult: (Boolean, String?) -> Unit,
    ) {
        if (!session.isUnlocked.value) {
            onResult(false, "Vault must be unlocked to migrate")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isMigrationInProgress = true) }
            val engine = com.vaultsync.core.migration.VaultMigrationEngine(
                context = getApplication(),
                database = container.database,
                credentialStore = container.credentialStore,
                session = session,
                sourceStorageManager = container.storageManager,
            )

            try {
                session.withVmkSuspend { vmk ->
                    val result = engine.executeMigration(
                        destinationRoot = destinationDir,
                        vmk = vmk,
                        onProgress = { progress ->
                            _uiState.update { it.copy(migrationProgress = progress) }
                        },
                    )

                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isMigrationInProgress = false,
                                isSharedStorageActive = result.isSuccess,
                                showMigrationDialog = false,
                                infoMessage = if (result.isSuccess) "Migration complete! Vault is now stored in ${destinationDir.name}." else null,
                                errorMessage = if (!result.isSuccess) result.errorMessage else null,
                            )
                        }
                        onResult(result.isSuccess, result.errorMessage)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isMigrationInProgress = false,
                            errorMessage = "Migration failed: ${e.message}",
                        )
                    }
                    onResult(false, e.message)
                }
            }
        }
    }

    fun syncManifestIfSharedStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            val storagePrefs = com.vaultsync.core.storage.VaultStoragePreferences(getApplication())
            if (storagePrefs.isSharedStorageEnabled && session.isUnlocked.value) {
                try {
                    session.withVmkSuspend { vmk ->
                        val manifest = com.vaultsync.core.manifest.VaultManifestManager.createManifestFromCurrentVault(
                            database = container.database,
                            credentialStore = container.credentialStore,
                            vmk = vmk,
                        )
                        com.vaultsync.core.manifest.VaultManifestManager.writeManifest(
                            container.storageManager.manifestFile,
                            manifest,
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w("VaultViewModel", "Failed to sync manifest: ${e.message}")
                }
            }
        }
    }
}
