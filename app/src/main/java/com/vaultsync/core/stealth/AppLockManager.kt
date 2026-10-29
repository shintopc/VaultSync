package com.vaultsync.core.stealth

import android.content.Context
import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.vault.VaultContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.Cipher

/**
 * Manages front-door authentication for accessing the MAIN APP (VaultSync Gallery).
 * Disguised modes (Calculator/Notes) and Hidden mode protect the entire application
 * behind this security gate.
 */
object AppLockManager {
    private val _isAppUnlocked = MutableStateFlow(false)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    /**
     * Attempts to unlock access to the main app using the master PIN credential.
     */
    fun unlockWithPin(context: Context, pin: String): Boolean {
        val container = VaultContainer.get(context)
        val wrapped = container.credentialStore.getWrappedVmk() ?: return false
        return try {
            val vmk = VaultKeyManager.unwrapVaultMasterKey(wrapped, pin.trim().toCharArray())
            container.session.unlock(vmk)
            VaultKeyManager.zeroize(vmk)
            _isAppUnlocked.value = true
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Unlocks access to the main app using verified biometric cipher.
     */
    fun unlockWithBiometricCipher(context: Context, cipher: Cipher): Boolean {
        val container = VaultContainer.get(context)
        return try {
            val vmk = container.credentialStore.unwrapBiometricVmk(cipher)
            if (vmk != null && vmk.size == 32) {
                container.session.unlock(vmk)
                VaultKeyManager.zeroize(vmk)
                _isAppUnlocked.value = true
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Relocks access to the main app, returning to the disguise facade.
     */
    fun relock(context: Context) {
        val container = VaultContainer.get(context)
        container.session.lock()
        _isAppUnlocked.value = false
    }

    /**
     * Manually mark app as unlocked (e.g. for Normal mode without launch lock).
     */
    fun setUnlockedDirectly() {
        _isAppUnlocked.value = true
    }
}
