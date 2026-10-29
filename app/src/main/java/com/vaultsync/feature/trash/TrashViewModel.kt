package com.vaultsync.feature.trash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vaultsync.core.trash.TrashItem
import com.vaultsync.core.trash.TrashManager
import com.vaultsync.core.trash.TrashPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrashUiState(
    val items: List<TrashItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isOperationInProgress: Boolean = false,
    val operationMessage: String? = null,
    val totalCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val previewItem: TrashItem? = null,
    val isTrashEnabled: Boolean = true,
) {
    val hasSelection: Boolean = selectedIds.isNotEmpty()
    val isAllSelected: Boolean = items.isNotEmpty() && selectedIds.size == items.size

    val formattedTotalSize: String
        get() {
            if (totalSizeBytes <= 0) return "0 B"
            val kb = totalSizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
                else -> "$totalSizeBytes B"
            }
        }
}

class TrashViewModel(application: Application) : AndroidViewModel(application) {
    private val trashManager = TrashManager.get(application)
    private val trashPreferences = TrashPreferences(application)

    private val _uiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    init {
        loadTrash()
    }

    fun loadTrash() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val items = trashManager.getTrashItems()
            val stats = trashManager.getTrashStats()
            _uiState.update {
                it.copy(
                    items = items,
                    totalCount = stats.first,
                    totalSizeBytes = stats.second,
                    isLoading = false,
                    isTrashEnabled = trashPreferences.isTrashEnabled,
                )
            }
        }
    }

    fun toggleSelection(id: String) {
        _uiState.update { state ->
            val newSelection = if (id in state.selectedIds) {
                state.selectedIds - id
            } else {
                state.selectedIds + id
            }
            state.copy(selectedIds = newSelection)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            val allIds = state.items.map { it.id }.toSet()
            state.copy(selectedIds = allIds)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    fun setPreviewItem(item: TrashItem?) {
        _uiState.update { it.copy(previewItem = item) }
    }

    fun restoreSelected(onFinished: (Int) -> Unit) {
        val selected = _uiState.value.items.filter { it.id in _uiState.value.selectedIds }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isOperationInProgress = true, operationMessage = "Restoring ${selected.size} item(s)...")
            }
            val restoredCount = trashManager.restoreItems(selected)
            clearSelection()
            loadTrash()
            _uiState.update { it.copy(isOperationInProgress = false, operationMessage = null) }
            onFinished(restoredCount)
        }
    }

    fun restoreSingle(item: TrashItem, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isOperationInProgress = true, operationMessage = "Restoring file...")
            }
            val success = trashManager.restoreItem(item)
            if (item == _uiState.value.previewItem) {
                _uiState.update { it.copy(previewItem = null) }
            }
            loadTrash()
            _uiState.update { it.copy(isOperationInProgress = false, operationMessage = null) }
            onFinished(success)
        }
    }

    fun deletePermanentlySelected(onFinished: (Int) -> Unit) {
        val selected = _uiState.value.items.filter { it.id in _uiState.value.selectedIds }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isOperationInProgress = true, operationMessage = "Permanently deleting files...")
            }
            val count = trashManager.deletePermanently(selected)
            clearSelection()
            loadTrash()
            _uiState.update { it.copy(isOperationInProgress = false, operationMessage = null) }
            onFinished(count)
        }
    }

    fun deletePermanentlySingle(item: TrashItem, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isOperationInProgress = true, operationMessage = "Permanently deleting file...")
            }
            val success = trashManager.deletePermanently(item)
            if (item == _uiState.value.previewItem) {
                _uiState.update { it.copy(previewItem = null) }
            }
            loadTrash()
            _uiState.update { it.copy(isOperationInProgress = false, operationMessage = null) }
            onFinished(success)
        }
    }

    fun emptyTrash(onFinished: (Int) -> Unit) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isOperationInProgress = true, operationMessage = "Emptying trash...")
            }
            val count = trashManager.emptyTrash()
            clearSelection()
            loadTrash()
            _uiState.update { it.copy(isOperationInProgress = false, operationMessage = null) }
            onFinished(count)
        }
    }
}
