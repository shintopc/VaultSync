package com.vaultsync.core.server

import com.vaultsync.core.security.KeyDerivation
import com.vaultsync.core.security.VaultCredentialStore
import com.vaultsync.core.security.VaultKeyManager
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class PcSession(
    val token: String,
    val clientIp: String,
    val createdAtMillis: Long,
    @Volatile var lastActivityMillis: Long,
    @Volatile var hasVaultAccess: Boolean = false,
)

class PcAuthManager(
    private val credentialStore: VaultCredentialStore,
    private val sessionTtlMillis: Long = 30 * 60 * 1000L, // 30 minutes
) {
    private val secureRandom = SecureRandom()

    // Generated pairing passcode for web login
    @Volatile
    var currentWebPasscode: String = generateRandomPasscode()
        private set

    // Active sessions: token -> PcSession
    private val activeSessions = ConcurrentHashMap<String, PcSession>()

    // IP Throttling tracker: IP -> AttemptHistory
    private class AttemptHistory(
        var failureCount: Int = 0,
        var lockedUntilMillis: Long = 0L,
    )
    private val ipAttempts = ConcurrentHashMap<String, AttemptHistory>()

    fun regeneratePasscode(): String {
        currentWebPasscode = generateRandomPasscode()
        // Invalidate previous sessions when passcode changes
        invalidateAllSessions()
        return currentWebPasscode
    }

    fun isThrottled(clientIp: String): Long {
        val history = ipAttempts[clientIp] ?: return 0L
        val now = System.currentTimeMillis()
        return if (history.lockedUntilMillis > now) {
            (history.lockedUntilMillis - now) / 1000L
        } else {
            0L
        }
    }

    fun authenticatePasscode(clientIp: String, submittedPasscode: String): PcSession? {
        val now = System.currentTimeMillis()
        val history = ipAttempts.computeIfAbsent(clientIp) { AttemptHistory() }

        if (history.lockedUntilMillis > now) {
            return null
        }

        if (submittedPasscode.trim() == currentWebPasscode) {
            // Success: clear throttle
            history.failureCount = 0
            history.lockedUntilMillis = 0L

            val token = generateSecureToken()
            val session = PcSession(
                token = token,
                clientIp = clientIp,
                createdAtMillis = now,
                lastActivityMillis = now,
                hasVaultAccess = false,
            )
            activeSessions[token] = session
            return session
        } else {
            // Failure: record and potentially lock
            history.failureCount++
            if (history.failureCount >= 5) {
                history.lockedUntilMillis = now + 60_000L // 60s lockout
            }
            return null
        }
    }

    fun validateSession(token: String?, clientIp: String): PcSession? {
        if (token.isNullOrBlank()) return null
        val session = activeSessions[token] ?: return null
        val now = System.currentTimeMillis()

        if (now - session.lastActivityMillis > sessionTtlMillis) {
            activeSessions.remove(token)
            return null
        }

        // Verify client IP matches to prevent session hijacking across LAN
        val normSessionIp = NetworkUtils.normalizeIp(session.clientIp)
        val normClientIp = NetworkUtils.normalizeIp(clientIp)
        if (normSessionIp != normClientIp && !isLoopback(normClientIp) && !isLoopback(normSessionIp)) {
            return null
        }

        session.lastActivityMillis = now
        return session
    }

    fun elevateSessionToVault(token: String, pin: String, vaultSession: com.vaultsync.core.security.VaultSession? = null): Boolean {
        val cleanPin = pin.trim()
        if (cleanPin.isEmpty()) return false

        val session = activeSessions[token] ?: run {
            android.util.Log.w("PcAuthManager", "elevateSessionToVault: session not found for token")
            return false
        }

        // Validate against stored wrapped VMK using Master PIN / Password
        val wrapped = credentialStore.getWrappedVmk()
        if (wrapped == null) {
            android.util.Log.w("PcAuthManager", "elevateSessionToVault: credentialStore has no wrapped VMK")
            return false
        }

        return try {
            val vmk = VaultKeyManager.unwrapVaultMasterKey(wrapped, cleanPin.toCharArray())
            if (vaultSession != null && !vaultSession.isUnlocked.value) {
                vaultSession.unlock(vmk)
            } else {
                VaultKeyManager.zeroize(vmk)
            }
            session.hasVaultAccess = true
            session.lastActivityMillis = System.currentTimeMillis()
            android.util.Log.i("PcAuthManager", "elevateSessionToVault: authorized via Master PIN / Password unwrapping")
            true
        } catch (e: Exception) {
            android.util.Log.w("PcAuthManager", "elevateSessionToVault: failed to unwrap key: ${e.message}")
            false
        }
    }

    fun revokeVaultAccess(token: String) {
        activeSessions[token]?.hasVaultAccess = false
    }

    fun revokeAllVaultAccess() {
        activeSessions.values.forEach { it.hasVaultAccess = false }
    }

    fun logout(token: String?) {
        if (!token.isNullOrBlank()) {
            activeSessions.remove(token)
        }
    }

    fun invalidateAllSessions() {
        activeSessions.clear()
        ipAttempts.clear()
    }

    fun getActiveSessionCount(): Int {
        val now = System.currentTimeMillis()
        return activeSessions.values.count { now - it.lastActivityMillis <= sessionTtlMillis }
    }

    fun validateOriginAndCsrf(
        originHeader: String?,
        refererHeader: String?,
        serverHost: String,
        customHeader: String?,
    ): Boolean {
        // If custom client header is set, browser preflight CORS prevents CSRF
        if (customHeader.equals("web", ignoreCase = true)) {
            return true
        }

        // Check Origin header if present
        if (!originHeader.isNullOrBlank()) {
            val host = originHeader.removePrefix("http://").removePrefix("https://").substringBefore('/')
            if (isAllowedHost(host, serverHost)) return true
        }

        // Check Referer header if present
        if (!refererHeader.isNullOrBlank()) {
            val host = refererHeader.removePrefix("http://").removePrefix("https://").substringBefore('/')
            if (isAllowedHost(host, serverHost)) return true
        }

        // If no Origin or Referer (like non-browser direct API calls), require custom header
        return customHeader != null
    }

    private fun isAllowedHost(hostWithPort: String, serverHost: String): Boolean {
        val host = hostWithPort.substringBefore(':')
        val targetHost = serverHost.substringBefore(':')
        return host.equals(targetHost, ignoreCase = true) ||
            host == "localhost" ||
            host == "127.0.0.1"
    }

    private fun isLoopback(ip: String): Boolean =
        ip == "127.0.0.1" || ip == "localhost" || ip == "::1"

    private fun generateRandomPasscode(): String {
        val code = 100000 + secureRandom.nextInt(900000)
        return code.toString()
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
