package com.vaultsync.core.floating

import android.net.Uri
import java.io.File

enum class FloatingMediaSource {
    GALLERY,
    VAULT
}

/**
 * Represents an individual media window floating above the application UI.
 */
data class FloatingMediaItem(
    val id: String,
    val title: String,
    val isVideo: Boolean,
    val source: FloatingMediaSource,
    val uri: Uri? = null,
    val file: File? = null,
    val vaultEntryId: String? = null,
    val x: Float = 80f,
    val y: Float = 200f,
    val width: Float = 650f,
    val height: Float = 650f,
    val isMinimized: Boolean = false,
    val isMuted: Boolean = true,
    val zIndex: Float = 0f,
    val rotationDegrees: Float = 0f,
)
