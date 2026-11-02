package com.vaultsync.core.storage

import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.vault.AndroidVaultRepository
import com.vaultsync.core.vault.FakeVaultEntryDao
import com.vaultsync.core.vault.FakeVaultFolderDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultStorageOptimizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageManager: VaultStorageManager
    private lateinit var fakeEntryDao: FakeVaultEntryDao
    private lateinit var fakeFolderDao: FakeVaultFolderDao
    private lateinit var repository: AndroidVaultRepository
    private val vmk = ByteArray(32) { 0x42 }

    @Before
    fun setUp() {
        val rootDir = tempFolder.newFolder("vault_optimizer_test")
        storageManager = VaultStorageManager(rootDir)
        fakeEntryDao = FakeVaultEntryDao()
        fakeFolderDao = FakeVaultFolderDao()
        repository = AndroidVaultRepository(fakeFolderDao, fakeEntryDao, storageManager)
    }

    @Test
    fun removeDuplicatesMovesSubsequentItemsToTrash(): Unit = runBlocking {
        val file1 = tempFolder.newFile("file1.png").apply { writeBytes("Duplicate Content".toByteArray()) }
        val file2 = tempFolder.newFile("file2.png").apply { writeBytes("Duplicate Content".toByteArray()) }
        val file3 = tempFolder.newFile("file3.png").apply { writeBytes("Duplicate Content".toByteArray()) }

        val e1 = repository.importFile(file1, vmk = vmk)
        val e2 = repository.importFile(file2, vmk = vmk)
        val e3 = repository.importFile(file3, vmk = vmk)

        val duplicateGroup = DuplicateGroup(
            contentHash = "test_hash_123",
            fileSize = e1.originalSizeBytes,
            entries = listOf(e1, e2, e3),
            wastedBytes = e1.originalSizeBytes * 2,
        )

        assertEquals(3, repository.getAllEntries().first().size)
        assertEquals(0, repository.getTrashCount().first())

        val removedCount = VaultStorageOptimizer.removeDuplicates(
            repository = repository,
            duplicateGroups = listOf(duplicateGroup),
            moveToTrash = true,
        )

        assertEquals(2, removedCount)
        val remainingActive = repository.getAllEntries().first()
        assertEquals(1, remainingActive.size)
        assertEquals(e1.id, remainingActive.first().id) // e1 is kept

        assertEquals(2, repository.getTrashCount().first())
        val trash = repository.getTrashEntries().first()
        assertTrue(trash.any { it.id == e2.id })
        assertTrue(trash.any { it.id == e3.id })
    }

    @Test
    fun removeDuplicatesPermanentDeleteDeletesBlobsDirectly(): Unit = runBlocking {
        val file1 = tempFolder.newFile("orig.txt").apply { writeBytes("Same Content".toByteArray()) }
        val file2 = tempFolder.newFile("copy.txt").apply { writeBytes("Same Content".toByteArray()) }

        val e1 = repository.importFile(file1, vmk = vmk)
        val e2 = repository.importFile(file2, vmk = vmk)

        val blob1 = storageManager.getEncryptedBlobFile(e1.id)
        val blob2 = storageManager.getEncryptedBlobFile(e2.id)
        assertTrue(blob1.exists())
        assertTrue(blob2.exists())

        val duplicateGroup = DuplicateGroup(
            contentHash = "hash_456",
            fileSize = e1.originalSizeBytes,
            entries = listOf(e1, e2),
            wastedBytes = e1.originalSizeBytes,
        )

        val removed = VaultStorageOptimizer.removeDuplicates(
            repository = repository,
            duplicateGroups = listOf(duplicateGroup),
            moveToTrash = false,
        )

        assertEquals(1, removed)
        assertTrue(blob1.exists())
        assertFalse(blob2.exists())
    }
}

