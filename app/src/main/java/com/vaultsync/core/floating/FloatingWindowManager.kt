package com.vaultsync.core.floating

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.vaultsync.core.media.DeviceMediaItem
import com.vaultsync.core.media.MediaCategory
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Manages active floating windows displaying images and videos from Gallery and Vault.
 * Supports simultaneous multi-window placement, cascading, resizing, moving, and safe cleanup.
 */
class FloatingWindowManager private constructor(private val appContext: Context? = null) {

    private val _windows = MutableStateFlow<List<FloatingMediaItem>>(emptyList())
    val windows: StateFlow<List<FloatingMediaItem>> = _windows.asStateFlow()

    private var nextZIndex = 1f
    private var cascadeIndex = 0

    /**
     * Opens a single gallery media item in a floating window.
     */
    fun openGalleryMedia(item: DeviceMediaItem) {
        openGalleryMediaMultiple(listOf(item))
    }

    /**
     * Opens multiple gallery media items simultaneously in floating windows.
     */
    fun openGalleryMediaMultiple(items: List<DeviceMediaItem>) {
        if (items.isEmpty()) return
        val current = _windows.value.toMutableList()

        items.forEach { item ->
            val id = "gallery_${item.id}"
            val existingIndex = current.indexOfFirst { it.id == id }
            if (existingIndex >= 0) {
                current[existingIndex] = current[existingIndex].copy(
                    zIndex = nextZIndex++,
                    isMinimized = false,
                )
            } else {
                val (x, y) = calculateCascadePosition()
                val isVideo = item.category == MediaCategory.Videos
                current.add(
                    FloatingMediaItem(
                        id = id,
                        title = item.displayName,
                        isVideo = isVideo,
                        source = FloatingMediaSource.GALLERY,
                        uri = item.uri,
                        x = x,
                        y = y,
                        zIndex = nextZIndex++,
                    )
                )
            }
        }
        _windows.value = current
    }

    /**
     * Opens a single decrypted vault entry in a floating window.
     */
    fun openVaultMedia(entry: VaultEntry, decryptedFile: File) {
        openVaultMediaMultiple(listOf(entry to decryptedFile))
    }

    /**
     * Opens multiple decrypted vault entries simultaneously in floating windows.
     */
    fun openVaultMediaMultiple(entries: List<Pair<VaultEntry, File>>) {
        if (entries.isEmpty()) return
        val current = _windows.value.toMutableList()

        entries.forEach { (entry, file) ->
            val id = "vault_${entry.id}"
            val existingIndex = current.indexOfFirst { it.id == id }
            if (existingIndex >= 0) {
                current[existingIndex] = current[existingIndex].copy(
                    zIndex = nextZIndex++,
                    isMinimized = false,
                )
            } else {
                val (x, y) = calculateCascadePosition()
                val isVideo = entry.category == VaultCategory.Videos
                current.add(
                    FloatingMediaItem(
                        id = id,
                        title = entry.originalFileName,
                        isVideo = isVideo,
                        source = FloatingMediaSource.VAULT,
                        file = file,
                        vaultEntryId = entry.id,
                        x = x,
                        y = y,
                        zIndex = nextZIndex++,
                    )
                )
            }
        }
        _windows.value = current
    }

    /**
     * Closes a specific floating window by ID and cleans up its decrypted file if from vault.
     */
    fun closeWindow(id: String) {
        val target = _windows.value.firstOrNull { it.id == id }
        if (target != null && target.source == FloatingMediaSource.VAULT && target.file != null) {
            try {
                target.file.delete()
            } catch (_: Exception) {}
        }
        _windows.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * Closes all active floating windows.
     */
    fun closeAll() {
        _windows.value.forEach { item ->
            if (item.source == FloatingMediaSource.VAULT && item.file != null) {
                try {
                    item.file.delete()
                } catch (_: Exception) {}
            }
        }
        _windows.value = emptyList()
    }

    /**
     * Closes all vault floating windows and securely wipes their temporary decrypted files.
     * Invoked immediately when the vault locks.
     */
    fun closeVaultWindows() {
        val current = _windows.value
        val vaultWindows = current.filter { it.source == FloatingMediaSource.VAULT }
        vaultWindows.forEach { item ->
            if (item.file != null) {
                try {
                    item.file.delete()
                } catch (_: Exception) {}
            }
        }
        _windows.update { list -> list.filterNot { it.source == FloatingMediaSource.VAULT } }
    }

    /**
     * Brings the window with given ID to the front of the Z-stack.
     */
    fun bringToFront(id: String) {
        _windows.update { list ->
            list.map { if (it.id == id) it.copy(zIndex = nextZIndex++) else it }
        }
    }

    /**
     * Updates window position by pixel delta.
     */
    fun updatePosition(id: String, deltaX: Float, deltaY: Float) {
        _windows.update { list ->
            list.map { item ->
                if (item.id == id) {
                    item.copy(
                        x = (item.x + deltaX).coerceAtLeast(0f),
                        y = (item.y + deltaY).coerceAtLeast(0f),
                    )
                } else item
            }
        }
    }

    /**
     * Updates window dimensions by pixel delta, with minimum and maximum bounds.
     */
    fun updateSize(id: String, deltaWidth: Float, deltaHeight: Float) {
        _windows.update { list ->
            list.map { item ->
                if (item.id == id) {
                    val newW = (item.width + deltaWidth).coerceIn(380f, 1200f)
                    val newH = (item.height + deltaHeight).coerceIn(380f, 1400f)
                    item.copy(width = newW, height = newH)
                } else item
            }
        }
    }

    /**
     * Toggles minimized state for a floating window.
     */
    fun toggleMinimize(id: String) {
        _windows.update { list ->
            list.map { item ->
                if (item.id == id) item.copy(isMinimized = !item.isMinimized) else item
            }
        }
    }

    /**
     * Toggles mute state for a floating video window.
     */
    fun toggleMute(id: String) {
        _windows.update { list ->
            list.map { item ->
                if (item.id == id) item.copy(isMuted = !item.isMuted) else item
            }
        }
    }

    /**
     * Rotates media clockwise by 90 degrees (0 -> 90 -> 180 -> 270 -> 0).
     */
    fun rotateMedia(id: String) {
        _windows.update { list ->
            list.map { item ->
                if (item.id == id) {
                    val nextRotation = (item.rotationDegrees + 90f) % 360f
                    item.copy(rotationDegrees = nextRotation)
                } else item
            }
        }
    }

    private fun calculateCascadePosition(): Pair<Float, Float> {
        val step = cascadeIndex % 5
        cascadeIndex++
        val x = 40f + (step * 50f)
        val y = 160f + (step * 70f)
        return Pair(x, y)
    }

    companion object {
        @Volatile
        private var INSTANCE: FloatingWindowManager? = null

        fun get(context: Context): FloatingWindowManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FloatingWindowManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun createForTesting(): FloatingWindowManager = FloatingWindowManager(null)
    }
}

val LocalFloatingWindowManager = staticCompositionLocalOf<FloatingWindowManager> {
    FloatingWindowManager.createForTesting()
}
