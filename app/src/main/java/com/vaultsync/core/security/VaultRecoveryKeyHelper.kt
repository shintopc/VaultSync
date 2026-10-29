package com.vaultsync.core.security

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VaultRecoveryKeyHelper {
    const val PREFIX = "VSREC"

    /**
     * Generates a 128-bit cryptographically secure recovery key,
     * formatted as 8 groups of 4 hexadecimal characters:
     * VSREC-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX
     */
    fun generateRecoveryKey(): String {
        val randomBytes = KeyDerivation.generateRandomBytes(16)
        val hex = randomBytes.joinToString("") { "%02X".format(it) }
        val chunks = hex.chunked(4)
        return "$PREFIX-${chunks.joinToString("-")}"
    }

    /**
     * Normalizes any input recovery key by removing prefixes, hyphens, and whitespace,
     * returning a clean 32-character uppercase alphanumeric string.
     */
    fun normalizeKey(input: String): String {
        val upper = input.uppercase().trim()
        val withoutPrefix = if (upper.startsWith("$PREFIX-")) {
            upper.removePrefix("$PREFIX-")
        } else if (upper.startsWith("VS-")) {
            upper.removePrefix("VS-")
        } else {
            upper
        }
        return withoutPrefix.replace(Regex("[^A-Z0-9]"), "")
    }

    /**
     * Formats a clean 32-character key into the standard grouped format.
     */
    fun formatKey(raw: String): String {
        val clean = normalizeKey(raw)
        if (clean.length != 32) return raw
        val chunks = clean.chunked(4)
        return "$PREFIX-${chunks.joinToString("-")}"
    }

    fun isValidFormat(input: String): Boolean {
        val clean = normalizeKey(input)
        return clean.length == 32 && clean.all { it in "0123456789ABCDEF" }
    }

    /**
     * Generates a professional backup document content for the Android Save File dialog.
     */
    fun createBackupFileContent(recoveryKey: String): String {
        val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return """
            ================================================================
            VAULTSYNC PRO - SECURE VAULT RECOVERY KEY
            ================================================================
            KEEP THIS FILE SECURE AND OFFLINE.

            If you forget your Vault Master PIN, this Recovery Key is the
            ONLY way to reset your PIN and restore access to your encrypted
            files.

            RECOVERY KEY:
            $recoveryKey

            Generated: $formattedDate
            Security: AES-256-GCM Hardware-Backed Encryption
            App: VaultSync Android
            ================================================================
        """.trimIndent()
    }

    /**
     * Extracts a recovery key from file content (e.g. from an imported backup file).
     */
    fun extractKeyFromFileContent(content: String): String? {
        val prefixRegex = Regex("""(VSREC|VS)-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}""", RegexOption.IGNORE_CASE)
        val match = prefixRegex.find(content)
        if (match != null) return match.value.uppercase()

        val groupedRegex = Regex("""[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}""", RegexOption.IGNORE_CASE)
        val matchGrouped = groupedRegex.find(content)
        if (matchGrouped != null) return "$PREFIX-${matchGrouped.value.uppercase()}"

        val clean = normalizeKey(content)
        if (clean.length == 32 && clean.all { it in "0123456789ABCDEF" }) {
            return formatKey(clean)
        }

        return null
    }

    /**
     * Encrypts the recovery key with the VMK so it can be safely stored on device
     * and displayed to the user whenever the vault is unlocked.
     */
    fun encryptRecoveryKeyForDisplay(recoveryKey: String, vmk: ByteArray): String {
        val nonce = KeyDerivation.generateRandomBytes(CryptoConstants.GCM_NONCE_SIZE_BYTES)
        val keySpec = SecretKeySpec(vmk, CryptoConstants.KEY_ALGORITHM)
        val cipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, nonce))
        val encrypted = cipher.doFinal(recoveryKey.toByteArray(Charsets.UTF_8))
        return toHex(nonce) + ":" + toHex(encrypted)
    }

    /**
     * Decrypts the display recovery key using the unlocked VMK.
     */
    fun decryptRecoveryKeyForDisplay(encryptedString: String, vmk: ByteArray): String? {
        return try {
            val parts = encryptedString.split(":")
            if (parts.size != 2) return null
            val nonce = fromHex(parts[0])
            val encrypted = fromHex(parts[1])
            val keySpec = SecretKeySpec(vmk, CryptoConstants.KEY_ALGORITHM)
            val cipher = Cipher.getInstance(CryptoConstants.CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE_BITS, nonce))
            val decrypted = cipher.doFinal(encrypted)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray {
        val len = hex.length
        val result = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            result[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
        }
        return result
    }
}
