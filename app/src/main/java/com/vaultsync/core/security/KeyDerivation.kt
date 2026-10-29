package com.vaultsync.core.security

import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object KeyDerivation {
    private val secureRandom = SecureRandom()

    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun deriveKeyWrappingKey(
        credential: CharArray,
        salt: ByteArray,
        iterations: Int = CryptoConstants.PBKDF2_ITERATIONS,
    ): ByteArray {
        val spec = PBEKeySpec(
            credential,
            salt,
            iterations,
            CryptoConstants.AES_KEY_SIZE_BYTES * 8,
        )
        return try {
            val factory = SecretKeyFactory.getInstance(CryptoConstants.KDF_ALGORITHM)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun deriveCredentialVerifier(
        credential: CharArray,
        salt: ByteArray,
        iterations: Int = CryptoConstants.PBKDF2_ITERATIONS,
    ): ByteArray {
        // Derive verification token using an altered salt to prevent key-reuse equivalence
        val verifierSalt = ByteArray(salt.size) { i -> (salt[i].toInt() xor 0x5A).toByte() }
        val spec = PBEKeySpec(
            credential,
            verifierSalt,
            iterations,
            CryptoConstants.AES_KEY_SIZE_BYTES * 8,
        )
        return try {
            val factory = SecretKeyFactory.getInstance(CryptoConstants.KDF_ALGORITHM)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
