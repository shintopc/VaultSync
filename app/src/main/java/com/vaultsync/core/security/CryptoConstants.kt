package com.vaultsync.core.security

object CryptoConstants {
    const val KEY_ALGORITHM = "AES"
    const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

    const val AES_KEY_SIZE_BYTES = 32 // 256 bits
    const val GCM_NONCE_SIZE_BYTES = 12 // 96 bits
    const val GCM_TAG_SIZE_BITS = 128
    const val GCM_TAG_SIZE_BYTES = 16

    const val PBKDF2_ITERATIONS = 120_000
    const val SALT_SIZE_BYTES = 32

    // File Format Specification
    val MAGIC_BYTES = byteArrayOf('V'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    const val FORMAT_VERSION: Short = 1
    const val DEFAULT_CHUNK_SIZE_BYTES = 64 * 1024 // 64 KiB plaintext chunk
    const val FILE_NONCE_PREFIX_SIZE_BYTES = 4
    const val WRAPPED_KEY_SIZE_BYTES = AES_KEY_SIZE_BYTES + GCM_TAG_SIZE_BYTES // 48 bytes
}
