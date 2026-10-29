package com.vaultsync.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VaultCredentialStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isInitialized: Boolean
        get() = prefs.getBoolean(KEY_IS_INITIALIZED, false)

    var isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    fun saveCredentials(wrappedVmk: WrappedVmk) {
        prefs.edit()
            .putBoolean(KEY_IS_INITIALIZED, true)
            .putString(KEY_SALT, toHex(wrappedVmk.salt))
            .putString(KEY_NONCE, toHex(wrappedVmk.nonce))
            .putString(KEY_ENCRYPTED_KEY, toHex(wrappedVmk.encryptedKey))
            .putString(KEY_CREDENTIAL_VERIFIER, toHex(wrappedVmk.credentialVerifier))
            .apply()
    }

    fun getWrappedVmk(): WrappedVmk? {
        if (!isInitialized) return null
        val saltHex = prefs.getString(KEY_SALT, null) ?: return null
        val nonceHex = prefs.getString(KEY_NONCE, null) ?: return null
        val encKeyHex = prefs.getString(KEY_ENCRYPTED_KEY, null) ?: return null
        val verifierHex = prefs.getString(KEY_CREDENTIAL_VERIFIER, null) ?: return null

        return WrappedVmk(
            salt = fromHex(saltHex),
            nonce = fromHex(nonceHex),
            encryptedKey = fromHex(encKeyHex),
            credentialVerifier = fromHex(verifierHex),
        )
    }

    val hasRecoveryKey: Boolean
        get() = prefs.getBoolean(KEY_HAS_RECOVERY_KEY, false)

    fun saveRecoveryCredentials(wrappedVmk: WrappedVmk, encryptedDisplayKey: String? = null) {
        val editor = prefs.edit()
            .putBoolean(KEY_HAS_RECOVERY_KEY, true)
            .putString(KEY_RECOVERY_SALT, toHex(wrappedVmk.salt))
            .putString(KEY_RECOVERY_NONCE, toHex(wrappedVmk.nonce))
            .putString(KEY_RECOVERY_ENCRYPTED_KEY, toHex(wrappedVmk.encryptedKey))
            .putString(KEY_RECOVERY_VERIFIER, toHex(wrappedVmk.credentialVerifier))
        if (encryptedDisplayKey != null) {
            editor.putString(KEY_ENCRYPTED_DISPLAY_RECOVERY_KEY, encryptedDisplayKey)
        }
        editor.apply()
    }

    fun getRecoveryWrappedVmk(): WrappedVmk? {
        if (!hasRecoveryKey) return null
        val saltHex = prefs.getString(KEY_RECOVERY_SALT, null) ?: return null
        val nonceHex = prefs.getString(KEY_RECOVERY_NONCE, null) ?: return null
        val encKeyHex = prefs.getString(KEY_RECOVERY_ENCRYPTED_KEY, null) ?: return null
        val verifierHex = prefs.getString(KEY_RECOVERY_VERIFIER, null) ?: return null

        return WrappedVmk(
            salt = fromHex(saltHex),
            nonce = fromHex(nonceHex),
            encryptedKey = fromHex(encKeyHex),
            credentialVerifier = fromHex(verifierHex),
        )
    }

    fun getEncryptedDisplayRecoveryKey(): String? =
        prefs.getString(KEY_ENCRYPTED_DISPLAY_RECOVERY_KEY, null)

    fun enableBiometric(vmk: ByteArray): Boolean {
        return try {
            val secretKey = getOrCreateBiometricKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedVmk = cipher.doFinal(vmk)

            prefs.edit()
                .putBoolean(KEY_BIOMETRIC_ENABLED, true)
                .putString(KEY_BIOMETRIC_WRAPPED_VMK, toHex(encryptedVmk))
                .putString(KEY_BIOMETRIC_IV, toHex(iv))
                .apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun disableBiometric() {
        prefs.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .remove(KEY_BIOMETRIC_WRAPPED_VMK)
            .remove(KEY_BIOMETRIC_IV)
            .apply()
    }

    fun canUseBiometric(): Boolean {
        if (!isBiometricEnabled) return false
        val wrappedHex = prefs.getString(KEY_BIOMETRIC_WRAPPED_VMK, null)
        val ivHex = prefs.getString(KEY_BIOMETRIC_IV, null)
        return !wrappedHex.isNullOrBlank() && !ivHex.isNullOrBlank()
    }

    fun getBiometricCipherForDecryption(): Cipher? {
        if (!canUseBiometric()) return null
        return try {
            val iv = fromHex(prefs.getString(KEY_BIOMETRIC_IV, "") ?: return null)
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun unwrapBiometricVmk(cipher: Cipher): ByteArray? {
        val wrappedHex = prefs.getString(KEY_BIOMETRIC_WRAPPED_VMK, null) ?: return null
        return try {
            val encryptedVmk = fromHex(wrappedHex)
            cipher.doFinal(encryptedVmk)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
                keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun getOrCreateBiometricKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
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

    companion object {
        private const val PREFS_NAME = "vault_credentials"
        private const val KEY_IS_INITIALIZED = "is_initialized"
        private const val KEY_SALT = "salt"
        private const val KEY_NONCE = "nonce"
        private const val KEY_ENCRYPTED_KEY = "encrypted_key"
        private const val KEY_CREDENTIAL_VERIFIER = "credential_verifier"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_BIOMETRIC_WRAPPED_VMK = "biometric_wrapped_vmk"
        private const val KEY_BIOMETRIC_IV = "biometric_iv"

        private const val KEY_HAS_RECOVERY_KEY = "has_recovery_key"
        private const val KEY_RECOVERY_SALT = "recovery_salt"
        private const val KEY_RECOVERY_NONCE = "recovery_nonce"
        private const val KEY_RECOVERY_ENCRYPTED_KEY = "recovery_encrypted_key"
        private const val KEY_RECOVERY_VERIFIER = "recovery_verifier"
        private const val KEY_ENCRYPTED_DISPLAY_RECOVERY_KEY = "encrypted_display_recovery_key"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val BIOMETRIC_KEY_ALIAS = "VaultSync_Biometric_Master_Key"
    }
}
