package com.vaultsync.core.vault

import android.content.Context
import com.vaultsync.core.database.VaultDatabase
import com.vaultsync.core.security.VaultCredentialStore
import com.vaultsync.core.security.VaultSession
import com.vaultsync.core.storage.VaultPreviewManager
import com.vaultsync.core.storage.VaultStorageManager

object VaultContainer {
    @Volatile
    private var instance: VaultContainerInstance? = null

    fun get(context: Context): VaultContainerInstance {
        return instance ?: synchronized(this) {
            instance ?: VaultContainerInstance(context.applicationContext).also { instance = it }
        }
    }
}

class VaultContainerInstance(context: Context) {
    val database: VaultDatabase = VaultDatabase.create(context)
    val storageManager: VaultStorageManager = VaultStorageManager(context)
    val repository: VaultRepository = AndroidVaultRepository(database, storageManager)
    val session: VaultSession = VaultSession()
    val credentialStore: VaultCredentialStore = VaultCredentialStore(context)
    val previewManager: VaultPreviewManager = VaultPreviewManager(context, repository, storageManager)
    val thumbnailManager: com.vaultsync.core.storage.VaultThumbnailManager = com.vaultsync.core.storage.VaultThumbnailManager(context, storageManager)
}
