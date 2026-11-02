package com.vaultsync.core.manifest

import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.security.VaultRecoveryKeyHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class VaultManifestTest {

    @Test
    fun testSerializationAndDeserialization() {
        val vmk = VaultKeyManager.generateVaultMasterKey()
        val pin = "849201".toCharArray()
        val recoveryKey = VaultRecoveryKeyHelper.generateRecoveryKey()
        val recoveryChars = VaultRecoveryKeyHelper.normalizeKey(recoveryKey).toCharArray()

        val pinWrapped = VaultKeyManager.wrapVaultMasterKey(vmk, pin)
        val recoveryWrapped = VaultKeyManager.wrapVaultMasterKey(vmk, recoveryChars)

        val folder1 = CatalogFolder(
            id = UUID.randomUUID().toString(),
            name = "Vacation 2026",
            parentId = null,
            createdAtMillis = 1000L,
            updatedAtMillis = 2000L,
        )
        val folder2 = CatalogFolder(
            id = UUID.randomUUID().toString(),
            name = "Subfolder",
            parentId = folder1.id,
            createdAtMillis = 3000L,
            updatedAtMillis = 4000L,
        )

        val entry1 = CatalogEntry(
            id = UUID.randomUUID().toString(),
            folderId = folder1.id,
            originalFileName = "family_beach.jpg",
            category = "Images",
            mimeType = "image/jpeg",
            originalSizeBytes = 5242880L,
            encryptedSizeBytes = 5243100L,
            createdAtMillis = 5000L,
            dateModifiedMillis = 6000L,
        )

        val entry2 = CatalogEntry(
            id = UUID.randomUUID().toString(),
            folderId = null,
            originalFileName = "video_drone.mp4",
            category = "Videos",
            mimeType = "video/mp4",
            originalSizeBytes = 104857600L,
            encryptedSizeBytes = 104860000L,
            createdAtMillis = 7000L,
            dateModifiedMillis = 8000L,
        )

        val catalog = VaultCatalog(
            folders = listOf(folder1, folder2),
            entries = listOf(entry1, entry2),
        )

        val vaultId = UUID.randomUUID().toString()
        val (nonce, ciphertext) = VaultManifestSerializer.encryptCatalog(catalog, vmk, vaultId)

        val manifest = VaultManifest(
            vaultId = vaultId,
            createdAtMillis = 1000L,
            updatedAtMillis = 2000L,
            wrappedVmkWithPin = pinWrapped,
            wrappedVmkWithRecoveryKey = recoveryWrapped,
            catalogNonce = nonce,
            encryptedCatalog = ciphertext,
        )

        val manifestBytes = VaultManifestSerializer.serializeManifest(manifest)
        assertTrue("Manifest bytes must be non-empty", manifestBytes.isNotEmpty())

        val deserialized = VaultManifestSerializer.deserializeManifest(manifestBytes)

        assertEquals(1, deserialized.formatVersion)
        assertEquals(manifest.vaultId, deserialized.vaultId)
        assertNotNull(deserialized.wrappedVmkWithPin)
        assertNotNull(deserialized.wrappedVmkWithRecoveryKey)

        // Verify key wrapping metadata is accessible BEFORE decrypting catalog
        val unwrappedWithPin = VaultKeyManager.unwrapVaultMasterKey(deserialized.wrappedVmkWithPin, pin)
        assertArrayEquals("Unwrapped VMK via PIN must match original", vmk, unwrappedWithPin)

        val unwrappedWithRecovery = VaultKeyManager.unwrapVaultMasterKey(deserialized.wrappedVmkWithRecoveryKey!!, recoveryChars)
        assertArrayEquals("Unwrapped VMK via Recovery Key must match original", vmk, unwrappedWithRecovery)

        // Decrypt catalog using the unwrapped VMK
        val decryptedCatalog = VaultManifestSerializer.decryptCatalog(
            encryptedCatalog = deserialized.encryptedCatalog,
            nonce = deserialized.catalogNonce,
            vmk = unwrappedWithPin,
            vaultId = deserialized.vaultId,
        )

        assertEquals(2, decryptedCatalog.folders.size)
        assertEquals(2, decryptedCatalog.entries.size)

        assertEquals("Vacation 2026", decryptedCatalog.folders[0].name)
        assertEquals("Subfolder", decryptedCatalog.folders[1].name)
        assertEquals(folder1.id, decryptedCatalog.folders[1].parentId)

        assertEquals("family_beach.jpg", decryptedCatalog.entries[0].originalFileName)
        assertEquals("image/jpeg", decryptedCatalog.entries[0].mimeType)
        assertEquals(5242880L, decryptedCatalog.entries[0].originalSizeBytes)

        assertEquals("video_drone.mp4", decryptedCatalog.entries[1].originalFileName)
        assertEquals("video/mp4", decryptedCatalog.entries[1].mimeType)
        assertEquals(104857600L, decryptedCatalog.entries[1].originalSizeBytes)
    }

    @Test
    fun testWrongKeyFailsCatalogDecryption() {
        val vmk1 = VaultKeyManager.generateVaultMasterKey()
        val vmk2 = VaultKeyManager.generateVaultMasterKey()
        val pin = "1234".toCharArray()
        val pinWrapped = VaultKeyManager.wrapVaultMasterKey(vmk1, pin)

        val catalog = VaultCatalog(
            folders = emptyList(),
            entries = listOf(
                CatalogEntry(
                    id = "e1",
                    folderId = null,
                    originalFileName = "secret.pdf",
                    category = "Documents",
                    mimeType = "application/pdf",
                    originalSizeBytes = 1000L,
                    encryptedSizeBytes = 1200L,
                    createdAtMillis = 100L,
                    dateModifiedMillis = 200L,
                ),
            ),
        )

        val vaultId = "test-vault"
        val (nonce, ciphertext) = VaultManifestSerializer.encryptCatalog(catalog, vmk1, vaultId)

        val manifest = VaultManifest(
            vaultId = vaultId,
            createdAtMillis = 100L,
            updatedAtMillis = 200L,
            wrappedVmkWithPin = pinWrapped,
            wrappedVmkWithRecoveryKey = null,
            catalogNonce = nonce,
            encryptedCatalog = ciphertext,
        )

        assertThrows(SecurityException::class.java) {
            VaultManifestSerializer.decryptCatalog(
                encryptedCatalog = manifest.encryptedCatalog,
                nonce = manifest.catalogNonce,
                vmk = vmk2,
                vaultId = vaultId,
            )
        }
    }

    @Test
    fun testTamperedEncryptedCatalogFails() {
        val vmk = VaultKeyManager.generateVaultMasterKey()
        val pin = "1234".toCharArray()
        val pinWrapped = VaultKeyManager.wrapVaultMasterKey(vmk, pin)

        val catalog = VaultCatalog(
            folders = emptyList(),
            entries = emptyList(),
        )

        val vaultId = "test-vault"
        val (nonce, ciphertext) = VaultManifestSerializer.encryptCatalog(catalog, vmk, vaultId)

        val tamperedCiphertext = ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x55).toByte()

        assertThrows(SecurityException::class.java) {
            VaultManifestSerializer.decryptCatalog(
                encryptedCatalog = tamperedCiphertext,
                nonce = nonce,
                vmk = vmk,
                vaultId = vaultId,
            )
        }
    }

    @Test
    fun testAtomicManifestWriteAndBackupFallback() {
        val tempDir = java.nio.file.Files.createTempDirectory("manifest_test").toFile()
        try {
            val manifestFile = java.io.File(tempDir, VaultManifest.MANIFEST_FILE_NAME)
            val backupFile = java.io.File(tempDir, "${VaultManifest.MANIFEST_FILE_NAME}.bak")

            val vmk = VaultKeyManager.generateVaultMasterKey()
            val pin = "1234".toCharArray()
            val pinWrapped = VaultKeyManager.wrapVaultMasterKey(vmk, pin)

            val catalog1 = VaultCatalog(emptyList(), emptyList())
            val (nonce1, ct1) = VaultManifestSerializer.encryptCatalog(catalog1, vmk, "vault-1")
            val manifest1 = VaultManifest(
                vaultId = "vault-1",
                createdAtMillis = 1000L,
                updatedAtMillis = 1000L,
                wrappedVmkWithPin = pinWrapped,
                wrappedVmkWithRecoveryKey = null,
                catalogNonce = nonce1,
                encryptedCatalog = ct1,
            )

            // 1. Initial write
            VaultManifestManager.writeManifest(manifestFile, manifest1)
            assertTrue("Primary manifest file must exist", manifestFile.exists())
            val loaded1 = VaultManifestManager.readManifest(manifestFile)
            assertNotNull(loaded1)
            assertEquals("vault-1", loaded1?.vaultId)

            // 2. Second write should automatically create backup copy of previous manifest
            val (nonce2, ct2) = VaultManifestSerializer.encryptCatalog(catalog1, vmk, "vault-2")
            val manifest2 = VaultManifest(
                vaultId = "vault-2",
                createdAtMillis = 1000L,
                updatedAtMillis = 2000L,
                wrappedVmkWithPin = pinWrapped,
                wrappedVmkWithRecoveryKey = null,
                catalogNonce = nonce2,
                encryptedCatalog = ct2,
            )
            VaultManifestManager.writeManifest(manifestFile, manifest2)
            assertTrue("Backup file must exist after second write", backupFile.exists())
            val loaded2 = VaultManifestManager.readManifest(manifestFile)
            assertEquals("vault-2", loaded2?.vaultId)

            // 3. Delete primary manifest: readManifest must fall back to .bak and restore primary
            manifestFile.delete()
            val fallbackLoaded = VaultManifestManager.readManifest(manifestFile)
            assertNotNull("Must successfully recover from backup", fallbackLoaded)
            assertEquals("vault-1", fallbackLoaded?.vaultId)
            assertTrue("Primary manifest file must be self-healed", manifestFile.exists())

            // 4. Corrupt primary manifest: readManifest must recover from valid .bak
            manifestFile.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
            val recoveredFromCorrupt = VaultManifestManager.readManifest(manifestFile)
            assertNotNull("Must recover from backup when primary is corrupted", recoveredFromCorrupt)
            assertEquals("vault-1", recoveredFromCorrupt?.vaultId)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
