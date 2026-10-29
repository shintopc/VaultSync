package com.vaultsync.core.trash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashItemTest {

    @Test
    fun daysRemainingCalculationIsAccurate() {
        val now = System.currentTimeMillis()
        val fifteenDaysInMs = 15L * 24L * 60L * 60L * 1000L
        val autoDeleteAt = now + fifteenDaysInMs

        val item = TrashItem(
            id = "test-1",
            displayName = "photo.jpg",
            originalUriString = "content://media/external/images/media/1",
            originalPath = "Camera",
            mimeType = "image/jpeg",
            sizeBytes = 1024 * 1024 * 2L, // 2MB
            localFilePath = "/data/user/0/com.vaultsync.app/files/trash/test-1_photo.jpg",
            deletedAt = now,
            autoDeleteAt = autoDeleteAt,
        )

        assertTrue(item.isImage)
        assertFalse(item.isVideo)
        assertEquals(15, item.daysRemaining)
        assertEquals("15 days left", item.remainingStatusText)
        assertEquals("2.0 MB", item.formattedSize)
    }

    @Test
    fun daysRemainingExpiredHandling() {
        val now = System.currentTimeMillis()
        val expiredItem = TrashItem(
            id = "test-expired",
            displayName = "video.mp4",
            originalUriString = "content://media/external/video/media/2",
            originalPath = "Movies",
            mimeType = "video/mp4",
            sizeBytes = 1024 * 1024 * 50L,
            localFilePath = "/data/user/0/com.vaultsync.app/files/trash/test_video.mp4",
            deletedAt = now - (16L * 24L * 60L * 60L * 1000L),
            autoDeleteAt = now - (1L * 24L * 60L * 60L * 1000L),
        )

        assertTrue(expiredItem.isVideo)
        assertFalse(expiredItem.isImage)
        assertEquals(0, expiredItem.daysRemaining)
        assertEquals("Expires today", expiredItem.remainingStatusText)
    }
}
