package com.vaultsync.core.migration

import org.json.JSONArray
import org.json.JSONObject

enum class MigrationStatus {
    NOT_STARTED,
    IN_PROGRESS,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class BlobMigrationState {
    PENDING,
    COPYING,
    VERIFIED_AT_DESTINATION,
    SOURCE_DELETED,
    COMPLETED,
    FAILED,
}

data class MigrationJournal(
    val migrationId: String,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val status: MigrationStatus,
    val totalBlobs: Int,
    val completedBlobs: Int,
    val failedBlobs: Int,
    val currentBlobId: String? = null,
    val blobs: Map<String, BlobJournalRecord> = emptyMap(),
    val errorMessage: String? = null,
)

data class BlobJournalRecord(
    val blobId: String,
    val expectedSizeBytes: Long,
    val sourceSha256: String,
    val state: BlobMigrationState,
    val attempts: Int = 0,
    val lastError: String? = null,
)

data class MigrationProgress(
    val currentFileIndex: Int,
    val totalFiles: Int,
    val currentFileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: MigrationStatus,
    val message: String? = null,
)

data class MigrationResult(
    val isSuccess: Boolean,
    val migratedCount: Int,
    val totalCount: Int,
    val errorMessage: String? = null,
)
