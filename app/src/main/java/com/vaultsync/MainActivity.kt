package com.vaultsync

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.vaultsync.core.designsystem.VaultSyncTheme
import com.vaultsync.core.server.PcAccessManager
import com.vaultsync.core.stealth.AppLockManager
import com.vaultsync.core.stealth.IconMode
import com.vaultsync.core.stealth.SecretCodeReceiver
import com.vaultsync.core.stealth.StealthPreferences
import com.vaultsync.core.vault.VaultContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val recoveryNotice = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        handleIncomingIntent(intent)
        enableEdgeToEdge()
        setContent {
            VaultSyncTheme {
                VaultSyncApp(recoveryNotice = recoveryNotice)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val isSecretDialer = intent.getBooleanExtra(SecretCodeReceiver.EXTRA_RECOVERY_TRIGGERED, false)
        val uri = intent.data
        if (isSecretDialer) {
            val code = intent.getStringExtra(SecretCodeReceiver.EXTRA_SECRET_CODE_USED) ?: "82858"
            recoveryNotice.value = "VaultSync launched via dialer secret code (*#*#$code#*#*)"
        } else if (uri != null && uri.scheme == "vaultsync") {
            val host = uri.host ?: "open"
            recoveryNotice.value = "VaultSync launched via recovery link (vaultsync://$host)"
        }
    }

    override fun onStart() {
        super.onStart()
        val stealthPrefs = StealthPreferences(this)
        val timeoutSec = stealthPrefs.autoLockTimeoutSeconds
        val bgTime = stealthPrefs.backgroundTimestamp
        if (timeoutSec > 0L && bgTime > 0L) {
            val elapsedSec = (System.currentTimeMillis() - bgTime) / 1000L
            if (elapsedSec >= timeoutSec) {
                val container = VaultContainer.get(this)
                container.session.lock()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // If user backgrounded the app (not a configuration change like screen rotation)
        if (!isChangingConfigurations) {
            val stealthPrefs = StealthPreferences(this)
            stealthPrefs.backgroundTimestamp = System.currentTimeMillis()
            if (stealthPrefs.iconMode != IconMode.NORMAL || stealthPrefs.isAppLockEnabled) {
                AppLockManager.relock(this)
            }
            val container = VaultContainer.get(this)
            if (stealthPrefs.autoLockTimeoutSeconds == 0L) {
                container.session.lock()
            }
            com.vaultsync.core.stealth.AppIconManager.syncAliases(this)
            lifecycleScope.launch(Dispatchers.IO) {
                container.previewManager.clearAllPreviews()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            val pcAccess = PcAccessManager.get(this)
            pcAccess.stopServer()
            pcAccess.unregisterNetworkCallback()

            com.vaultsync.core.floating.FloatingWindowManager.get(this).closeAll()

            val container = VaultContainer.get(this)
            container.session.lock()
            lifecycleScope.launch(Dispatchers.IO) {
                container.previewManager.clearAllPreviews()
                container.thumbnailManager.clearAllThumbnails()
            }
        }
    }
}
