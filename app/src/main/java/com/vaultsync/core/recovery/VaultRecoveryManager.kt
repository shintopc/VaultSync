package com.vaultsync.core.recovery

import android.content.Context
import android.os.Environment
import android.util.Log
import com.vaultsync.core.database.VaultDatabase
import com.vaultsync.core.manifest.VaultManifest
import com.vaultsync.core.manifest.VaultManifestManager
import com.vaultsync.core.manifest.VaultManifestSerializer
import com.vaultsync.core.security.VaultCredentialStore
import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.security.VaultRecoveryKeyHelper
import com.vaultsync.core.security.VaultSession
import com.vaultsync.core.storage.VaultStoragePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class VaultDiscoveryResult {
    object NotFound : VaultDiscoveryResult()

    data class Detected(
        val manifest: VaultManifest,
        val vaultDir: File,
        val blobCount: Int,
    ) : VaultDiscoveryResult()

    data class Corrupted(
        val vaultDir: File,
        val errorMessage: String,
    ) : VaultDiscoveryResult()

    data class PermissionRequired(
        val vaultDir: File,
        val message: String,
    ) : VaultDiscoveryResult()
}

data class VaultRecoveryResult(
    val isSuccess: Boolean,
    val restoredFilesCount: Int,
    val vaultId: String? = null,
    val errorMessage: String? = null,
)

object VaultRecoveryManager {
    private const val TAG = "VaultRecoveryManager"

    fun detectExistingVault(
        context: Context,
        explicitDir: File? = null,
    ): VaultDiscoveryResult {
        val vaultDir = explicitDir ?: run {
            val prefs = VaultStoragePreferences(context)
            if (prefs.isSharedStorageEnabled) {
                prefs.getSharedStorageDirectory()
            } else {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                File(docsDir, VaultStoragePreferences.DEFAULT_FOLDER_NAME)
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return VaultDiscoveryResult.PermissionRequired(vaultDir, "Storage permission required to read Documents/VaultSync")
        }

        if (!vaultDir.exists() || !vaultDir.isDirectory) {
            return VaultDiscoveryResult.NotFound
        }

        val manifestFile = File(vaultDir, VaultManifest.MANIFEST_FILE_NAME)
        if (!manifestFile.exists() || manifestFile.length() < 32) {
            // Check if blobs exist without manifest
            val blobsDir = File(vaultDir, "blobs")
            val blobCount = blobsDir.listFiles { _, name -> name.endsWith(".vsf") }?.size ?: 0
            return if (blobCount > 0) {
                VaultDiscoveryResult.Corrupted(vaultDir, "Found $blobCount encrypted blobs, but vault_manifest.vsm is missing")
            } else {
                VaultDiscoveryResult.NotFound
            }
        }

        return try {
            val manifest = VaultManifestManager.readManifest(manifestFile)
                ?: return VaultDiscoveryResult.Corrupted(vaultDir, "Failed to parse vault_manifest.vsm")
            val blobsDir = File(vaultDir, "blobs")
            val blobCount = blobsDir.listFiles { _, name -> name.endsWith(".vsf") }?.size ?: 0
            VaultDiscoveryResult.Detected(
                manifest = manifest,
                vaultDir = vaultDir,
                blobCount = blobCount,
            )
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("Permission denied", ignoreCase = true) || msg.contains("EACCES", ignoreCase = true)) {
                VaultDiscoveryResult.PermissionRequired(vaultDir, "Storage permission required to read Documents/VaultSync")
            } else {
                VaultDiscoveryResult.Corrupted(vaultDir, e.message ?: "Unknown error reading manifest")
            }
        }
    }

    suspend fun recoverVault(
        context: Context,
        database: VaultDatabase,
        credentialStore: VaultCredentialStore,
        session: VaultSession,
        manifest: VaultManifest,
        vaultDir: File,
        credentialInput: String,
        isRecoveryKey: Boolean,
    ): VaultRecoveryResult = withContext(Dispatchers.IO) {
        val cleanCredential = if (isRecoveryKey) {
            VaultRecoveryKeyHelper.normalizeKey(credentialInput)
        } else {
            credentialInput.trim()
        }

        if (cleanCredential.isBlank()) {
            return@withContext VaultRecoveryResult(false, 0, null, "Credential cannot be blank")
        }

        val wrapped = if (isRecoveryKey) {
            manifest.wrappedVmkWithRecoveryKey
                ?: return@withContext VaultRecoveryResult(false, 0, null, "Manifest has no recovery key registered")
        } else {
            manifest.wrappedVmkWithPin
        }

        var vmk: ByteArray? = null
        try {
            // 1. Unwrap the VMK
            vmk = VaultKeyManager.unwrapVaultMasterKey(wrapped, cleanCredential.toCharArray())

            // 2. Decrypt the catalog
            val catalog = VaultManifestSerializer.decryptCatalog(
                encryptedCatalog = manifest.encryptedCatalog,
                nonce = manifest.catalogNonce,
                vmk = vmk,
                vaultId = manifest.vaultId,
                version = manifest.formatVersion,
            )

            // 3. Reconstruct Room database
            val blobsDir = File(vaultDir, "blobs")
            val restoredCount = VaultManifestManager.reconstructDatabaseFromCatalog(
                database = database,
                catalog = catalog,
                existingBlobChecker = { blobId -> File(blobsDir, "$blobId.vsf").exists() },
            )

            // 4. Update CredentialStore so app lock and normal unlock continue working
            credentialStore.saveCredentials(manifest.wrappedVmkWithPin)
            if (manifest.wrappedVmkWithRecoveryKey != null) {
                credentialStore.saveRecoveryCredentials(manifest.wrappedVmkWithRecoveryKey)
            }

            // 5. Update Storage Preferences to point to the shared vault directory
            VaultStoragePreferences(context).configureSharedStorage(vaultDir.absolutePath)

            // 6. Unlock the session with the recovered VMK
            session.unlock(vmk)

            Log.i(TAG, "Successfully recovered vault ${manifest.vaultId} with $restoredCount entries")
            VaultRecoveryResult(
                isSuccess = true,
                restoredFilesCount = restoredCount,
                vaultId = manifest.vaultId,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Recovery authentication failed: ${e.message}")
            VaultRecoveryResult(false, 0, null, "Incorrect ${if (isRecoveryKey) "recovery key" else "PIN / Password"}")
        } catch (e: Exception) {
            Log.e(TAG, "Recovery error: ${e.message}", e)
            VaultRecoveryResult(false, 0, null, "Recovery failed: ${e.message}")
        } finally {
            vmk?.let { VaultKeyManager.zeroize(it) }
        }
    }
}
