package com.vaultsync.core.vault

import com.vaultsync.core.database.VaultEntryDao
import com.vaultsync.core.database.VaultEntryEntity
import com.vaultsync.core.database.VaultFolderDao
import com.vaultsync.core.database.VaultFolderEntity
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.storage.VaultStorageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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

class FakeVaultFolderDao : VaultFolderDao {
    private val folders = MutableStateFlow<Map<String, VaultFolderEntity>>(emptyMap())

    override suspend fun insert(folder: VaultFolderEntity) {
        if (folders.value.containsKey(folder.id)) throw IllegalStateException("Duplicate folder ID")
        folders.value = folders.value + (folder.id to folder)
    }

    override suspend fun update(folder: VaultFolderEntity) {
        folders.value = folders.value + (folder.id to folder)
    }

    override suspend fun getById(id: String): VaultFolderEntity? = folders.value[id]

    override fun getByParentId(parentId: String?): Flow<List<VaultFolderEntity>> {
        return folders.map { map ->
            map.values.filter { it.parentId == parentId }.sortedBy { it.name.lowercase() }
        }
    }

    override fun getAll(): Flow<List<VaultFolderEntity>> {
        return folders.map { map -> map.values.sortedBy { it.name.lowercase() } }
    }

    override suspend fun getSubfolderCount(folderId: String): Int {
        return folders.value.values.count { it.parentId == folderId }
    }

    override suspend fun updateParentId(id: String, newParentId: String?, updatedAt: Long) {
        val existing = folders.value[id] ?: return
        folders.value = folders.value + (id to existing.copy(parentId = newParentId, updatedAtMillis = updatedAt))
    }

    override suspend fun deleteById(id: String) {
        folders.value = folders.value - id
    }
}

class FakeVaultEntryDao : VaultEntryDao {
    private val entries = MutableStateFlow<Map<String, VaultEntryEntity>>(emptyMap())

    override suspend fun insert(entry: VaultEntryEntity) {
        if (entries.value.containsKey(entry.id)) throw IllegalStateException("Duplicate entry ID")
        entries.value = entries.value + (entry.id to entry)
    }

    override suspend fun update(entry: VaultEntryEntity) {
        entries.value = entries.value + (entry.id to entry)
    }

    override suspend fun getById(id: String): VaultEntryEntity? = entries.value[id]

    override fun getByFolderId(folderId: String?): Flow<List<VaultEntryEntity>> {
        return entries.map { map ->
            map.values.filter { !it.isTrash && it.folderId == folderId }.sortedByDescending { it.createdAtMillis }
        }
    }

    override fun getByCategory(category: String): Flow<List<VaultEntryEntity>> {
        return entries.map { map ->
            map.values.filter { !it.isTrash && it.category == category }.sortedByDescending { it.createdAtMillis }
        }
    }

    override fun getAll(): Flow<List<VaultEntryEntity>> {
        return entries.map { map -> map.values.filter { !it.isTrash }.sortedByDescending { it.createdAtMillis } }
    }

    override suspend fun getCountInFolder(folderId: String): Int {
        return entries.value.values.count { it.folderId == folderId }
    }

    override suspend fun setInUse(id: String, inUse: Boolean) {
        entries.value[id]?.let {
            entries.value = entries.value + (id to it.copy(isInUse = inUse))
        }
    }

    override suspend fun updateFolderId(id: String, targetFolderId: String?, modifiedTime: Long) {
        entries.value[id]?.let {
            entries.value = entries.value + (id to it.copy(folderId = targetFolderId, dateModifiedMillis = modifiedTime))
        }
    }

    override suspend fun deleteById(id: String) {
        entries.value = entries.value - id
    }

    override suspend fun getAllEntryIds(): List<String> = entries.value.keys.toList()

    override fun getTrashEntries(): Flow<List<VaultEntryEntity>> {
        return entries.map { map ->
            map.values.filter { it.isTrash }.sortedByDescending { it.deletedAtMillis ?: 0L }
        }
    }

    override fun getTrashCount(): Flow<Int> {
        return entries.map { map -> map.values.count { it.isTrash } }
    }

    override suspend fun getTrashCountDirect(): Int {
        return entries.value.values.count { it.isTrash }
    }

    override suspend fun moveToTrash(id: String, deletedAt: Long) {
        entries.value[id]?.let {
            entries.value = entries.value + (id to it.copy(isTrash = true, deletedAtMillis = deletedAt))
        }
    }

    override suspend fun restoreFromTrash(id: String) {
        entries.value[id]?.let {
            entries.value = entries.value + (id to it.copy(isTrash = false, deletedAtMillis = null))
        }
    }

    override suspend fun getExpiredTrash(cutoff: Long): List<VaultEntryEntity> {
        return entries.value.values.filter { it.isTrash && (it.deletedAtMillis ?: 0L) < cutoff }
    }

    override suspend fun emptyTrash() {
        entries.value = entries.value.filterValues { !it.isTrash }
    }

    override suspend fun updateContentHash(id: String, hash: String) {
        entries.value[id]?.let {
            entries.value = entries.value + (id to it.copy(contentHash = hash))
        }
    }

    override suspend fun getAllActiveList(): List<VaultEntryEntity> {
        return entries.value.values.filter { !it.isTrash }
    }
}

class VaultRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var folderDao: FakeVaultFolderDao
    private lateinit var entryDao: FakeVaultEntryDao
    private lateinit var storageManager: VaultStorageManager
    private lateinit var repository: AndroidVaultRepository
    private lateinit var vmk: ByteArray

    @Before
    fun setup() {
        folderDao = FakeVaultFolderDao()
        entryDao = FakeVaultEntryDao()
        storageManager = VaultStorageManager(tempFolder.newFolder("vault_root"))
        repository = AndroidVaultRepository(folderDao, entryDao, storageManager)
        vmk = VaultKeyManager.generateVaultMasterKey()
    }

    @Test
    fun createRootAndNestedFolders(): Unit = runBlocking {
        val root = repository.createFolder("Work")
        assertEquals("Work", root.name)
        assertNull(root.parentId)

        val subfolder = repository.createFolder("Reports", parentId = root.id)
        assertEquals("Reports", subfolder.name)
        assertEquals(root.id, subfolder.parentId)

        val rootFolders = repository.getFolders(null).first()
        assertEquals(1, rootFolders.size)
        assertEquals(root.id, rootFolders.first().id)

        val childFolders = repository.getFolders(root.id).first()
        assertEquals(1, childFolders.size)
        assertEquals(subfolder.id, childFolders.first().id)
    }

    @Test
    fun renameFolderUpdatesName(): Unit = runBlocking {
        val folder = repository.createFolder("Initial")
        repository.renameFolder(folder.id, "Renamed")

        val updated = repository.getFolder(folder.id)
        assertNotNull(updated)
        assertEquals("Renamed", updated?.name)
    }

    @Test
    fun preventUnsafeFolderDeletionWhenNotEmpty(): Unit = runBlocking {
        val parent = repository.createFolder("Parent")
        repository.createFolder("Child", parentId = parent.id)

        // Deleting parent with children must fail
        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteFolder(parent.id) }
        }
        assertTrue(exception.message!!.contains("Cannot delete non-empty folder"))

        // Add file to empty folder and verify deletion also fails
        val emptyFolder = repository.createFolder("Solo")
        val file = tempFolder.newFile("sample.txt").apply { writeText("Sample content") }
        repository.importFile(file, destinationFolderId = emptyFolder.id, vmk = vmk)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteFolder(emptyFolder.id) }
        }
        Unit
    }

    @Test
    fun importFileEncryptsIncrementallyAndPreservesOriginal(): Unit = runBlocking {
        val originalText = "Sensitive vault secret message"
        val originalFile = tempFolder.newFile("secret_document.txt").apply {
            writeBytes(originalText.toByteArray(Charsets.UTF_8))
        }

        val entry = repository.importFile(
            sourceFile = originalFile,
            displayName = "CustomSecret.txt",
            destinationFolderId = null,
            vmk = vmk,
            preserveOriginal = true,
        )

        // 1. Original file remains on disk untouched
        assertTrue(originalFile.exists())
        assertEquals(originalText, originalFile.readText())

        // 2. Encrypted blob exists under opaque ID
        val blobFile = storageManager.getEncryptedBlobFile(entry.id)
        assertTrue(blobFile.exists())
        assertFalse(blobFile.name.contains("secret"))
        assertTrue(blobFile.length() > originalText.length)

        // 3. Category detected as Documents
        assertEquals(VaultCategory.Documents, entry.category)
        assertEquals("CustomSecret.txt", entry.originalFileName)

        // 4. Decrypt and verify exact match
        val exportDest = File(tempFolder.root, "exported.txt")
        repository.exportOrDecrypt(entry.id, exportDest, vmk)
        assertEquals(originalText, exportDest.readText())
    }

    @Test
    fun preventDeletionOfFileCurrentlyInUse(): Unit = runBlocking {
        val file = tempFolder.newFile("active.txt").apply { writeText("Active data") }
        val entry = repository.importFile(file, vmk = vmk)

        repository.setEntryInUse(entry.id, inUse = true)

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteEntry(entry.id) }
        }
        assertTrue(ex.message!!.contains("in use"))

        // After releasing in-use, deletion succeeds
        repository.setEntryInUse(entry.id, inUse = false)
        repository.deleteEntry(entry.id)

        assertNull(repository.getEntry(entry.id))
        assertFalse(storageManager.getEncryptedBlobFile(entry.id).exists())
    }

    @Test
    fun categoryAndFolderViewsReferenceSameEntries(): Unit = runBlocking {
        val folder = repository.createFolder("Photos")
        val imageFile = tempFolder.newFile("pic.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val entry = repository.importFile(imageFile, destinationFolderId = folder.id, vmk = vmk)

        // View from folder
        val folderEntries = repository.getEntries(folder.id).first()
        assertEquals(1, folderEntries.size)
        assertEquals(entry.id, folderEntries.first().id)

        // View from category
        val categoryEntries = repository.getEntriesByCategory(VaultCategory.Images).first()
        assertEquals(1, categoryEntries.size)
        assertEquals(entry.id, categoryEntries.first().id)

        // View from All Files
        val allEntries = repository.getAllEntries().first()
        assertEquals(1, allEntries.size)
        assertEquals(entry.id, allEntries.first().id)
    }

    @Test
    fun cleanupOrphanedBlobsRemovesDanglingFiles(): Unit = runBlocking {
        storageManager.initDirectories()
        val orphan = File(storageManager.blobsDir, "dangling-uuid.vsf").apply {
            writeBytes("unindexed data".toByteArray())
        }
        assertTrue(orphan.exists())

        repository.cleanupOrphanedBlobs()

        assertFalse(orphan.exists())
    }

    @Test
    fun renameEntryUpdatesFileNameAndCategory(): Unit = runBlocking {
        val file = tempFolder.newFile("document.txt").apply { writeBytes("hello".toByteArray()) }
        val entry = repository.importFile(file, vmk = vmk)
        assertEquals("document.txt", entry.originalFileName)
        assertEquals(VaultCategory.Documents, entry.category)

        repository.renameEntry(entry.id, "photo.jpg")

        val updated = repository.getEntry(entry.id)
        assertNotNull(updated)
        assertEquals("photo.jpg", updated!!.originalFileName)
        assertEquals(VaultCategory.Images, updated.category)
    }

    @Test
    fun copyEntryCreatesIndependentEncryptedBlobWithSameContent(): Unit = runBlocking {
        val content = "Private secret message for copying test".toByteArray()
        val file = tempFolder.newFile("secret.txt").apply { writeBytes(content) }
        val sourceEntry = repository.importFile(file, vmk = vmk)

        val folder = repository.createFolder("TargetFolder")
        val copiedEntry = repository.copyEntry(sourceEntry.id, targetFolderId = folder.id, vmk = vmk)

        assertNotNull(copiedEntry)
        assertTrue(copiedEntry.id != sourceEntry.id)
        assertEquals(folder.id, copiedEntry.folderId)
        assertEquals(sourceEntry.originalSizeBytes, copiedEntry.originalSizeBytes)

        // Verify copied file decrypts cleanly
        val decryptedCopy = tempFolder.newFile("decrypted_copy.txt")
        repository.exportOrDecrypt(copiedEntry.id, decryptedCopy, vmk)
        assertArrayEquals(content, decryptedCopy.readBytes())
    }

    @Test
    fun moveFolderRejectsCyclesAndSelfMove(): Unit = runBlocking {
        val parent = repository.createFolder("Parent")
        val child = repository.createFolder("Child", parentId = parent.id)

        // Self move rejected
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.moveFolder(parent.id, parent.id) }
        }

        // Cycle (moving parent into child) rejected
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.moveFolder(parent.id, child.id) }
        }

        // Valid move to root
        repository.moveFolder(child.id, null)
        val updatedChild = repository.getFolder(child.id)
        assertNull(updatedChild?.parentId)
    }

    @Test
    fun trashWorkflowMoveRestoreEmptyAndPurge(): Unit = runBlocking {
        val file1 = tempFolder.newFile("doc1.txt").apply { writeBytes("Document 1".toByteArray()) }
        val file2 = tempFolder.newFile("doc2.txt").apply { writeBytes("Document 2".toByteArray()) }
        val entry1 = repository.importFile(file1, vmk = vmk)
        val entry2 = repository.importFile(file2, vmk = vmk)

        assertEquals(2, repository.getAllEntries().first().size)
        assertEquals(0, repository.getTrashCount().first())

        // 1. Move entry1 to trash
        repository.moveToTrash(entry1.id)
        val activeAfterTrash = repository.getAllEntries().first()
        assertEquals(1, activeAfterTrash.size)
        assertEquals(entry2.id, activeAfterTrash.first().id)
        assertEquals(1, repository.getTrashCount().first())

        val trashEntries = repository.getTrashEntries().first()
        assertEquals(1, trashEntries.size)
        assertEquals(entry1.id, trashEntries.first().id)
        assertTrue(trashEntries.first().isTrash)
        assertNotNull(trashEntries.first().deletedAtMillis)

        // 2. Restore entry1 from trash
        repository.restoreFromTrash(entry1.id)
        assertEquals(2, repository.getAllEntries().first().size)
        assertEquals(0, repository.getTrashCount().first())

        // 3. Move both to trash and empty trash
        repository.moveToTrash(entry1.id)
        repository.moveToTrash(entry2.id)
        assertEquals(2, repository.getTrashCount().first())

        val blob1 = storageManager.getEncryptedBlobFile(entry1.id)
        val blob2 = storageManager.getEncryptedBlobFile(entry2.id)
        assertTrue(blob1.exists())
        assertTrue(blob2.exists())

        repository.emptyTrash()
        assertEquals(0, repository.getTrashCount().first())
        assertFalse(blob1.exists())
        assertFalse(blob2.exists())
    }

    @Test
    fun purgeExpiredTrashPurgesOnlyEntriesOlderThanCutoff(): Unit = runBlocking {
        val fileOld = tempFolder.newFile("old.txt").apply { writeBytes("Old File".toByteArray()) }
        val fileNew = tempFolder.newFile("new.txt").apply { writeBytes("New File".toByteArray()) }
        val entryOld = repository.importFile(fileOld, vmk = vmk)
        val entryNew = repository.importFile(fileNew, vmk = vmk)

        // Simulate entryOld deleted 35 days ago, entryNew deleted 5 days ago
        val now = System.currentTimeMillis()
        val thirtyFiveDaysAgo = now - (35L * 24 * 60 * 60 * 1000)
        val fiveDaysAgo = now - (5L * 24 * 60 * 60 * 1000)

        entryDao.moveToTrash(entryOld.id, thirtyFiveDaysAgo)
        entryDao.moveToTrash(entryNew.id, fiveDaysAgo)

        assertEquals(2, repository.getTrashCount().first())
        val purgedCount = repository.purgeExpiredTrash(retentionDays = 30)
        assertEquals(1, purgedCount)

        val remainingTrash = repository.getTrashEntries().first()
        assertEquals(1, remainingTrash.size)
        assertEquals(entryNew.id, remainingTrash.first().id)
    }
}
