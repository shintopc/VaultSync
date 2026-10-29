package com.vaultsync.core.model

data class VaultFolder(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val itemCount: Int = 0,
    val subfolderCount: Int = 0,
)

data class VaultEntry(
    val id: String,
    val folderId: String?,
    val originalFileName: String,
    val category: VaultCategory,
    val mimeType: String?,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val createdAtMillis: Long,
    val dateModifiedMillis: Long,
    val isInUse: Boolean = false,
)
