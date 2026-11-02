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
    val isTrash: Boolean = false,
    val deletedAtMillis: Long? = null,
    val contentHash: String? = null,
) {
    val daysRemainingInTrash: Int
        get() {
            if (!isTrash || deletedAtMillis == null) return 30
            val elapsed = System.currentTimeMillis() - deletedAtMillis
            val thirtyDaysMs = 30L * 24L * 60L * 60L * 1000L
            val remainingMs = (thirtyDaysMs - elapsed).coerceAtLeast(0L)
            return (remainingMs / (24L * 60L * 60L * 1000L)).toInt()
        }
}
