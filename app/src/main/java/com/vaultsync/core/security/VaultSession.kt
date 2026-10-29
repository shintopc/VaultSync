package com.vaultsync.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Arrays

class VaultSession {
    private var activeVmk: ByteArray? = null

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    @Synchronized
    fun unlock(vmk: ByteArray) {
        require(vmk.size == CryptoConstants.AES_KEY_SIZE_BYTES) { "Invalid VMK length" }
        lock() // Clear any existing key first
        activeVmk = vmk.copyOf()
        _isUnlocked.value = true
    }

    @Synchronized
    fun lock() {
        activeVmk?.let { key ->
            Arrays.fill(key, 0.toByte())
        }
        activeVmk = null
        _isUnlocked.value = false
    }

    @Synchronized
    fun <T> withVmk(block: (ByteArray) -> T): T {
        val key = activeVmk ?: throw SecurityException("Vault is locked")
        return block(key)
    }

    @Synchronized
    fun getVmkCopy(): ByteArray {
        val key = activeVmk ?: throw SecurityException("Vault is locked")
        return key.copyOf()
    }

    suspend fun <T> withVmkSuspend(block: suspend (ByteArray) -> T): T {
        val key = getVmkCopy()
        return try {
            block(key)
        } finally {
            Arrays.fill(key, 0.toByte())
        }
    }
}
