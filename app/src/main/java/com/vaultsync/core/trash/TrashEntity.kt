package com.vaultsync.core.trash

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trash_entries")
data class TrashEntity(
    @PrimaryKey
    val id: String,
    val displayName: String,
    val originalUriString: String?,
    val originalPath: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val localFilePath: String,
    val deletedAt: Long,
    val autoDeleteAt: Long,
)
