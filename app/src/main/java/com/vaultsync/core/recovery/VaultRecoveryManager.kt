package com.vaultsync.core.recovery

import android.content.Context
import android.os.Environment
import android.util.Log
import com.vaultsync.core.database.VaultDatabase
import com.vaultsync.core.database.VaultEntryEntity
import com.vaultsync.core.database.VaultFolderEntity
import com.vaultsync.core.manifest.VaultManifest
import com.vaultsync.core.manifest.VaultManifestManager
import com.vaultsync.core.manifest.VaultManifestSerializer
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.security.VaultCredentialStore
import com.vaultsync.core.security.VaultFileDecryptor
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
        val blobCount: Int = 0,
        val canDeepScan: Boolean = false,
        val manifestHeaderCandidate: VaultManifest? = null,
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

        val hasAllFilesAccess = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val canReadDirectly = try { vaultDir.exists() && vaultDir.canRead() } catch (_: Exception) { false }

        if (!hasAllFilesAccess && !canReadDirectly) {
            return VaultDiscoveryResult.PermissionRequired(vaultDir, "Storage permission required to read ${vaultDir.name}")
        }

        if (!vaultDir.exists() || !vaultDir.isDirectory) {
            return VaultDiscoveryResult.NotFound
        }

        val manifestFile = File(vaultDir, VaultManifest.MANIFEST_FILE_NAME)
        val backupFile = File(vaultDir, "${VaultManifest.MANIFEST_FILE_NAME}.bak")
        val effectiveManifestFile = when {
            manifestFile.exists() && manifestFile.length() >= 32 -> manifestFile
            backupFile.exists() && backupFile.length() >= 32 -> backupFile
            else -> null
        }

        val blobsDir = File(vaultDir, "blobs")
        val blobCount = blobsDir.listFiles { _, name -> name.endsWith(".vsf") }?.size ?: 0

        if (effectiveManifestFile == null) {
            return if (blobCount > 0) {
                VaultDiscoveryResult.Corrupted(
                    vaultDir = vaultDir,
                    errorMessage = "Found $blobCount encrypted files, but vault_manifest.vsm is missing.",
                    blobCount = blobCount,
                    canDeepScan = true,
                    manifestHeaderCandidate = null,
                )
            } else {
                VaultDiscoveryResult.NotFound
            }
        }

        return try {
            val manifest = VaultManifestManager.readManifest(effectiveManifestFile)
            if (manifest == null) {
                VaultDiscoveryResult.Corrupted(
                    vaultDir = vaultDir,
                    errorMessage = "Failed to parse vault_manifest.vsm.",
                    blobCount = blobCount,
                    canDeepScan = blobCount > 0,
                    manifestHeaderCandidate = null,
                )
            } else {
                VaultDiscoveryResult.Detected(
                    manifest = manifest,
                    vaultDir = vaultDir,
                    blobCount = blobCount,
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("Permission denied", ignoreCase = true) || msg.contains("EACCES", ignoreCase = true)) {
                VaultDiscoveryResult.PermissionRequired(vaultDir, "Storage permission required to read Documents/VaultSync")
            } else {
                VaultDiscoveryResult.Corrupted(
                    vaultDir = vaultDir,
                    errorMessage = e.message ?: "Unknown error reading manifest",
                    blobCount = blobCount,
                    canDeepScan = blobCount > 0,
                )
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

            // 2. Decrypt the catalog (fallback to Deep Scan if corrupted)
            var restoredCount = 0
            val catalog = try {
                VaultManifestSerializer.decryptCatalog(
                    encryptedCatalog = manifest.encryptedCatalog,
                    nonce = manifest.catalogNonce,
                    vmk = vmk,
                    vaultId = manifest.vaultId,
                    version = manifest.formatVersion,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Catalog decryption failed, falling back to deep blob salvage: ${e.message}")
                null
            }

            // 3. Reconstruct Room database
            val blobsDir = File(vaultDir, "blobs")
            if (catalog != null) {
                restoredCount = VaultManifestManager.reconstructDatabaseFromCatalog(
                    database = database,
                    catalog = catalog,
                    existingBlobChecker = { blobId -> File(blobsDir, "$blobId.vsf").exists() },
                )
            }

            // Also salvage any uncataloged blobs on disk
            val salvagedCount = salvageUncatalogedBlobs(database, blobsDir, vmk)
            val totalRestored = restoredCount + salvagedCount

            // 4. Update CredentialStore so app lock and normal unlock continue working
            credentialStore.saveCredentials(manifest.wrappedVmkWithPin)
            if (manifest.wrappedVmkWithRecoveryKey != null) {
                credentialStore.saveRecoveryCredentials(manifest.wrappedVmkWithRecoveryKey)
            }

            // 5. Update Storage Preferences to point to the shared vault directory
            VaultStoragePreferences(context).configureSharedStorage(vaultDir.absolutePath)

            // 6. Unlock the session with the recovered VMK
            session.unlock(vmk)

            Log.i(TAG, "Successfully recovered vault ${manifest.vaultId} with $totalRestored entries ($restoredCount from catalog, $salvagedCount salvaged)")
            VaultRecoveryResult(
                isSuccess = true,
                restoredFilesCount = totalRestored,
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

    fun detectMimeType(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return Pair("image/jpeg", "jpg")
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() && bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()) {
            return Pair("image/png", "png")
        }
        if (bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) {
            return Pair("image/gif", "gif")
        }
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) {
            return Pair("image/webp", "webp")
        }
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x41.toByte() && bytes[10] == 0x56.toByte() && bytes[11] == 0x45.toByte()) {
            return Pair("audio/wav", "wav")
        }
        if (bytes.size >= 12) {
            val hasFtyp = (4..8).any { i ->
                bytes.size >= i + 4 &&
                    bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() &&
                    bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte()
            }
            if (hasFtyp) {
                return Pair("video/mp4", "mp4")
            }
        }
        if (bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) {
            return Pair("video/webm", "mkv")
        }
        if (bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            return Pair("application/pdf", "pdf")
        }
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return Pair("audio/mpeg", "mp3")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) {
            return Pair("audio/mpeg", "mp3")
        }
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) {
            return Pair("application/zip", "zip")
        }
        return Pair("application/octet-stream", "bin")
    }

    suspend fun salvageUncatalogedBlobs(
        database: VaultDatabase,
        blobsDir: File,
        vmk: ByteArray,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        if (!blobsDir.exists() || !blobsDir.isDirectory) return@withContext 0

        val blobFiles = blobsDir.listFiles { _, name -> name.endsWith(".vsf") } ?: return@withContext 0
        if (blobFiles.isEmpty()) return@withContext 0

        val entryDao = database.entryDao()
        val folderDao = database.folderDao()
        val registeredIds = entryDao.getAllEntryIds().toSet()

        val uncataloged = blobFiles.filter { it.name.substringBeforeLast(".vsf") !in registeredIds }
        if (uncataloged.isEmpty()) return@withContext 0

        val now = System.currentTimeMillis()
        val recoveredFolderId = "folder_recovered_${now}"
        var folderCreated = false
        var salvagedCount = 0

        for ((index, blobFile) in uncataloged.withIndex()) {
            onProgress?.invoke(index + 1, uncataloged.size)
            val blobId = blobFile.name.substringBeforeLast(".vsf")

            try {
                val header = VaultFileDecryptor.readHeader(blobFile)
                val dek = try {
                    VaultFileDecryptor.unwrapDek(header, vmk)
                } catch (_: Exception) {
                    continue
                }
                VaultKeyManager.zeroize(dek)

                val firstBytes = VaultFileDecryptor.readSamplePlaintext(blobFile, vmk, 1024)
                val (mimeType, extension) = detectMimeType(firstBytes)
                val category = VaultCategory.fromMimeOrExtension(mimeType, "file.$extension")

                if (!folderCreated) {
                    val existingFolder = folderDao.getById(recoveredFolderId)
                    if (existingFolder == null) {
                        folderDao.insert(
                            VaultFolderEntity(
                                id = recoveredFolderId,
                                name = "Recovered Files",
                                parentId = null,
                                createdAtMillis = now,
                                updatedAtMillis = now,
                            )
                        )
                    }
                    folderCreated = true
                }

                val entity = VaultEntryEntity(
                    id = blobId,
                    folderId = recoveredFolderId,
                    originalFileName = "Recovered_${blobId.take(8)}.$extension",
                    category = category.name,
                    mimeType = mimeType,
                    originalSizeBytes = if (header.originalFileSize > 0) header.originalFileSize else blobFile.length(),
                    encryptedSizeBytes = blobFile.length(),
                    createdAtMillis = now,
                    dateModifiedMillis = now,
                    isInUse = false,
                )
                entryDao.insert(entity)
                salvagedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to salvage blob ${blobFile.name}: ${e.message}")
            }
        }
        salvagedCount
    }

    suspend fun recoverVaultWithDeepScan(
        context: Context,
        database: VaultDatabase,
        credentialStore: VaultCredentialStore,
        session: VaultSession,
        vaultDir: File,
        manifestCandidate: VaultManifest?,
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

        val wrapped = if (manifestCandidate != null) {
            if (isRecoveryKey) manifestCandidate.wrappedVmkWithRecoveryKey else manifestCandidate.wrappedVmkWithPin
        } else {
            if (isRecoveryKey) credentialStore.getRecoveryWrappedVmk() else credentialStore.getWrappedVmk()
        }

        var vmk: ByteArray? = null
        try {
            if (wrapped != null) {
                vmk = VaultKeyManager.unwrapVaultMasterKey(wrapped, cleanCredential.toCharArray())
            } else {
                return@withContext VaultRecoveryResult(false, 0, null, "No vault encryption credentials found to decrypt files")
            }

            val blobsDir = File(vaultDir, "blobs")
            val salvagedCount = salvageUncatalogedBlobs(database, blobsDir, vmk)

            if (salvagedCount == 0) {
                return@withContext VaultRecoveryResult(false, 0, null, "No salvageable files could be decrypted with this credential.")
            }

            credentialStore.saveCredentials(manifestCandidate?.wrappedVmkWithPin ?: wrapped)
            if (manifestCandidate?.wrappedVmkWithRecoveryKey != null) {
                credentialStore.saveRecoveryCredentials(manifestCandidate.wrappedVmkWithRecoveryKey)
            }

            VaultStoragePreferences(context).configureSharedStorage(vaultDir.absolutePath)
            session.unlock(vmk)

            // Immediately create and sync the reconstructed manifest
            val newManifest = VaultManifestManager.createManifestFromCurrentVault(
                database = database,
                credentialStore = credentialStore,
                vmk = vmk,
                existingVaultId = manifestCandidate?.vaultId,
            )
            VaultManifestManager.writeManifest(
                File(vaultDir, VaultManifest.MANIFEST_FILE_NAME),
                newManifest,
            )

            VaultRecoveryResult(
                isSuccess = true,
                restoredFilesCount = salvagedCount,
                vaultId = newManifest.vaultId,
            )
        } catch (e: SecurityException) {
            VaultRecoveryResult(false, 0, null, "Incorrect PIN / Password")
        } catch (e: Exception) {
            VaultRecoveryResult(false, 0, null, "Deep Scan failed: ${e.message}")
        } finally {
            vmk?.let { VaultKeyManager.zeroize(it) }
        }
    }
}
