package com.vaultsync.core.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.vaultsync.core.media.AndroidMediaRepository
import com.vaultsync.core.security.VaultCredentialStore
import com.vaultsync.core.security.VaultSession
import com.vaultsync.core.vault.VaultContainer
import com.vaultsync.core.vault.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PcAccessState(
    val serverState: ServerState = ServerState.STOPPED,
    val isWifiConnected: Boolean = false,
    val wifiSsid: String? = null,
    val localIp: String? = null,
    val port: Int = 8080,
    val url: String? = null,
    val passcode: String = "------",
    val activeSessionsCount: Int = 0,
    val errorMessage: String? = null,
    val isWakeLockActive: Boolean = false,
)

class PcAccessManager(
    private val context: Context,
    val authManager: PcAuthManager,
    val server: PcServer,
) {
    private val _state = MutableStateFlow(PcAccessState())
    val state: StateFlow<PcAccessState> = _state.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkInfo()
        }

        override fun onLost(network: Network) {
            updateNetworkInfo()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateNetworkInfo()
        }
    }

    init {
        registerNetworkCallback()
        updateNetworkInfo()
    }

    fun startServer(): Boolean {
        updateNetworkInfo()
        val success = server.start()
        _state.update {
            it.copy(
                serverState = server.state,
                port = server.boundPort,
                url = server.getLocalUrl(),
                passcode = authManager.currentWebPasscode,
                errorMessage = server.errorMessage,
                isWakeLockActive = success,
            )
        }
        if (success) {
            try {
                PcAccessService.start(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return success
    }

    fun stopServer() {
        try {
            PcAccessService.stop(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        server.stop()
        _state.update {
            it.copy(
                serverState = ServerState.STOPPED,
                url = null,
                activeSessionsCount = 0,
                errorMessage = null,
                isWakeLockActive = false,
            )
        }
    }

    fun regeneratePasscode(): String {
        val newCode = authManager.regeneratePasscode()
        _state.update { it.copy(passcode = newCode, activeSessionsCount = 0) }
        return newCode
    }

    fun updateNetworkInfo() {
        val isWifi = NetworkUtils.isWifiConnected(context)
        val ssid = NetworkUtils.getWifiSsid(context)
        val ip = NetworkUtils.getLocalIpAddress(context)
        val url = if (server.isRunning && ip != null) "http://$ip:${server.boundPort}" else null

        _state.update {
            it.copy(
                isWifiConnected = isWifi,
                wifiSsid = ssid,
                localIp = ip,
                url = url,
                passcode = authManager.currentWebPasscode,
                activeSessionsCount = authManager.getActiveSessionCount(),
                serverState = server.state,
                port = server.boundPort,
                isWakeLockActive = server.isRunning,
            )
        }
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unregisterNetworkCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        @Volatile
        private var instance: PcAccessManager? = null

        fun get(context: Context): PcAccessManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val container = VaultContainer.get(appContext)
                    val authManager = PcAuthManager(container.credentialStore)
                    val mediaRepo = AndroidMediaRepository(appContext)
                    val server = PcServer(
                        context = appContext,
                        authManager = authManager,
                        mediaRepository = mediaRepo,
                        vaultRepository = container.repository,
                        vaultSession = container.session,
                        storageManager = container.storageManager,
                        thumbnailManager = container.thumbnailManager,
                    )
                    PcAccessManager(appContext, authManager, server).also { instance = it }
                }
            }
        }
    }
}
