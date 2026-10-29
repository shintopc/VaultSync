package com.vaultsync.core.security

import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class WrappedVmk(
    val salt: ByteArray,
    val nonce: ByteArray,
    val encryptedKey: ByteArray,
    val credentialVerifier: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WrappedVmk) return false
        return salt.contentEquals(other.salt) &&
            nonce.contentEquals(other.nonce) &&
            encryptedKey.contentEquals(other.encryptedKey) &&
            credentialVerifier.contentEquals(other.credentialVerifier)
    }

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + encryptedKey.contentHashCode()
        result = 31 * result + credentialVerifier.contentHashCode()
        return result
    }
}

object VaultKeyManager {

    fun generateVaultMasterKey(): ByteArray =
        KeyDerivation.generateRandomBytes(CryptoConstants.AES_KEY_SIZE_BYTES)

    fun wrapVaultMasterKey(
        vmk: ByteArray,
        credential: CharArray,
    ): WrappedVmk {
        val salt = KeyDerivation.generateRandomBytes(CryptoConstants.SALT_SIZE_BYTES)
        val kwk = KeyDerivation.deriveKeyWrappingKey(credential, salt)
        val verifier = KeyDerivation.deriveCredentialVerifier(credential, salt)
        val nonce = KeyDerivation.generateRandomBytes(CryptoConstants.GCM_NONCE_SIZE_BYTES)

        try {
            val keySpec = SecretKeySpec(kwk, CryptoConstants.KEY_ALGORITHM)
            val cipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val encryptedKey = cipher.doFinal(vmk)

            return WrappedVmk(
                salt = salt,
                nonce = nonce,
                encryptedKey = encryptedKey,
                credentialVerifier = verifier,
            )
        } finally {
            Arrays.fill(kwk, 0.toByte())
        }
    }

    fun unwrapVaultMasterKey(
        wrappedVmk: WrappedVmk,
        credential: CharArray,
    ): ByteArray {
        val derivedVerifier = KeyDerivation.deriveCredentialVerifier(credential, wrappedVmk.salt)
        try {
            if (!KeyDerivation.constantTimeEquals(derivedVerifier, wrappedVmk.credentialVerifier)) {
                throw SecurityException("Incorrect vault unlock credential")
            }
        } finally {
            Arrays.fill(derivedVerifier, 0.toByte())
        }

        val kwk = KeyDerivation.deriveKeyWrappingKey(credential, wrappedVmk.salt)
        return try {
            val keySpec = SecretKeySpec(kwk, CryptoConstants.KEY_ALGORITHM)
            val cipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, wrappedVmk.nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(wrappedVmk.encryptedKey)
        } finally {
            Arrays.fill(kwk, 0.toByte())
        }
    }

    fun zeroize(bytes: ByteArray) {
        Arrays.fill(bytes, 0.toByte())
    }
}
