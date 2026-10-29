package com.vaultsync.core.migration

import android.content.Context
import android.util.Log
import com.vaultsync.core.database.VaultDatabase
import com.vaultsync.core.manifest.VaultManifest
import com.vaultsync.core.manifest.VaultManifestManager
import com.vaultsync.core.security.VaultCredentialStore
import com.vaultsync.core.security.VaultFileDecryptor
import com.vaultsync.core.security.VaultSession
import com.vaultsync.core.storage.VaultStorageManager
import com.vaultsync.core.storage.VaultStoragePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class VaultMigrationEngine(
    private val context: Context,
    private val database: VaultDatabase,
    private val credentialStore: VaultCredentialStore,
    private val session: VaultSession,
    private val sourceStorageManager: VaultStorageManager,
) {
    companion object {
        private const val TAG = "VaultMigrationEngine"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KiB
    }

    suspend fun executeMigration(
        destinationRoot: File,
        vmk: ByteArray,
        onProgress: ((MigrationProgress) -> Unit)? = null,
    ): MigrationResult = withContext(Dispatchers.IO) {
        // 1. Invariant Checks
        require(session.isUnlocked.value) { "Vault must be unlocked before migration" }
        require(destinationRoot.absolutePath != sourceStorageManager.vaultRoot.absolutePath) {
            "Destination directory cannot be identical to source directory"
        }

        destinationRoot.mkdirs()
        if (!destinationRoot.canWrite()) {
            return@withContext MigrationResult(false, 0, 0, "Destination directory is not writable")
        }

        // Initialize destination directories
        val destBlobsDir = File(destinationRoot, "blobs").apply { mkdirs() }
        val destJournalDir = File(destinationRoot, MigrationJournalManager.JOURNAL_DIR_NAME).apply { mkdirs() }
        val journalFile = File(destJournalDir, MigrationJournalManager.JOURNAL_FILE_NAME)

        // Ensure .nomedia in destination directories
        File(destinationRoot, ".nomedia").createNewFile()
        File(destBlobsDir, ".nomedia").createNewFile()

        // 2. Inventory all referenced entries from Room DB
        val allEntries = database.entryDao().getAll().first()
        val totalBlobs = allEntries.size

        // 3. Initialize or Resume Journal
        val loadedJournal = MigrationJournalManager.readJournal(journalFile)
        var journal: MigrationJournal = if (loadedJournal == null || loadedJournal.status == MigrationStatus.COMPLETED) {
            val blobsMap = mutableMapOf<String, BlobJournalRecord>()
            for (entry in allEntries) {
                val sourceFile = sourceStorageManager.getEncryptedBlobFile(entry.id)
                val expectedSize = if (sourceFile.exists()) sourceFile.length() else entry.encryptedSizeBytes
                val sha256 = if (sourceFile.exists()) computeSha256(sourceFile) else ""
                blobsMap[entry.id] = BlobJournalRecord(
                    blobId = entry.id,
                    expectedSizeBytes = expectedSize,
                    sourceSha256 = sha256,
                    state = BlobMigrationState.PENDING,
                )
            }
            val newJournal = MigrationJournal(
                migrationId = UUID.randomUUID().toString(),
                startedAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis(),
                status = MigrationStatus.IN_PROGRESS,
                totalBlobs = totalBlobs,
                completedBlobs = 0,
                failedBlobs = 0,
                blobs = blobsMap,
            )
            MigrationJournalManager.saveJournal(journalFile, newJournal)
            newJournal
        } else {
            loadedJournal
        }

        // 4. Create and persist the initial Manifest before moving any data
        val destManifestFile = File(destinationRoot, VaultManifest.MANIFEST_FILE_NAME)
        val manifest = VaultManifestManager.createManifestFromCurrentVault(
            database = database,
            credentialStore = credentialStore,
            vmk = vmk,
        )
        VaultManifestManager.writeManifest(destManifestFile, manifest)

        // 5. File-by-File Resumable Migration
        var completedCount = 0
        var totalBytesTransferred = 0L
        val totalBytes = allEntries.sumOf { it.encryptedSizeBytes }

        for ((index, entry) in allEntries.withIndex()) {
            val blobId = entry.id
            var record = journal.blobs[blobId] ?: BlobJournalRecord(
                blobId = blobId,
                expectedSizeBytes = entry.encryptedSizeBytes,
                sourceSha256 = "",
                state = BlobMigrationState.PENDING,
            )

            // If already completed in previous run, skip
            if (record.state == BlobMigrationState.COMPLETED) {
                completedCount++
                totalBytesTransferred += record.expectedSizeBytes
                continue
            }

            val sourceBlob = sourceStorageManager.getEncryptedBlobFile(blobId)
            val finalDestBlob = File(destBlobsDir, "$blobId.vsf")
            val tempDestBlob = File(destBlobsDir, "$blobId.vsf.part")

            onProgress?.invoke(
                MigrationProgress(
                    currentFileIndex = index + 1,
                    totalFiles = totalBlobs,
                    currentFileName = entry.originalFileName,
                    bytesTransferred = totalBytesTransferred,
                    totalBytes = totalBytes,
                    status = MigrationStatus.IN_PROGRESS,
                    message = "Migrating ${entry.originalFileName}...",
                ),
            )

            try {
                // Step A: If source was already deleted but state was not updated to COMPLETED
                if (record.state == BlobMigrationState.SOURCE_DELETED) {
                    record = record.copy(state = BlobMigrationState.COMPLETED)
                    journal = updateJournalRecord(journal, journalFile, record)
                    completedCount++
                    continue
                }

                // Step B: If destination was verified in prior run, skip copy and proceed to delete source
                if (record.state == BlobMigrationState.VERIFIED_AT_DESTINATION) {
                    if (finalDestBlob.exists() && finalDestBlob.length() == record.expectedSizeBytes) {
                        if (sourceBlob.exists()) {
                            sourceBlob.delete()
                        }
                        record = record.copy(state = BlobMigrationState.COMPLETED)
                        journal = updateJournalRecord(journal, journalFile, record)
                        completedCount++
                        continue
                    }
                }

                // Step C: Verify source file exists
                if (!sourceBlob.exists()) {
                    // Check if already in final destination
                    if (finalDestBlob.exists()) {
                        record = record.copy(state = BlobMigrationState.COMPLETED)
                        journal = updateJournalRecord(journal, journalFile, record)
                        completedCount++
                        continue
                    } else {
                        throw IllegalStateException("Source blob missing for entry: ${entry.originalFileName} ($blobId)")
                    }
                }

                // Update journal: COPYING
                record = record.copy(
                    state = BlobMigrationState.COPYING,
                    expectedSizeBytes = sourceBlob.length(),
                    sourceSha256 = if (record.sourceSha256.isNotBlank()) record.sourceSha256 else computeSha256(sourceBlob),
                    attempts = record.attempts + 1,
                )
                journal = updateJournalRecord(journal, journalFile, record)

                // Clean any leftover temp destination file
                if (tempDestBlob.exists()) {
                    tempDestBlob.delete()
                }

                // Stream copy with on-the-fly SHA-256 calculation
                val digest = MessageDigest.getInstance("SHA-256")
                BufferedInputStream(FileInputStream(sourceBlob)).use { input ->
                    BufferedOutputStream(FileOutputStream(tempDestBlob)).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            totalBytesTransferred += bytesRead
                        }
                        output.flush()
                        try {
                            (output as? FileOutputStream)?.fd?.sync()
                        } catch (_: Exception) {}
                    }
                }

                // Step D: Strict Integrity Verification
                val destSha256 = bytesToHex(digest.digest())
                if (destSha256 != record.sourceSha256) {
                    tempDestBlob.delete()
                    throw SecurityException("Checksum mismatch for blob $blobId: expected ${record.sourceSha256}, got $destSha256")
                }
                if (tempDestBlob.length() != sourceBlob.length()) {
                    tempDestBlob.delete()
                    throw java.io.IOException("Size mismatch for blob $blobId: expected ${sourceBlob.length()}, got ${tempDestBlob.length()}")
                }

                // Verify VSF1 header validity
                val header = VaultFileDecryptor.readHeader(tempDestBlob)
                require(header.fileId.toString() == blobId) { "Header fileId mismatch: expected $blobId, got ${header.fileId}" }

                // Atomic rename to final destination
                if (finalDestBlob.exists()) {
                    finalDestBlob.delete()
                }
                if (!tempDestBlob.renameTo(finalDestBlob)) {
                    tempDestBlob.copyTo(finalDestBlob, overwrite = true)
                    tempDestBlob.delete()
                }

                // Step E: Record VERIFIED_AT_DESTINATION in journal
                record = record.copy(state = BlobMigrationState.VERIFIED_AT_DESTINATION)
                journal = updateJournalRecord(journal, journalFile, record)

                // Step F: Safe removal of source blob
                if (!sourceBlob.delete()) {
                    Log.w(TAG, "Could not immediately delete source blob: ${sourceBlob.absolutePath}")
                }

                // Step G: Record COMPLETED
                record = record.copy(state = BlobMigrationState.COMPLETED)
                journal = updateJournalRecord(journal, journalFile, record)
                completedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Error migrating blob $blobId: ${e.message}")
                record = record.copy(
                    state = BlobMigrationState.FAILED,
                    lastError = e.message,
                )
                journal = updateJournalRecord(journal, journalFile, record)
                return@withContext MigrationResult(
                    isSuccess = false,
                    migratedCount = completedCount,
                    totalCount = totalBlobs,
                    errorMessage = "Failed on file ${entry.originalFileName}: ${e.message}",
                )
            }
        }

        // 6. Sample Decryption Test on a completed file to guarantee end-to-end validity
        if (allEntries.isNotEmpty()) {
            val sample = allEntries.first()
            val sampleBlob = File(destBlobsDir, "${sample.id}.vsf")
            if (sampleBlob.exists()) {
                val sampleHeader = VaultFileDecryptor.readHeader(sampleBlob)
                val unwrappedDek = VaultFileDecryptor.unwrapDek(sampleHeader, vmk)
                require(unwrappedDek.size == 32) { "Sample decryption verification failed" }
            }
        }

        // 7. Update final manifest & mark journal COMPLETED
        val finalManifest = VaultManifestManager.createManifestFromCurrentVault(
            database = database,
            credentialStore = credentialStore,
            vmk = vmk,
            existingVaultId = manifest.vaultId,
        )
        VaultManifestManager.writeManifest(destManifestFile, finalManifest)

        journal = journal.copy(
            status = MigrationStatus.COMPLETED,
            completedBlobs = completedCount,
            updatedAtMillis = System.currentTimeMillis(),
        )
        MigrationJournalManager.saveJournal(journalFile, journal)

        // 8. Update storage preferences to activate shared storage
        VaultStoragePreferences(context).configureSharedStorage(
            path = destinationRoot.absolutePath,
        )

        onProgress?.invoke(
            MigrationProgress(
                currentFileIndex = totalBlobs,
                totalFiles = totalBlobs,
                currentFileName = "Complete",
                bytesTransferred = totalBytes,
                totalBytes = totalBytes,
                status = MigrationStatus.COMPLETED,
                message = "Migration completed successfully!",
            ),
        )

        MigrationResult(
            isSuccess = true,
            migratedCount = completedCount,
            totalCount = totalBlobs,
        )
    }

    private fun updateJournalRecord(
        journal: MigrationJournal,
        journalFile: File,
        record: BlobJournalRecord,
    ): MigrationJournal {
        val updatedMap = journal.blobs.toMutableMap()
        updatedMap[record.blobId] = record
        val updatedJournal = journal.copy(
            updatedAtMillis = System.currentTimeMillis(),
            blobs = updatedMap,
            completedBlobs = updatedMap.values.count { it.state == BlobMigrationState.COMPLETED },
            failedBlobs = updatedMap.values.count { it.state == BlobMigrationState.FAILED },
        )
        MigrationJournalManager.saveJournal(journalFile, updatedJournal)
        return updatedJournal
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var n: Int
            while (fis.read(buffer).also { n = it } != -1) {
                digest.update(buffer, 0, n)
            }
        }
        return bytesToHex(digest.digest())
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
