package com.vaultsync.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

class VaultKeyManagerTest {

    @Test
    fun generatesValid256BitKey() {
        val vmk1 = VaultKeyManager.generateVaultMasterKey()
        val vmk2 = VaultKeyManager.generateVaultMasterKey()

        assertEquals(CryptoConstants.AES_KEY_SIZE_BYTES, vmk1.size)
        assertEquals(CryptoConstants.AES_KEY_SIZE_BYTES, vmk2.size)
        assertFalse(vmk1.contentEquals(vmk2))
    }

    @Test
    fun wrapAndUnwrapWithCorrectPasswordSucceeds() {
        val vmk = VaultKeyManager.generateVaultMasterKey()
        val password = "MySecureVaultPassword123!".toCharArray()

        val wrapped = VaultKeyManager.wrapVaultMasterKey(vmk, password)
        val unwrapped = VaultKeyManager.unwrapVaultMasterKey(wrapped, password)

        assertArrayEquals(vmk, unwrapped)
    }

    @Test
    fun unwrapWithWrongPasswordThrowsSecurityException() {
        val vmk = VaultKeyManager.generateVaultMasterKey()
        val correctPassword = "CorrectPassword123".toCharArray()
        val wrongPassword = "WrongPassword456".toCharArray()

        val wrapped = VaultKeyManager.wrapVaultMasterKey(vmk, correctPassword)

        assertThrows(SecurityException::class.java) {
            VaultKeyManager.unwrapVaultMasterKey(wrapped, wrongPassword)
        }
    }

    @Test
    fun tamperedEncryptedKeyThrowsSecurityException() {
        val vmk = VaultKeyManager.generateVaultMasterKey()
        val password = "TestPassword".toCharArray()

        val wrapped = VaultKeyManager.wrapVaultMasterKey(vmk, password)
        val tamperedEncryptedKey = wrapped.encryptedKey.copyOf()
        tamperedEncryptedKey[0] = (tamperedEncryptedKey[0].toInt() xor 0xFF).toByte()

        val tamperedWrapped = wrapped.copy(encryptedKey = tamperedEncryptedKey)

        assertThrows(Exception::class.java) {
            VaultKeyManager.unwrapVaultMasterKey(tamperedWrapped, password)
        }
    }

    @Test
    fun zeroizeClearsByteArray() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        VaultKeyManager.zeroize(bytes)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun vaultSessionManagesKeyLifecycle() {
        val session = VaultSession()
        assertFalse(session.isUnlocked.value)

        val vmk = VaultKeyManager.generateVaultMasterKey()
        session.unlock(vmk)
        assertTrue(session.isUnlocked.value)

        val retrievedKey = session.getVmkCopy()
        assertArrayEquals(vmk, retrievedKey)

        session.lock()
        assertFalse(session.isUnlocked.value)

        assertThrows(SecurityException::class.java) {
            session.getVmkCopy()
        }
    }
}
