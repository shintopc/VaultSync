package com.vaultsync.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultStorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun directoriesAndNoMediaCreatedProperly() {
        val root = tempFolder.newFolder("vault_root")
        val storage = VaultStorageManager(root)
        storage.initDirectories()

        assertTrue(storage.vaultRoot.exists())
        assertTrue(storage.blobsDir.exists())
        assertTrue(storage.tempDir.exists())

        // .nomedia must be present to prevent public media scanning
        assertTrue(File(storage.vaultRoot, ".nomedia").exists())
        assertTrue(File(storage.blobsDir, ".nomedia").exists())
    }

    @Test
    fun generatesOpaqueBlobPaths() {
        val root = tempFolder.newFolder("vault_opaque")
        val storage = VaultStorageManager(root)

        val id = "3a4f82c1-8419-4b67-9204-712349182312"
        val blob = storage.getEncryptedBlobFile(id)
        val temp = storage.getTempBlobFile(id)

        assertEquals("$id.vsf", blob.name)
        assertEquals("$id.part", temp.name)
        assertEquals(storage.blobsDir.absolutePath, blob.parentFile?.absolutePath)
        assertEquals(storage.tempDir.absolutePath, temp.parentFile?.absolutePath)
    }

    @Test
    fun cleansStaleTempFiles() {
        val root = tempFolder.newFolder("vault_stale")
        val storage = VaultStorageManager(root)
        storage.initDirectories()

        // Create incomplete part files
        val stale1 = File(storage.tempDir, "stale1.part").apply { writeText("interrupted") }
        val stale2 = File(storage.blobsDir, "stale2.part").apply { writeText("interrupted2") }
        val realBlob = File(storage.blobsDir, "real.vsf").apply { writeText("real data") }

        assertTrue(stale1.exists())
        assertTrue(stale2.exists())
        assertTrue(realBlob.exists())

        storage.cleanStaleTempFiles()

        assertFalse(stale1.exists())
        assertFalse(stale2.exists())
        assertTrue(realBlob.exists()) // Real committed blob must not be touched
    }
}
