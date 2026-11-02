package com.vaultsync.core.backup

import com.vaultsync.core.storage.VaultStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class VaultBackupArchiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sourceStorage: VaultStorageManager
    private lateinit var targetStorage: VaultStorageManager

    @Before
    fun setUp() {
        val srcDir = tempFolder.newFolder("source_vault")
        val tgtDir = tempFolder.newFolder("target_vault")
        sourceStorage = VaultStorageManager(srcDir).apply { initDirectories() }
        targetStorage = VaultStorageManager(tgtDir).apply { initDirectories() }
    }

    @Test
    fun roundtripBackupAndRestoreReconstructsManifestAndBlobs(): Unit = runBlocking {
        // Create mock manifest and blobs in sourceStorage
        val manifestContent = "VAULT_MANIFEST_V1_MOCK_DATA".toByteArray()
        sourceStorage.manifestFile.writeBytes(manifestContent)

        val blob1Content = "ENCRYPTED_BLOB_PAYLOAD_ALPHA".toByteArray()
        val blob2Content = "ENCRYPTED_BLOB_PAYLOAD_BETA".toByteArray()

        File(sourceStorage.blobsDir, "item1.vsf").writeBytes(blob1Content)
        File(sourceStorage.blobsDir, "item2.vsf").writeBytes(blob2Content)

        val backupOut = ByteArrayOutputStream()

        // Create backup
        val metadata = VaultBackupManager.createBackupToStream(
            outputStream = backupOut,
            storageManager = sourceStorage,
        )

        assertEquals(2, metadata.blobCount)
        assertEquals(manifestContent.size + blob1Content.size + blob2Content.size.toLong(), metadata.totalSizeBytes)
        assertTrue(backupOut.size() > 0)

        // Restore into empty targetStorage
        val backupIn = ByteArrayInputStream(backupOut.toByteArray())
        val restoredBlobs = VaultBackupManager.restoreBackupFromStream(
            inputStream = backupIn,
            storageManager = targetStorage,
        )

        assertEquals(2, restoredBlobs)
        assertTrue(targetStorage.manifestFile.exists())
        assertArrayEquals(manifestContent, targetStorage.manifestFile.readBytes())

        val restored1 = File(targetStorage.blobsDir, "item1.vsf")
        val restored2 = File(targetStorage.blobsDir, "item2.vsf")
        assertTrue(restored1.exists())
        assertTrue(restored2.exists())
        assertArrayEquals(blob1Content, restored1.readBytes())
        assertArrayEquals(blob2Content, restored2.readBytes())
    }
}

