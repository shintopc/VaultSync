package com.vaultsync.core.media

import android.Manifest
import android.os.Build

sealed interface MediaPermissionStatus {
    data object Unknown : MediaPermissionStatus
    data object Granted : MediaPermissionStatus
    data class Denied(val canAskAgain: Boolean) : MediaPermissionStatus
}

fun requiredMediaPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

fun mediaPermissionStatusFromGrantResults(
    requiredPermissions: List<String>,
    grantResults: Map<String, Boolean>,
    canAskAgain: Boolean,
): MediaPermissionStatus {
    if (requiredPermissions.all { grantResults[it] == true }) {
        return MediaPermissionStatus.Granted
    }
    return MediaPermissionStatus.Denied(canAskAgain = canAskAgain)
}
