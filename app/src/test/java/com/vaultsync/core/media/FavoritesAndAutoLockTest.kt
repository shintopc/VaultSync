package com.vaultsync.core.media

import com.vaultsync.core.stealth.AutoLockTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesAndAutoLockTest {

    @Test
    fun autoLockTimeoutResolvesCorrectly() {
        assertEquals(AutoLockTimeout.IMMEDIATE, AutoLockTimeout.fromSeconds(0L))
        assertEquals(AutoLockTimeout.SECONDS_30, AutoLockTimeout.fromSeconds(30L))
        assertEquals(AutoLockTimeout.MINUTES_1, AutoLockTimeout.fromSeconds(60L))
        assertEquals(AutoLockTimeout.MINUTES_5, AutoLockTimeout.fromSeconds(300L))
        assertEquals(AutoLockTimeout.NEVER, AutoLockTimeout.fromSeconds(-1L))
        assertEquals(AutoLockTimeout.IMMEDIATE, AutoLockTimeout.fromSeconds(9999L))
    }

    @Test
    fun favoritesFilteringMatchesFavoritedIds() {
        val item1 = DeviceMediaItem(
            id = "photo_1",
            uri = null,
            displayName = "photo_1.jpg",
            category = MediaCategory.Images,
            mimeType = "image/jpeg",
            folderName = "Camera",
            folderKey = "Camera",
            sizeBytes = 1024L,
            dateModifiedMillis = 1000L,
            source = MediaSource.MediaStore,
        )
        val item2 = DeviceMediaItem(
            id = "photo_2",
            uri = null,
            displayName = "photo_2.jpg",
            category = MediaCategory.Images,
            mimeType = "image/jpeg",
            folderName = "Camera",
            folderKey = "Camera",
            sizeBytes = 2048L,
            dateModifiedMillis = 2000L,
            source = MediaSource.MediaStore,
        )
        val items = listOf(item1, item2)
        val favoriteIds = setOf("photo_2")

        val filtered = items.filter { it.id in favoriteIds }
        assertEquals(1, filtered.size)
        assertEquals("photo_2.jpg", filtered.first().displayName)
    }

    @Test
    fun exifDetailsStoresMetadataAccurately() {
        val details = ExifDetails(
            cameraMake = "Google",
            cameraModel = "Pixel 8 Pro",
            aperture = "f/1.68",
            focalLength = "6.9mm",
            exposureTime = "1/120s",
            iso = "ISO 50",
            width = 4080,
            height = 3072,
            dateTaken = "2026:10:28 07:00:00",
            latitude = 37.7749,
            longitude = -122.4194,
            hasLocation = true,
        )

        assertEquals("Google", details.cameraMake)
        assertEquals("Pixel 8 Pro", details.cameraModel)
        assertTrue(details.hasLocation)
        assertEquals(4080, details.width)
        assertEquals(3072, details.height)
    }
}
