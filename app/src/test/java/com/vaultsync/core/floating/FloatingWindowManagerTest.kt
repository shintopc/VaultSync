package com.vaultsync.core.floating

import com.vaultsync.core.media.DeviceMediaItem
import com.vaultsync.core.media.MediaCategory
import com.vaultsync.core.media.MediaSource
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FloatingWindowManagerTest {

    private lateinit var manager: FloatingWindowManager

    @Before
    fun setUp() {
        manager = FloatingWindowManager.createForTesting()
    }

    private fun createDeviceMediaItem(
        id: String,
        displayName: String,
        category: MediaCategory = MediaCategory.Images,
        sizeBytes: Long = 100L,
    ): DeviceMediaItem {
        return DeviceMediaItem(
            id = id,
            uri = null,
            displayName = displayName,
            category = category,
            mimeType = if (category == MediaCategory.Videos) "video/mp4" else "image/jpeg",
            folderName = "Camera",
            folderKey = "camera",
            sizeBytes = sizeBytes,
            dateModifiedMillis = System.currentTimeMillis(),
            source = MediaSource.MediaStore,
        )
    }

    private fun createVaultEntry(
        id: String,
        fileName: String,
        category: VaultCategory = VaultCategory.Images,
        sizeBytes: Long = 100L,
    ): VaultEntry {
        return VaultEntry(
            id = id,
            folderId = null,
            originalFileName = fileName,
            category = category,
            mimeType = if (category == VaultCategory.Videos) "video/mp4" else "image/jpeg",
            originalSizeBytes = sizeBytes,
            encryptedSizeBytes = sizeBytes + 28,
            createdAtMillis = System.currentTimeMillis(),
            dateModifiedMillis = System.currentTimeMillis(),
        )
    }

    @Test
    fun testOpenGalleryMediaSingle() {
        val item = createDeviceMediaItem(id = "101", displayName = "vacation.jpg")

        manager.openGalleryMedia(item)

        val windows = manager.windows.value
        assertEquals(1, windows.size)
        assertEquals("gallery_101", windows[0].id)
        assertEquals("vacation.jpg", windows[0].title)
        assertFalse(windows[0].isVideo)
        assertEquals(FloatingMediaSource.GALLERY, windows[0].source)
    }

    @Test
    fun testOpenMultipleSimultaneouslyWithCascading() {
        val item1 = createDeviceMediaItem(id = "1", displayName = "img1.png", category = MediaCategory.Images)
        val item2 = createDeviceMediaItem(id = "2", displayName = "video.mp4", category = MediaCategory.Videos)
        val item3 = createDeviceMediaItem(id = "3", displayName = "img2.jpg", category = MediaCategory.Images)

        manager.openGalleryMediaMultiple(listOf(item1, item2, item3))

        val windows = manager.windows.value
        assertEquals(3, windows.size)

        // Verify video category
        assertFalse(windows[0].isVideo)
        assertTrue(windows[1].isVideo)
        assertFalse(windows[2].isVideo)

        // Verify cascading positions
        assertTrue(windows[1].x > windows[0].x)
        assertTrue(windows[1].y > windows[0].y)
        assertTrue(windows[2].x > windows[1].x)
        assertTrue(windows[2].y > windows[1].y)

        // Verify increasing zIndex
        assertTrue(windows[1].zIndex > windows[0].zIndex)
        assertTrue(windows[2].zIndex > windows[1].zIndex)
    }

    @Test
    fun testOpenExistingBringsToFrontAndRestores() {
        val item = createDeviceMediaItem(id = "200", displayName = "test.png")
        manager.openGalleryMedia(item)
        val initialZ = manager.windows.value[0].zIndex

        manager.toggleMinimize("gallery_200")
        assertTrue(manager.windows.value[0].isMinimized)

        manager.openGalleryMedia(item)
        val updated = manager.windows.value[0]
        assertFalse(updated.isMinimized)
        assertTrue(updated.zIndex > initialZ)
    }

    @Test
    fun testVaultMediaCleanupOnClose() {
        val tempFile = File.createTempFile("vault_test_", ".tmp")
        tempFile.writeText("sensitive vault decrypted data")
        assertTrue(tempFile.exists())

        val entry = createVaultEntry(id = "vault_entry_1", fileName = "secret.jpg")

        manager.openVaultMedia(entry, tempFile)
        assertEquals(1, manager.windows.value.size)
        assertEquals(FloatingMediaSource.VAULT, manager.windows.value[0].source)

        manager.closeWindow("vault_vault_entry_1")
        assertEquals(0, manager.windows.value.size)
        // File must be wiped and deleted
        assertFalse(tempFile.exists())
    }

    @Test
    fun testCloseVaultWindowsRetainsGalleryWindows() {
        val tempFile = File.createTempFile("vault_test_2_", ".tmp")
        tempFile.writeText("vault data")

        val galleryItem = createDeviceMediaItem(id = "g1", displayName = "photo.jpg")
        val vaultEntry = createVaultEntry(id = "v1", fileName = "secret.mp4", category = VaultCategory.Videos)

        manager.openGalleryMedia(galleryItem)
        manager.openVaultMedia(vaultEntry, tempFile)

        assertEquals(2, manager.windows.value.size)

        // Lock vault -> closeVaultWindows()
        manager.closeVaultWindows()

        assertEquals(1, manager.windows.value.size)
        assertEquals("gallery_g1", manager.windows.value[0].id)
        assertFalse(tempFile.exists())
    }

    @Test
    fun testPositionAndSizeUpdates() {
        val item = createDeviceMediaItem(id = "pos", displayName = "img.jpg")
        manager.openGalleryMedia(item)

        val initialX = manager.windows.value[0].x
        val initialY = manager.windows.value[0].y
        val initialW = manager.windows.value[0].width
        val initialH = manager.windows.value[0].height

        manager.updatePosition("gallery_pos", 50f, -20f)
        val moved = manager.windows.value[0]
        assertEquals(initialX + 50f, moved.x, 0.01f)
        assertEquals(initialY - 20f, moved.y, 0.01f)

        manager.updateSize("gallery_pos", 100f, 150f)
        val resized = manager.windows.value[0]
        assertEquals(initialW + 100f, resized.width, 0.01f)
        assertEquals(initialH + 150f, resized.height, 0.01f)
    }

    @Test
    fun testToggleMinimizeAndMute() {
        val item = createDeviceMediaItem(id = "vid", displayName = "clip.mp4", category = MediaCategory.Videos)
        manager.openGalleryMedia(item)

        assertTrue(manager.windows.value[0].isMuted) // default muted
        manager.toggleMute("gallery_vid")
        assertFalse(manager.windows.value[0].isMuted)

        assertFalse(manager.windows.value[0].isMinimized)
        manager.toggleMinimize("gallery_vid")
        assertTrue(manager.windows.value[0].isMinimized)
    }

    @Test
    fun testRotateMedia() {
        val item = createDeviceMediaItem(id = "rot", displayName = "photo.jpg")
        manager.openGalleryMedia(item)

        assertEquals(0f, manager.windows.value[0].rotationDegrees, 0.01f)
        manager.rotateMedia("gallery_rot")
        assertEquals(90f, manager.windows.value[0].rotationDegrees, 0.01f)
        manager.rotateMedia("gallery_rot")
        assertEquals(180f, manager.windows.value[0].rotationDegrees, 0.01f)
        manager.rotateMedia("gallery_rot")
        assertEquals(270f, manager.windows.value[0].rotationDegrees, 0.01f)
        manager.rotateMedia("gallery_rot")
        assertEquals(0f, manager.windows.value[0].rotationDegrees, 0.01f)
    }
}
