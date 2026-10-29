package com.vaultsync.core.migration

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

object MigrationJournalManager {
    private const val TAG = "MigrationJournalManager"
    const val JOURNAL_FILE_NAME = "migration_journal.json"
    const val JOURNAL_DIR_NAME = "migration_journal"

    fun saveJournal(journalFile: File, journal: MigrationJournal) {
        val parent = journalFile.parentFile ?: throw IllegalArgumentException("Journal file must have a parent")
        parent.mkdirs()

        val tempFile = File(parent, "${journalFile.name}.${UUID.randomUUID()}.tmp")
        try {
            val jsonString = serializeJournal(journal)
            FileOutputStream(tempFile).use { fos ->
                fos.write(jsonString.toByteArray(StandardCharsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fsync journal: ${e.message}")
                }
            }

            if (journalFile.exists()) {
                journalFile.delete()
            }
            if (!tempFile.renameTo(journalFile)) {
                tempFile.copyTo(journalFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }

    fun readJournal(journalFile: File): MigrationJournal? {
        if (!journalFile.exists() || journalFile.length() == 0L) {
            return null
        }
        return try {
            val jsonString = journalFile.readText(StandardCharsets.UTF_8)
            deserializeJournal(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse migration journal: ${e.message}")
            null
        }
    }

    fun serializeJournal(journal: MigrationJournal): String {
        val root = JSONObject()
        root.put("migrationId", journal.migrationId)
        root.put("startedAt", journal.startedAtMillis)
        root.put("updatedAt", journal.updatedAtMillis)
        root.put("status", journal.status.name)
        root.put("totalBlobs", journal.totalBlobs)
        root.put("completedBlobs", journal.completedBlobs)
        root.put("failedBlobs", journal.failedBlobs)
        if (journal.currentBlobId != null) root.put("currentBlobId", journal.currentBlobId)
        if (journal.errorMessage != null) root.put("errorMessage", journal.errorMessage)

        val blobsArray = JSONArray()
        for ((_, record) in journal.blobs) {
            val bObj = JSONObject()
            bObj.put("blobId", record.blobId)
            bObj.put("expectedSizeBytes", record.expectedSizeBytes)
            bObj.put("sourceSha256", record.sourceSha256)
            bObj.put("state", record.state.name)
            bObj.put("attempts", record.attempts)
            if (record.lastError != null) bObj.put("lastError", record.lastError)
            blobsArray.put(bObj)
        }
        root.put("blobs", blobsArray)

        return root.toString()
    }

    fun deserializeJournal(jsonString: String): MigrationJournal {
        val root = JSONObject(jsonString)
        val migrationId = root.getString("migrationId")
        val startedAt = root.getLong("startedAt")
        val updatedAt = root.optLong("updatedAt", System.currentTimeMillis())
        val status = MigrationStatus.valueOf(root.getString("status"))
        val totalBlobs = root.getInt("totalBlobs")
        val completedBlobs = root.optInt("completedBlobs", 0)
        val failedBlobs = root.optInt("failedBlobs", 0)
        val currentBlobId = if (root.has("currentBlobId") && !root.isNull("currentBlobId")) root.getString("currentBlobId") else null
        val errorMessage = if (root.has("errorMessage") && !root.isNull("errorMessage")) root.getString("errorMessage") else null

        val blobsMap = mutableMapOf<String, BlobJournalRecord>()
        val blobsArray = root.optJSONArray("blobs")
        if (blobsArray != null) {
            for (i in 0 until blobsArray.length()) {
                val bObj = blobsArray.getJSONObject(i)
                val id = bObj.getString("blobId")
                blobsMap[id] = BlobJournalRecord(
                    blobId = id,
                    expectedSizeBytes = bObj.getLong("expectedSizeBytes"),
                    sourceSha256 = bObj.getString("sourceSha256"),
                    state = BlobMigrationState.valueOf(bObj.getString("state")),
                    attempts = bObj.optInt("attempts", 0),
                    lastError = if (bObj.has("lastError") && !bObj.isNull("lastError")) bObj.getString("lastError") else null,
                )
            }
        }

        return MigrationJournal(
            migrationId = migrationId,
            startedAtMillis = startedAt,
            updatedAtMillis = updatedAt,
            status = status,
            totalBlobs = totalBlobs,
            completedBlobs = completedBlobs,
            failedBlobs = failedBlobs,
            currentBlobId = currentBlobId,
            blobs = blobsMap,
            errorMessage = errorMessage,
        )
    }
}
