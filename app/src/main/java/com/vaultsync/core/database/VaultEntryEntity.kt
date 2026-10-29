package com.vaultsync.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vault_entries",
    foreignKeys = [
        ForeignKey(
            entity = VaultFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("folder_id"),
        Index("category"),
    ],
)
data class VaultEntryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "folder_id")
    val folderId: String?,
    @ColumnInfo(name = "original_file_name")
    val originalFileName: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    @ColumnInfo(name = "original_size_bytes")
    val originalSizeBytes: Long,
    @ColumnInfo(name = "encrypted_size_bytes")
    val encryptedSizeBytes: Long,
    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,
    @ColumnInfo(name = "date_modified")
    val dateModifiedMillis: Long,
    @ColumnInfo(name = "is_in_use")
    val isInUse: Boolean = false,
)
