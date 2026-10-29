package com.vaultsync.feature.vault

import com.vaultsync.core.database.VaultEntryDao
import com.vaultsync.core.database.VaultEntryEntity
import com.vaultsync.core.database.VaultFolderDao
import com.vaultsync.core.database.VaultFolderEntity
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.security.VaultSession
import com.vaultsync.core.storage.VaultPreviewManager
import com.vaultsync.core.storage.VaultStorageManager
import com.vaultsync.core.vault.AndroidVaultRepository
import com.vaultsync.core.vault.FakeVaultEntryDao
import com.vaultsync.core.vault.FakeVaultFolderDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultSessionAndNavigationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var folderDao: FakeVaultFolderDao
    private lateinit var entryDao: FakeVaultEntryDao
    private lateinit var storageManager: VaultStorageManager
    private lateinit var repository: AndroidVaultRepository
    private lateinit var previewManager: VaultPreviewManager
    private lateinit var vmk: ByteArray

    @Before
    fun setup() {
        session = VaultSession()
        folderDao = FakeVaultFolderDao()
        entryDao = FakeVaultEntryDao()

        val rootDir = tempFolder.newFolder("vault_data")
        val cacheDir = tempFolder.newFolder("cache")

        storageManager = VaultStorageManager(rootDir)
        storageManager.initDirectories()

        repository = AndroidVaultRepository(folderDao, entryDao, storageManager)
        previewManager = VaultPreviewManager(
            context = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): File = cacheDir
            },
            vaultRepository = repository,
            storageManager = storageManager,
        )

        vmk = VaultKeyManager.generateVaultMasterKey()
    }

    @Test
    fun sessionLockClearsAndZeroizesVmk(): Unit = runBlocking {
        session.unlock(vmk)
        assertTrue(session.isUnlocked.value)

        val retrievedKey = session.getVmkCopy()
        assertEquals(32, retrievedKey.size)

        session.lock()
        assertFalse(session.isUnlocked.value)

        assertThrows(SecurityException::class.java) {
            session.getVmkCopy()
        }

        assertThrows(SecurityException::class.java) {
            session.withVmk { }
        }
    }

    @Test
    fun previewManagerLifecycleCleansUpDecryptedCacheAndResetsInUse(): Unit = runBlocking {
        session.unlock(vmk)
        val testFile = tempFolder.newFile("sample.jpg").apply {
            writeBytes("fake image bytes for preview".toByteArray())
        }
        val entry = repository.importFile(testFile, vmk = vmk)

        // Pre-preview check
        assertFalse(repository.getEntry(entry.id)!!.isInUse)

        // Decrypt preview
        val previewFile = previewManager.decryptForPreview(entry, vmk)
        assertTrue(previewFile.exists())
        assertTrue(repository.getEntry(entry.id)!!.isInUse)

        // Cannot delete while in use
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteEntry(entry.id) }
        }

        // Release preview
        previewManager.releasePreview(entry, previewFile)
        assertFalse(previewFile.exists())
        assertFalse(repository.getEntry(entry.id)!!.isInUse)

        // Now deletion succeeds
        repository.deleteEntry(entry.id)
        assertNull(repository.getEntry(entry.id))
    }

    @Test
    fun clearAllPreviewsWipesCacheFolder(): Unit = runBlocking {
        session.unlock(vmk)
        val file1 = tempFolder.newFile("one.png").apply { writeBytes("img1".toByteArray()) }
        val file2 = tempFolder.newFile("two.png").apply { writeBytes("img2".toByteArray()) }
        val entry1 = repository.importFile(file1, vmk = vmk)
        val entry2 = repository.importFile(file2, vmk = vmk)

        val preview1 = previewManager.decryptForPreview(entry1, vmk)
        val preview2 = previewManager.decryptForPreview(entry2, vmk)

        assertTrue(preview1.exists())
        assertTrue(preview2.exists())

        previewManager.clearAllPreviews()

        assertFalse(preview1.exists())
        assertFalse(preview2.exists())
    }

    @Test
    fun categoryAndFolderConsistencyUnderMoveAndRename(): Unit = runBlocking {
        session.unlock(vmk)
        val folderA = repository.createFolder("FolderA")
        val folderB = repository.createFolder("FolderB")

        val audioFile = tempFolder.newFile("track.mp3").apply { writeBytes("audio".toByteArray()) }
        val entry = repository.importFile(audioFile, destinationFolderId = folderA.id, vmk = vmk)

        // Check initial state
        assertEquals(folderA.id, entry.folderId)
        assertEquals(VaultCategory.Audio, entry.category)

        var audioList = repository.getEntriesByCategory(VaultCategory.Audio).first()
        assertEquals(1, audioList.size)
        assertEquals(folderA.id, audioList.first().folderId)

        // Move entry to folderB
        repository.moveEntry(entry.id, folderB.id)

        // Folder view for A should now be empty, B has entry
        assertTrue(repository.getEntries(folderA.id).first().isEmpty())
        assertEquals(1, repository.getEntries(folderB.id).first().size)

        // Category view still has 1 audio file, now referencing folderB
        audioList = repository.getEntriesByCategory(VaultCategory.Audio).first()
        assertEquals(1, audioList.size)
        assertEquals(folderB.id, audioList.first().folderId)

        // Rename entry to document
        repository.renameEntry(entry.id, "notes.txt")
        assertTrue(repository.getEntriesByCategory(VaultCategory.Audio).first().isEmpty())
        val docList = repository.getEntriesByCategory(VaultCategory.Documents).first()
        assertEquals(1, docList.size)
        assertEquals("notes.txt", docList.first().originalFileName)
        assertEquals(folderB.id, docList.first().folderId)
    }

    @Test
    fun previewManagerTextDetectionIdentifiesDocumentExtensions(): Unit {
        val txtEntry = com.vaultsync.core.model.VaultEntry(
            id = "1",
            folderId = null,
            originalFileName = "script.py",
            category = VaultCategory.Documents,
            mimeType = "text/x-python",
            originalSizeBytes = 100,
            encryptedSizeBytes = 200,
            createdAtMillis = 0,
            dateModifiedMillis = 0,
            isInUse = false,
        )
        assertTrue(previewManager.isTextDocument(txtEntry))
        assertTrue(previewManager.isPreviewable(txtEntry))

        val pdfEntry = com.vaultsync.core.model.VaultEntry(
            id = "pdf-1",
            folderId = null,
            originalFileName = "document.pdf",
            category = VaultCategory.Documents,
            mimeType = "application/pdf",
            originalSizeBytes = 1500,
            encryptedSizeBytes = 1600,
            createdAtMillis = 0,
            dateModifiedMillis = 0,
            isInUse = false,
        )
        assertTrue(previewManager.isPdfDocument(pdfEntry))
        assertTrue(previewManager.isPreviewable(pdfEntry))

        val binaryDoc = com.vaultsync.core.model.VaultEntry(
            id = "2",
            folderId = null,
            originalFileName = "archive.zip",
            category = VaultCategory.Other,
            mimeType = "application/zip",
            originalSizeBytes = 500,
            encryptedSizeBytes = 600,
            createdAtMillis = 0,
            dateModifiedMillis = 0,
            isInUse = false,
        )
        assertFalse(previewManager.isTextDocument(binaryDoc))
        assertFalse(previewManager.isPdfDocument(binaryDoc))
        assertFalse(previewManager.isPreviewable(binaryDoc))
    }

    @Test
    fun preventMovingFolderIntoItselfOrDescendants(): Unit = runBlocking {
        session.unlock(vmk)
        val rootA = repository.createFolder("RootA")
        val childB = repository.createFolder("ChildB", parentId = rootA.id)
        val grandChildC = repository.createFolder("GrandChildC", parentId = childB.id)

        // Cannot move into itself
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.moveFolder(rootA.id, rootA.id) }
        }

        // Cannot move parent into direct child
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.moveFolder(rootA.id, childB.id) }
        }

        // Cannot move parent into grandchild
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.moveFolder(rootA.id, grandChildC.id) }
        }

        // UI descendant helper identifies all subfolder descendants
        val allFolders = repository.getAllFolders().first()
        val descendantsOfA = getDescendantFolderIds(rootA.id, allFolders)
        assertTrue(descendantsOfA.contains(rootA.id))
        assertTrue(descendantsOfA.contains(childB.id))
        assertTrue(descendantsOfA.contains(grandChildC.id))

        // Moving grandchild to root succeeds
        repository.moveFolder(grandChildC.id, null)
        val updatedC = repository.getFolder(grandChildC.id)!!
        assertNull(updatedC.parentId)
    }

    @Test
    fun folderDeletionGuardsAgainstNonEmptyFolder(): Unit = runBlocking {
        session.unlock(vmk)
        val folder = repository.createFolder("SafeFolder")
        val subfolder = repository.createFolder("SubSafe", parentId = folder.id)

        // Deleting folder with subfolder must throw IllegalStateException
        val ex1 = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteFolder(folder.id) }
        }
        assertTrue(ex1.message!!.contains("contains 1 subfolder(s)"))

        // Delete subfolder
        repository.deleteFolder(subfolder.id)

        // Now import a file into folder
        val testFile = tempFolder.newFile("file.txt").apply { writeBytes("content".toByteArray()) }
        val entry = repository.importFile(testFile, destinationFolderId = folder.id, vmk = vmk)

        // Deleting folder with file must throw IllegalStateException
        val ex2 = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteFolder(folder.id) }
        }
        assertTrue(ex2.message!!.contains("contains 0 subfolder(s) and 1 file(s)"))

        // Delete entry, then folder deletion succeeds
        repository.deleteEntry(entry.id)
        repository.deleteFolder(folder.id)
        assertNull(repository.getFolder(folder.id))
    }

    @Test
    fun insufficientStorageHandlingDuringImportAndCopy(): Unit = runBlocking {
        session.unlock(vmk)
        val hugeSizeBytes = Long.MAX_VALUE

        // storageManager.hasAvailableStorage must reject huge size
        assertFalse(storageManager.hasAvailableStorage(hugeSizeBytes))

        val dummyStream = java.io.ByteArrayInputStream(ByteArray(10))
        val ex = assertThrows(java.io.IOException::class.java) {
            runBlocking {
                repository.importStream(
                    inputStream = dummyStream,
                    displayName = "huge.bin",
                    originalSize = hugeSizeBytes,
                    mimeType = "application/octet-stream",
                    destinationFolderId = null,
                    vmk = vmk,
                )
            }
        }
        assertTrue(ex.message!!.contains("Insufficient storage"))
    }

    @Test
    fun corruptedFileDecryptionHandlingAndCleanup(): Unit = runBlocking {
        session.unlock(vmk)
        val testFile = tempFolder.newFile("secret.png").apply {
            writeBytes("Valid image content for vault encryption test".toByteArray())
        }
        val entry = repository.importFile(testFile, vmk = vmk)
        val blobFile = storageManager.getEncryptedBlobFile(entry.id)
        assertTrue(blobFile.exists())

        // Tamper with the encrypted ciphertext bytes to simulate corruption
        val corruptedBytes = blobFile.readBytes()
        assertTrue(corruptedBytes.size > 30)
        // Corrupt a byte in the payload / authentication tag
        corruptedBytes[corruptedBytes.size - 5] = (corruptedBytes[corruptedBytes.size - 5].toInt() xor 0xFF).toByte()
        blobFile.writeBytes(corruptedBytes)

        // Decryption must fail due to AEAD tag verification failure
        assertThrows(Exception::class.java) {
            runBlocking { previewManager.decryptForPreview(entry, vmk) }
        }

        // In-use status must be reset to false despite the error
        assertFalse(repository.getEntry(entry.id)!!.isInUse)

        // No stray temporary preview files should remain
        val previewFiles = tempFolder.root.resolve("cache/vault_previews").listFiles { _, name -> name != ".nomedia" }
        assertTrue(previewFiles == null || previewFiles.isEmpty())
    }

    @Test
    fun missingBlobFileHandling(): Unit = runBlocking {
        session.unlock(vmk)
        val testFile = tempFolder.newFile("missing_test.png").apply {
            writeBytes("Some bytes".toByteArray())
        }
        val entry = repository.importFile(testFile, vmk = vmk)
        val blobFile = storageManager.getEncryptedBlobFile(entry.id)
        assertTrue(blobFile.exists())

        // Delete blob file from disk
        assertTrue(blobFile.delete())

        // Decrypt preview should throw FileNotFoundException
        assertThrows(java.io.FileNotFoundException::class.java) {
            runBlocking { previewManager.decryptForPreview(entry, vmk) }
        }
        assertFalse(repository.getEntry(entry.id)!!.isInUse)

        // Export should also throw FileNotFoundException
        val exportTarget = tempFolder.newFile("target.png")
        assertThrows(java.io.FileNotFoundException::class.java) {
            runBlocking { repository.exportOrDecrypt(entry.id, exportTarget, vmk) }
        }
    }

    @Test
    fun wrongCredentialUnwrapFailure(): Unit {
        val masterKey = VaultKeyManager.generateVaultMasterKey()
        val wrapped = VaultKeyManager.wrapVaultMasterKey(masterKey, "CorrectPIN123".toCharArray())

        // Correct PIN unwraps identically
        val unwrapped = VaultKeyManager.unwrapVaultMasterKey(wrapped, "CorrectPIN123".toCharArray())
        assertTrue(masterKey.contentEquals(unwrapped))

        // Wrong PIN must throw Exception
        assertThrows(Exception::class.java) {
            VaultKeyManager.unwrapVaultMasterKey(wrapped, "WrongPIN999".toCharArray())
        }
    }

    @Test
    fun copyEntryDuplicationAndNaming(): Unit = runBlocking {
        session.unlock(vmk)
        val folder = repository.createFolder("TargetFolder")
        val file = tempFolder.newFile("original.txt").apply { writeBytes("Hello Vault".toByteArray()) }
        val originalEntry = repository.importFile(file, destinationFolderId = null, vmk = vmk)

        // Copy to another folder: keeps original name
        val copyInFolder = repository.copyEntry(originalEntry.id, folder.id, vmk)
        assertEquals("original.txt", copyInFolder.originalFileName)
        assertEquals(folder.id, copyInFolder.folderId)
        assertTrue(copyInFolder.id != originalEntry.id)

        // Copy to same folder: prepends "Copy of "
        val copyInSame = repository.copyEntry(originalEntry.id, null, vmk)
        assertEquals("Copy of original.txt", copyInSame.originalFileName)
        assertNull(copyInSame.folderId)
    }

    @Test
    fun rootAndFolderEntryIsolation(): Unit = runBlocking {
        session.unlock(vmk)
        val folder = repository.createFolder("SubFolder")

        val rootFile = tempFolder.newFile("root_photo.jpg").apply { writeBytes("root photo".toByteArray()) }
        val folderFile = tempFolder.newFile("folder_photo.jpg").apply { writeBytes("folder photo".toByteArray()) }

        val rootEntry = repository.importFile(rootFile, destinationFolderId = null, vmk = vmk)
        val folderEntry = repository.importFile(folderFile, destinationFolderId = folder.id, vmk = vmk)

        // Root query (folderId = null) must contain ONLY rootEntry
        val rootEntries = repository.getEntries(null).first()
        assertEquals(1, rootEntries.size)
        assertEquals(rootEntry.id, rootEntries.first().id)
        assertNull(rootEntries.first().folderId)

        // Subfolder query (folderId = folder.id) must contain ONLY folderEntry
        val subfolderEntries = repository.getEntries(folder.id).first()
        assertEquals(1, subfolderEntries.size)
        assertEquals(folderEntry.id, subfolderEntries.first().id)
        assertEquals(folder.id, subfolderEntries.first().folderId)

        // Moving folderEntry to root (null) updates isolation
        repository.moveEntry(folderEntry.id, null)
        val updatedRoot = repository.getEntries(null).first()
        assertEquals(2, updatedRoot.size)
        assertTrue(updatedRoot.all { it.folderId == null })

        val updatedSubfolder = repository.getEntries(folder.id).first()
        assertTrue(updatedSubfolder.isEmpty())
    }
}

