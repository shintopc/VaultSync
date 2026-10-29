package com.vaultsync.core.stealth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vaultsync.MainActivity

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "android.provider.Telephony.SECRET_CODE") {
            val code = intent.data?.host ?: "82858"
            Log.d(TAG, "Secret dialer code received: $code")

            val prefs = StealthPreferences(context)
            prefs.lastRecoveryTimestamp = System.currentTimeMillis()

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_RECOVERY_TRIGGERED, true)
                putExtra(EXTRA_SECRET_CODE_USED, code)
            }
            context.startActivity(launchIntent)
        }
    }

    companion object {
        private const val TAG = "SecretCodeReceiver"
        const val EXTRA_RECOVERY_TRIGGERED = "com.vaultsync.extra.RECOVERY_TRIGGERED"
        const val EXTRA_SECRET_CODE_USED = "com.vaultsync.extra.SECRET_CODE_USED"
    }
}
