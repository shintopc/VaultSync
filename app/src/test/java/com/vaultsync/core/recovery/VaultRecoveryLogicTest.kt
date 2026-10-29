package com.vaultsync.core.recovery

import com.vaultsync.core.database.VaultEntryEntity
import com.vaultsync.core.database.VaultFolderEntity
import com.vaultsync.core.manifest.CatalogEntry
import com.vaultsync.core.manifest.CatalogFolder
import com.vaultsync.core.manifest.VaultCatalog
import com.vaultsync.core.model.VaultCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class VaultRecoveryLogicTest {

    @Test
    fun testCatalogToEntityMappingAndTopologicalOrdering() {
        val rootFolder = CatalogFolder(
            id = "root-folder-id",
            name = "Root Album",
            parentId = null,
            createdAtMillis = 1000L,
            updatedAtMillis = 1000L,
        )

        val childFolder = CatalogFolder(
            id = "child-folder-id",
            name = "Child Album",
            parentId = "root-folder-id",
            createdAtMillis = 2000L,
            updatedAtMillis = 2000L,
        )

        val grandChildFolder = CatalogFolder(
            id = "grandchild-folder-id",
            name = "Deep Album",
            parentId = "child-folder-id",
            createdAtMillis = 3000L,
            updatedAtMillis = 3000L,
        )

        // Shuffle order intentionally to test dependency sorting
        val unsortedFolders = listOf(grandChildFolder, rootFolder, childFolder)

        // Simulate topological sorting algorithm used in reconstruction
        val sortedFolders = mutableListOf<CatalogFolder>()
        val insertedFolderIds = mutableSetOf<String>()
        var remaining = unsortedFolders

        var safetyCount = 0
        while (remaining.isNotEmpty() && safetyCount < 50) {
            val (ready, notReady) = remaining.partition { folder ->
                folder.parentId == null || insertedFolderIds.contains(folder.parentId)
            }
            if (ready.isEmpty()) {
                sortedFolders.addAll(notReady)
                break
            }
            ready.forEach {
                sortedFolders.add(it)
                insertedFolderIds.add(it.id)
            }
            remaining = notReady
            safetyCount++
        }

        assertEquals(3, sortedFolders.size)
        assertEquals("root-folder-id", sortedFolders[0].id)
        assertEquals("child-folder-id", sortedFolders[1].id)
        assertEquals("grandchild-folder-id", sortedFolders[2].id)

        // Test CatalogEntry to VaultEntryEntity mapping
        val catalogEntry = CatalogEntry(
            id = "entry-1",
            folderId = "child-folder-id",
            originalFileName = "beach.jpg",
            category = "Images",
            mimeType = "image/jpeg",
            originalSizeBytes = 2048L,
            encryptedSizeBytes = 2200L,
            createdAtMillis = 4000L,
            dateModifiedMillis = 5000L,
        )

        val entity = catalogEntry.toEntity()

        assertEquals("entry-1", entity.id)
        assertEquals("Images", entity.category)
        assertEquals("child-folder-id", entity.folderId)
        assertEquals("beach.jpg", entity.originalFileName)
        assertEquals(2048L, entity.originalSizeBytes)
    }

    @Test
    fun testDuplicateEntryPrevention() {
        val existingEntries = mutableMapOf<String, String>()
        existingEntries["e1"] = "b1"

        val catalogEntries = listOf(
            CatalogEntry(
                id = "e1",
                folderId = null,
                originalFileName = "a.jpg",
                category = "Images",
                mimeType = "image/jpeg",
                originalSizeBytes = 100L,
                encryptedSizeBytes = 200L,
                createdAtMillis = 1L,
                dateModifiedMillis = 2L,
            ),
            CatalogEntry(
                id = "e2",
                folderId = null,
                originalFileName = "b.jpg",
                category = "Images",
                mimeType = "image/jpeg",
                originalSizeBytes = 200L,
                encryptedSizeBytes = 300L,
                createdAtMillis = 3L,
                dateModifiedMillis = 4L,
            ),
        )

        val toInsert = catalogEntries.filter { !existingEntries.containsKey(it.id) }
        assertEquals(1, toInsert.size)
        assertEquals("e2", toInsert[0].id)
    }
}
