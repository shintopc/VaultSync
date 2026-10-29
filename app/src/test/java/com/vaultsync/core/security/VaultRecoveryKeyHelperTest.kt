package com.vaultsync.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRecoveryKeyHelperTest {

    @Test
    fun generateRecoveryKeyCreatesValidFormat() {
        val key1 = VaultRecoveryKeyHelper.generateRecoveryKey()
        val key2 = VaultRecoveryKeyHelper.generateRecoveryKey()

        assertNotNull(key1)
        assertNotNull(key2)
        assertFalse(key1 == key2)

        assertTrue(key1.startsWith("VSREC-"))
        assertTrue(VaultRecoveryKeyHelper.isValidFormat(key1))
        assertTrue(VaultRecoveryKeyHelper.isValidFormat(key2))

        val normalized = VaultRecoveryKeyHelper.normalizeKey(key1)
        assertEquals(32, normalized.length)
        assertTrue(normalized.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun normalizeKeyHandlesVariousFormats() {
        val raw = "VSREC-A1B2-C3D4-E5F6-7890-1234-5678-9ABC-DEF0"
        val expected = "A1B2C3D4E5F67890123456789ABCDEF0"

        assertEquals(expected, VaultRecoveryKeyHelper.normalizeKey(raw))
        assertEquals(expected, VaultRecoveryKeyHelper.normalizeKey(raw.lowercase()))
        assertEquals(expected, VaultRecoveryKeyHelper.normalizeKey("  vsrec-a1b2-c3d4-e5f6-7890-1234-5678-9abc-def0  "))
        assertEquals(expected, VaultRecoveryKeyHelper.normalizeKey("VS-A1B2-C3D4-E5F6-7890-1234-5678-9ABC-DEF0"))
        assertEquals(expected, VaultRecoveryKeyHelper.normalizeKey("A1B2 C3D4 E5F6 7890 1234 5678 9ABC DEF0"))
    }

    @Test
    fun formatKeyProducesStandardFormat() {
        val clean = "A1B2C3D4E5F67890123456789ABCDEF0"
        val formatted = VaultRecoveryKeyHelper.formatKey(clean)
        assertEquals("VSREC-A1B2-C3D4-E5F6-7890-1234-5678-9ABC-DEF0", formatted)
    }

    @Test
    fun isValidFormatValidatesCorrectly() {
        assertTrue(VaultRecoveryKeyHelper.isValidFormat("VSREC-A1B2-C3D4-E5F6-7890-1234-5678-9ABC-DEF0"))
        assertTrue(VaultRecoveryKeyHelper.isValidFormat("a1b2-c3d4-e5f6-7890-1234-5678-9abc-def0"))
        assertTrue(VaultRecoveryKeyHelper.isValidFormat("A1B2C3D4E5F67890123456789ABCDEF0"))

        // Too short
        assertFalse(VaultRecoveryKeyHelper.isValidFormat("A1B2C3D4"))
        // Invalid hex chars
        assertFalse(VaultRecoveryKeyHelper.isValidFormat("VSREC-ZZZZ-C3D4-E5F6-7890-1234-5678-9ABC-DEF0"))
        // Empty
        assertFalse(VaultRecoveryKeyHelper.isValidFormat(""))
    }

    @Test
    fun extractKeyFromFileContentExtractsKeyCorrectly() {
        val originalKey = "VSREC-A1B2-C3D4-E5F6-7890-1234-5678-9ABC-DEF0"
        val backupContent = VaultRecoveryKeyHelper.createBackupFileContent(originalKey)

        val extracted = VaultRecoveryKeyHelper.extractKeyFromFileContent(backupContent)
        assertEquals(originalKey, extracted)

        // Raw key inside text
        val messyText = "Here is my note: please use key VSREC-1234-5678-9ABC-DEF0-1234-5678-9ABC-DEF0 to restore."
        val extractedMessy = VaultRecoveryKeyHelper.extractKeyFromFileContent(messyText)
        assertEquals("VSREC-1234-5678-9ABC-DEF0-1234-5678-9ABC-DEF0", extractedMessy)

        // Just clean 32 chars
        val clean32 = "1234567890ABCDEF1234567890ABCDEF"
        val extractedClean = VaultRecoveryKeyHelper.extractKeyFromFileContent(clean32)
        assertEquals("VSREC-1234-5678-90AB-CDEF-1234-5678-90AB-CDEF", extractedClean)

        // Unrelated text
        assertNull(VaultRecoveryKeyHelper.extractKeyFromFileContent("No keys here at all"))
    }

    @Test
    fun encryptAndDecryptDisplayKeyRoundtrip() {
        val vmk = VaultKeyManager.generateVaultMasterKey()
        val recoveryKey = VaultRecoveryKeyHelper.generateRecoveryKey()

        val encrypted = VaultRecoveryKeyHelper.encryptRecoveryKeyForDisplay(recoveryKey, vmk)
        assertNotNull(encrypted)
        assertTrue(encrypted.contains(":"))

        val decrypted = VaultRecoveryKeyHelper.decryptRecoveryKeyForDisplay(encrypted, vmk)
        assertEquals(recoveryKey, decrypted)

        // Wrong VMK should fail decryption gracefully and return null
        val wrongVmk = VaultKeyManager.generateVaultMasterKey()
        val failed = VaultRecoveryKeyHelper.decryptRecoveryKeyForDisplay(encrypted, wrongVmk)
        assertNull(failed)
    }

    @Test
    fun endToEndRecoveryFlowRecoversVmkAndResetsPin() {
        // 1. Initial Vault Master Key and initial PIN
        val originalVmk = VaultKeyManager.generateVaultMasterKey()
        val initialPin = "1234".toCharArray()
        val initialWrappedPin = VaultKeyManager.wrapVaultMasterKey(originalVmk, initialPin)

        // 2. Wrap VMK with generated recovery key
        val recoveryKey = VaultRecoveryKeyHelper.generateRecoveryKey()
        val cleanRecoveryKey = VaultRecoveryKeyHelper.normalizeKey(recoveryKey)
        val recoveryWrappedVmk = VaultKeyManager.wrapVaultMasterKey(originalVmk, cleanRecoveryKey.toCharArray())

        // 3. User forgets PIN, but provides correct recovery key
        val unwrappedVmk = VaultKeyManager.unwrapVaultMasterKey(recoveryWrappedVmk, cleanRecoveryKey.toCharArray())
        assertArrayEquals(originalVmk, unwrappedVmk)

        // 4. User resets PIN to "9876" using the recovered VMK
        val newPin = "9876".toCharArray()
        val newWrappedPin = VaultKeyManager.wrapVaultMasterKey(unwrappedVmk, newPin)

        // 5. Old PIN fails, new PIN successfully unlocks vault
        assertThrows(SecurityException::class.java) {
            VaultKeyManager.unwrapVaultMasterKey(newWrappedPin, initialPin)
        }
        val unlockedWithNewPin = VaultKeyManager.unwrapVaultMasterKey(newWrappedPin, newPin)
        assertArrayEquals(originalVmk, unlockedWithNewPin)

        // 6. Wrong recovery key fails
        val wrongRecoveryKey = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".toCharArray()
        assertThrows(SecurityException::class.java) {
            VaultKeyManager.unwrapVaultMasterKey(recoveryWrappedVmk, wrongRecoveryKey)
        }
    }
}
