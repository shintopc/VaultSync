package com.vaultsync.core.vault

import android.content.Context
import android.util.Log
import com.vaultsync.core.database.VaultDatabase
import com.vaultsync.core.manifest.VaultManifestManager
import com.vaultsync.core.security.VaultCredentialStore
import com.vaultsync.core.security.VaultSession
import com.vaultsync.core.storage.VaultPreviewManager
import com.vaultsync.core.storage.VaultStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /**
     * Atomically serializes and synchronizes the current vault state (folders, entries, wrapped keys)
     * to vault_manifest.vsm with an automated .bak backup.
     * Returns true if successfully written, or false if the vault is locked or write failed.
     */
    suspend fun syncManifest(): Boolean = withContext(Dispatchers.IO) {
        if (!session.isUnlocked.value) {
            return@withContext false
        }
        try {
            session.withVmkSuspend { vmk ->
                val manifest = VaultManifestManager.createManifestFromCurrentVault(
                    database = database,
                    credentialStore = credentialStore,
                    vmk = vmk,
                )
                VaultManifestManager.writeManifest(
                    storageManager.manifestFile,
                    manifest,
                )
            }
            true
        } catch (e: Exception) {
            Log.w("VaultContainerInstance", "Failed to sync manifest: ${e.message}")
            false
        }
    }
}
