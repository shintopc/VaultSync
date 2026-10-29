package com.vaultsync.core.media

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPermissionTest {

    @Test
    fun requiredMediaPermissionsReturnsGranularForTiramisuAndAbove() {
        val permissions = requiredMediaPermissions(Build.VERSION_CODES.TIRAMISU)
        assertEquals(3, permissions.size)
        assertTrue(permissions.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(permissions.contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertTrue(permissions.contains(Manifest.permission.READ_MEDIA_AUDIO))
    }

    @Test
    fun requiredMediaPermissionsReturnsStorageForLegacy() {
        val permissions = requiredMediaPermissions(Build.VERSION_CODES.S_V2)
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), permissions)
    }

    @Test
    fun mediaPermissionStatusFromGrantResults() {
        val required = listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )

        val allGranted = mapOf(
            Manifest.permission.READ_MEDIA_IMAGES to true,
            Manifest.permission.READ_MEDIA_VIDEO to true,
        )
        assertEquals(
            MediaPermissionStatus.Granted,
            mediaPermissionStatusFromGrantResults(required, allGranted, canAskAgain = true),
        )

        val partiallyDenied = mapOf(
            Manifest.permission.READ_MEDIA_IMAGES to true,
            Manifest.permission.READ_MEDIA_VIDEO to false,
        )
        assertEquals(
            MediaPermissionStatus.Denied(canAskAgain = true),
            mediaPermissionStatusFromGrantResults(required, partiallyDenied, canAskAgain = true),
        )
    }
}
