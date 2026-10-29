package com.vaultsync.core.storage

import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.model.VaultFolder
import com.vaultsync.feature.vault.VaultAlbumDisplay
import com.vaultsync.feature.vault.VaultViewTab
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultThumbnailManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageManager: VaultStorageManager
    private lateinit var thumbnailManager: VaultThumbnailManager
    private lateinit var thumbDir: File

    @Before
    fun setUp() {
        val cacheDir = tempFolder.newFolder("cache")
        val vaultDir = tempFolder.newFolder("vault")
        thumbDir = File(cacheDir, "vault_thumbnails")

        storageManager = VaultStorageManager(vaultDir)
        storageManager.initDirectories()

        thumbnailManager = VaultThumbnailManager(thumbDir, storageManager)
    }

    @Test
    fun thumbnailDir_isCreatedWithNoMedia() {
        assertTrue(thumbDir.exists())
        assertTrue(File(thumbDir, ".nomedia").exists())
    }

    @Test
    fun nonMediaEntries_returnNullThumbnail() = runBlocking {
        val docEntry = VaultEntry(
            id = "doc_1",
            folderId = null,
            originalFileName = "secret.pdf",
            category = VaultCategory.Documents,
            mimeType = "application/pdf",
            originalSizeBytes = 1024L,
            encryptedSizeBytes = 1056L,
            createdAtMillis = System.currentTimeMillis(),
            dateModifiedMillis = System.currentTimeMillis(),
        )

        val result = thumbnailManager.getOrCreateThumbnail(docEntry, ByteArray(32))
        assertNull(result)
    }

    @Test
    fun clearAllThumbnails_removesAllCachedFilesExceptNoMedia() = runBlocking {
        val sampleThumb = File(thumbDir, "thumb_test.jpg")
        sampleThumb.writeText("fake-image-bytes")
        assertTrue(sampleThumb.exists())

        thumbnailManager.clearAllThumbnails()

        assertFalse(sampleThumb.exists())
        assertTrue(File(thumbDir, ".nomedia").exists())
    }

    @Test
    fun deleteThumbnail_removesSpecificThumbnail() = runBlocking {
        val thumb1 = File(thumbDir, "thumb_entry_1.jpg")
        val thumb2 = File(thumbDir, "thumb_entry_2.jpg")
        thumb1.writeText("img1")
        thumb2.writeText("img2")

        thumbnailManager.deleteThumbnail("entry_1")

        assertFalse(thumb1.exists())
        assertTrue(thumb2.exists())
    }

    @Test
    fun vaultViewTab_andAlbumDisplay_behaveCorrectly() {
        assertEquals(3, VaultViewTab.entries.size)
        assertTrue(VaultViewTab.entries.contains(VaultViewTab.PHOTOS))
        assertTrue(VaultViewTab.entries.contains(VaultViewTab.ALBUMS))
        assertTrue(VaultViewTab.entries.contains(VaultViewTab.AUDIO))

        val folder = VaultFolder(
            id = "folder_1",
            name = "Camera",
            parentId = null,
            createdAtMillis = 1000L,
            updatedAtMillis = 2000L,
            itemCount = 5,
        )
        val album = VaultAlbumDisplay(
            folder = folder,
            previewEntries = emptyList(),
            totalCount = 5,
        )
        assertEquals("Camera", album.folder.name)
        assertEquals(5, album.totalCount)
    }
}
