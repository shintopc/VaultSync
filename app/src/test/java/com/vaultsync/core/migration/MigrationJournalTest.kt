package com.vaultsync.core.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MigrationJournalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testJournalSaveAndRead() {
        val journalDir = tempFolder.newFolder("migration_journal")
        val journalFile = File(journalDir, "migration_journal.json")

        val record1 = BlobJournalRecord(
            blobId = "blob-uuid-1",
            expectedSizeBytes = 1048576L,
            sourceSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            state = BlobMigrationState.COMPLETED,
            attempts = 1,
            lastError = null,
        )

        val record2 = BlobJournalRecord(
            blobId = "blob-uuid-2",
            expectedSizeBytes = 2097152L,
            sourceSha256 = "abcde44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            state = BlobMigrationState.VERIFIED_AT_DESTINATION,
            attempts = 1,
            lastError = null,
        )

        val journal = MigrationJournal(
            migrationId = "mig-12345",
            startedAtMillis = 500L,
            updatedAtMillis = 2000L,
            status = MigrationStatus.IN_PROGRESS,
            totalBlobs = 2,
            completedBlobs = 1,
            failedBlobs = 0,
            currentBlobId = "blob-uuid-2",
            blobs = mapOf("blob-uuid-1" to record1, "blob-uuid-2" to record2),
            errorMessage = null,
        )

        MigrationJournalManager.saveJournal(journalFile, journal)
        assertTrue("Journal file must exist after save", journalFile.exists())

        val loaded = MigrationJournalManager.readJournal(journalFile)
        assertNotNull("Loaded journal must not be null", loaded)
        assertEquals(journal.migrationId, loaded!!.migrationId)
        assertEquals(MigrationStatus.IN_PROGRESS, loaded.status)
        assertEquals(2, loaded.totalBlobs)
        assertEquals(1, loaded.completedBlobs)
        assertEquals(2, loaded.blobs.size)

        val loadedRecord2 = loaded.blobs["blob-uuid-2"]
        assertNotNull(loadedRecord2)
        assertEquals(BlobMigrationState.VERIFIED_AT_DESTINATION, loadedRecord2!!.state)
        assertEquals(record2.sourceSha256, loadedRecord2.sourceSha256)
    }

    @Test
    fun testJournalTransitionsAndAtomicSave() {
        val journalDir = tempFolder.newFolder("migration_journal2")
        val journalFile = File(journalDir, "migration_journal.json")

        val record1 = BlobJournalRecord(
            blobId = "blob-1",
            expectedSizeBytes = 5000L,
            sourceSha256 = "hash-pending",
            state = BlobMigrationState.PENDING,
            attempts = 0,
        )

        val initialJournal = MigrationJournal(
            migrationId = "mig-001",
            startedAtMillis = 100L,
            updatedAtMillis = 100L,
            status = MigrationStatus.IN_PROGRESS,
            totalBlobs = 1,
            completedBlobs = 0,
            failedBlobs = 0,
            blobs = mapOf("blob-1" to record1),
        )

        MigrationJournalManager.saveJournal(journalFile, initialJournal)

        // Simulate transition to COPYING
        val copyingRecord = record1.copy(state = BlobMigrationState.COPYING, attempts = 1)
        val copyingJournal = initialJournal.copy(
            blobs = mapOf("blob-1" to copyingRecord),
            updatedAtMillis = 200L,
        )
        MigrationJournalManager.saveJournal(journalFile, copyingJournal)

        var loaded = MigrationJournalManager.readJournal(journalFile)!!
        assertEquals(BlobMigrationState.COPYING, loaded.blobs["blob-1"]!!.state)
        assertEquals(1, loaded.blobs["blob-1"]!!.attempts)

        // Simulate transition to COMPLETED
        val completedRecord = copyingRecord.copy(state = BlobMigrationState.COMPLETED)
        val completedJournal = copyingJournal.copy(
            blobs = mapOf("blob-1" to completedRecord),
            completedBlobs = 1,
            status = MigrationStatus.COMPLETED,
            updatedAtMillis = 300L,
        )
        MigrationJournalManager.saveJournal(journalFile, completedJournal)

        loaded = MigrationJournalManager.readJournal(journalFile)!!
        assertEquals(BlobMigrationState.COMPLETED, loaded.blobs["blob-1"]!!.state)
        assertEquals(1, loaded.completedBlobs)
        assertEquals(MigrationStatus.COMPLETED, loaded.status)
    }
}
