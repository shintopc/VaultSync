package com.vaultsync.core.manifest

import com.vaultsync.core.database.VaultEntryEntity
import com.vaultsync.core.database.VaultFolderEntity
import com.vaultsync.core.security.CryptoConstants
import com.vaultsync.core.security.KeyDerivation
import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.security.WrappedVmk
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, versioned manifest describing a complete vault.
 * Stored at the vault root as 'vault_manifest.vsm'.
 *
 * Security Architecture:
 * 1. The outer header contains the wrapped Vault Master Key (VMK) records for both PIN and Recovery Key.
 *    These are protected via PBKDF2 (120,000 iterations) and AES-256-GCM.
 * 2. The catalog (filenames, categories, folder structure, timestamps) is encrypted with the VMK
 *    using AES-256-GCM, preventing metadata disclosure on shared storage.
 * 3. Both PIN/Password and 128-bit Recovery Key can independently unwrap the same VMK.
 * 4. Zero circular dependencies: the unwrap parameters are accessible before the VMK is unlocked.
 */
data class VaultManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val vaultId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val wrappedVmkWithPin: WrappedVmk,
    val wrappedVmkWithRecoveryKey: WrappedVmk?,
    val catalogNonce: ByteArray,
    val encryptedCatalog: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultManifest) return false
        return formatVersion == other.formatVersion &&
            vaultId == other.vaultId &&
            createdAtMillis == other.createdAtMillis &&
            updatedAtMillis == other.updatedAtMillis &&
            wrappedVmkWithPin == other.wrappedVmkWithPin &&
            wrappedVmkWithRecoveryKey == other.wrappedVmkWithRecoveryKey &&
            catalogNonce.contentEquals(other.catalogNonce) &&
            encryptedCatalog.contentEquals(other.encryptedCatalog)
    }

    override fun hashCode(): Int {
        var result = formatVersion
        result = 31 * result + vaultId.hashCode()
        result = 31 * result + createdAtMillis.hashCode()
        result = 31 * result + updatedAtMillis.hashCode()
        result = 31 * result + wrappedVmkWithPin.hashCode()
        result = 31 * result + (wrappedVmkWithRecoveryKey?.hashCode() ?: 0)
        result = 31 * result + catalogNonce.contentHashCode()
        result = 31 * result + encryptedCatalog.contentHashCode()
        return result
    }

    companion object {
        const val FORMAT_VERSION = 1
        val MAGIC_BYTES = byteArrayOf('V'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'F'.code.toByte())
        const val MANIFEST_FILE_NAME = "vault_manifest.vsm"
    }
}

data class VaultCatalog(
    val folders: List<CatalogFolder>,
    val entries: List<CatalogEntry>,
    val exportedAtMillis: Long = System.currentTimeMillis(),
)

data class CatalogFolder(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toEntity(): VaultFolderEntity = VaultFolderEntity(
        id = id,
        name = name,
        parentId = parentId,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

data class CatalogEntry(
    val id: String,
    val folderId: String?,
    val originalFileName: String,
    val category: String,
    val mimeType: String?,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val createdAtMillis: Long,
    val dateModifiedMillis: Long,
) {
    fun toEntity(): VaultEntryEntity = VaultEntryEntity(
        id = id,
        folderId = folderId,
        originalFileName = originalFileName,
        category = category,
        mimeType = mimeType,
        originalSizeBytes = originalSizeBytes,
        encryptedSizeBytes = encryptedSizeBytes,
        createdAtMillis = createdAtMillis,
        dateModifiedMillis = dateModifiedMillis,
        isInUse = false,
    )
}

object VaultManifestSerializer {

    fun serializeCatalog(catalog: VaultCatalog): String {
        val root = JSONObject()
        root.put("version", VaultManifest.FORMAT_VERSION)
        root.put("exportedAt", catalog.exportedAtMillis)

        val foldersArray = JSONArray()
        for (f in catalog.folders) {
            val fObj = JSONObject()
            fObj.put("id", f.id)
            fObj.put("name", f.name)
            if (f.parentId != null) fObj.put("parentId", f.parentId) else fObj.put("parentId", JSONObject.NULL)
            fObj.put("createdAt", f.createdAtMillis)
            fObj.put("updatedAt", f.updatedAtMillis)
            foldersArray.put(fObj)
        }
        root.put("folders", foldersArray)

        val entriesArray = JSONArray()
        for (e in catalog.entries) {
            val eObj = JSONObject()
            eObj.put("id", e.id)
            if (e.folderId != null) eObj.put("folderId", e.folderId) else eObj.put("folderId", JSONObject.NULL)
            eObj.put("originalFileName", e.originalFileName)
            eObj.put("category", e.category)
            if (e.mimeType != null) eObj.put("mimeType", e.mimeType) else eObj.put("mimeType", JSONObject.NULL)
            eObj.put("originalSizeBytes", e.originalSizeBytes)
            eObj.put("encryptedSizeBytes", e.encryptedSizeBytes)
            eObj.put("createdAt", e.createdAtMillis)
            eObj.put("dateModified", e.dateModifiedMillis)
            entriesArray.put(eObj)
        }
        root.put("entries", entriesArray)

        return root.toString()
    }

    fun deserializeCatalog(jsonString: String): VaultCatalog {
        val root = JSONObject(jsonString)
        val exportedAt = root.optLong("exportedAt", System.currentTimeMillis())

        val foldersList = mutableListOf<CatalogFolder>()
        val foldersArray = root.optJSONArray("folders")
        if (foldersArray != null) {
            for (i in 0 until foldersArray.length()) {
                val fObj = foldersArray.getJSONObject(i)
                foldersList.add(
                    CatalogFolder(
                        id = fObj.getString("id"),
                        name = fObj.getString("name"),
                        parentId = if (fObj.isNull("parentId")) null else fObj.getString("parentId"),
                        createdAtMillis = fObj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAtMillis = fObj.optLong("updatedAt", System.currentTimeMillis()),
                    ),
                )
            }
        }

        val entriesList = mutableListOf<CatalogEntry>()
        val entriesArray = root.optJSONArray("entries")
        if (entriesArray != null) {
            for (i in 0 until entriesArray.length()) {
                val eObj = entriesArray.getJSONObject(i)
                entriesList.add(
                    CatalogEntry(
                        id = eObj.getString("id"),
                        folderId = if (eObj.isNull("folderId")) null else eObj.getString("folderId"),
                        originalFileName = eObj.getString("originalFileName"),
                        category = eObj.getString("category"),
                        mimeType = if (eObj.isNull("mimeType")) null else eObj.getString("mimeType"),
                        originalSizeBytes = eObj.getLong("originalSizeBytes"),
                        encryptedSizeBytes = eObj.getLong("encryptedSizeBytes"),
                        createdAtMillis = eObj.optLong("createdAt", System.currentTimeMillis()),
                        dateModifiedMillis = eObj.optLong("dateModified", System.currentTimeMillis()),
                    ),
                )
            }
        }

        return VaultCatalog(
            folders = foldersList,
            entries = entriesList,
            exportedAtMillis = exportedAt,
        )
    }

    fun encryptCatalog(
        catalog: VaultCatalog,
        vmk: ByteArray,
        vaultId: String,
        version: Int = VaultManifest.FORMAT_VERSION,
    ): Pair<ByteArray, ByteArray> {
        val jsonBytes = serializeCatalog(catalog).toByteArray(StandardCharsets.UTF_8)
        val nonce = KeyDerivation.generateRandomBytes(CryptoConstants.GCM_NONCE_SIZE_BYTES)
        val aad = createCatalogAad(version, vaultId)

        val keySpec = SecretKeySpec(vmk, CryptoConstants.KEY_ALGORITHM)
        val cipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(jsonBytes)

        return Pair(nonce, ciphertext)
    }

    fun decryptCatalog(
        encryptedCatalog: ByteArray,
        nonce: ByteArray,
        vmk: ByteArray,
        vaultId: String,
        version: Int = VaultManifest.FORMAT_VERSION,
    ): VaultCatalog {
        val aad = createCatalogAad(version, vaultId)
        val keySpec = SecretKeySpec(vmk, CryptoConstants.KEY_ALGORITHM)
        val cipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, nonce))
        cipher.updateAAD(aad)
        val plaintext = try {
            cipher.doFinal(encryptedCatalog)
        } catch (e: Exception) {
            throw SecurityException("Failed to decrypt catalog: integrity check failed or wrong VMK", e)
        }
        val jsonString = String(plaintext, StandardCharsets.UTF_8)
        return deserializeCatalog(jsonString)
    }

    private fun createCatalogAad(version: Int, vaultId: String): ByteArray {
        val idBytes = vaultId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + idBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VaultManifest.MAGIC_BYTES)
        buffer.putInt(version)
        buffer.put(idBytes)
        return buffer.array()
    }

    fun serializeManifest(manifest: VaultManifest): ByteArray {
        val idBytes = manifest.vaultId.toByteArray(StandardCharsets.UTF_8)
        val pinWrapped = manifest.wrappedVmkWithPin
        val recWrapped = manifest.wrappedVmkWithRecoveryKey

        // Estimate size
        val size = 4 + // Magic
            4 + // Version
            4 + idBytes.size + // VaultId
            8 + 8 + // Timestamps
            // PIN wrapped VMK
            (4 + pinWrapped.salt.size + 4 + pinWrapped.nonce.size + 4 + pinWrapped.encryptedKey.size + 4 + pinWrapped.credentialVerifier.size) +
            1 + // Has recovery key flag
            (if (recWrapped != null) (4 + recWrapped.salt.size + 4 + recWrapped.nonce.size + 4 + recWrapped.encryptedKey.size + 4 + recWrapped.credentialVerifier.size) else 0) +
            4 + manifest.catalogNonce.size +
            4 + manifest.encryptedCatalog.size

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VaultManifest.MAGIC_BYTES)
        buffer.putInt(manifest.formatVersion)
        buffer.putInt(idBytes.size)
        buffer.put(idBytes)
        buffer.putLong(manifest.createdAtMillis)
        buffer.putLong(manifest.updatedAtMillis)

        // Write PIN wrapped VMK
        writeWrappedVmk(buffer, pinWrapped)

        // Write Recovery wrapped VMK
        if (recWrapped != null) {
            buffer.put(1.toByte())
            writeWrappedVmk(buffer, recWrapped)
        } else {
            buffer.put(0.toByte())
        }

        // Write Catalog Nonce & Encrypted Catalog
        buffer.putInt(manifest.catalogNonce.size)
        buffer.put(manifest.catalogNonce)
        buffer.putInt(manifest.encryptedCatalog.size)
        buffer.put(manifest.encryptedCatalog)

        return buffer.array()
    }

    fun deserializeManifest(bytes: ByteArray): VaultManifest {
        require(bytes.size >= 32) { "Manifest file too small: ${bytes.size} bytes" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(VaultManifest.MAGIC_BYTES)) {
            throw SecurityException("Invalid vault manifest magic identifier")
        }

        val version = buffer.int
        if (version != VaultManifest.FORMAT_VERSION) {
            throw SecurityException("Unsupported vault manifest format version: $version")
        }

        val idLen = buffer.int
        val idBytes = ByteArray(idLen)
        buffer.get(idBytes)
        val vaultId = String(idBytes, StandardCharsets.UTF_8)

        val createdAt = buffer.long
        val updatedAt = buffer.long

        val pinWrapped = readWrappedVmk(buffer)

        val hasRec = buffer.get() == 1.toByte()
        val recWrapped = if (hasRec) readWrappedVmk(buffer) else null

        val nonceLen = buffer.int
        val nonce = ByteArray(nonceLen)
        buffer.get(nonce)

        val catLen = buffer.int
        val catBytes = ByteArray(catLen)
        buffer.get(catBytes)

        return VaultManifest(
            formatVersion = version,
            vaultId = vaultId,
            createdAtMillis = createdAt,
            updatedAtMillis = updatedAt,
            wrappedVmkWithPin = pinWrapped,
            wrappedVmkWithRecoveryKey = recWrapped,
            catalogNonce = nonce,
            encryptedCatalog = catBytes,
        )
    }

    private fun writeWrappedVmk(buffer: ByteBuffer, wrapped: WrappedVmk) {
        buffer.putInt(wrapped.salt.size)
        buffer.put(wrapped.salt)
        buffer.putInt(wrapped.nonce.size)
        buffer.put(wrapped.nonce)
        buffer.putInt(wrapped.encryptedKey.size)
        buffer.put(wrapped.encryptedKey)
        buffer.putInt(wrapped.credentialVerifier.size)
        buffer.put(wrapped.credentialVerifier)
    }

    private fun readWrappedVmk(buffer: ByteBuffer): WrappedVmk {
        val saltLen = buffer.int
        val salt = ByteArray(saltLen)
        buffer.get(salt)

        val nonceLen = buffer.int
        val nonce = ByteArray(nonceLen)
        buffer.get(nonce)

        val encLen = buffer.int
        val enc = ByteArray(encLen)
        buffer.get(enc)

        val verLen = buffer.int
        val ver = ByteArray(verLen)
        buffer.get(ver)

        return WrappedVmk(
            salt = salt,
            nonce = nonce,
            encryptedKey = enc,
            credentialVerifier = ver,
        )
    }
}
