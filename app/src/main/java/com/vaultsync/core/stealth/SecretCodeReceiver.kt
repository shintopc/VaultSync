package com.vaultsync.core.stealth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vaultsync.MainActivity
import com.vaultsync.R

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == ACTION_SECRET_CODE_MODERN || action == ACTION_SECRET_CODE_LEGACY) {
            val code = intent.data?.host
                ?: intent.getStringExtra("android.telephony.extra.SECRET_CODE")
                ?: "82858"
            Log.d(TAG, "Secret dialer code received ($action): $code")

            val prefs = StealthPreferences(context)
            prefs.lastRecoveryTimestamp = System.currentTimeMillis()

            // If the app was completely hidden, restore normal mode so user is un-locked
            if (prefs.iconMode == IconMode.HIDDEN) {
                AppIconManager.applyIconMode(context, IconMode.NORMAL)
                AppIconManager.syncAliases(context)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_RECOVERY_TRIGGERED, true)
                putExtra(EXTRA_SECRET_CODE_USED, code)
            }

            // 1. Attempt direct foreground launch
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct background activity launch blocked by OS, relying on heads-up notification", e)
            }

            // 2. Always post a high-priority Heads-Up notification for guaranteed user tap
            postRecoveryNotification(context, launchIntent, code)
        }
    }

    private fun postRecoveryNotification(context: Context, launchIntent: Intent, code: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val channelId = "vaultsync_recovery"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "VaultSync Recovery",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Notifications for Secret Code and Lockout Recovery"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                RECOVERY_NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("🔓 VaultSync Recovery Mode")
                .setContentText("Secret code (*#*#$code#*#*) entered. Tap here to open VaultSync.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .build()

            notificationManager.notify(RECOVERY_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post recovery notification", e)
        }
    }

    companion object {
        private const val TAG = "SecretCodeReceiver"
        const val ACTION_SECRET_CODE_MODERN = "android.telephony.action.SECRET_CODE"
        const val ACTION_SECRET_CODE_LEGACY = "android.provider.Telephony.SECRET_CODE"
        const val EXTRA_RECOVERY_TRIGGERED = "com.vaultsync.extra.RECOVERY_TRIGGERED"
        const val EXTRA_SECRET_CODE_USED = "com.vaultsync.extra.SECRET_CODE_USED"
        private const val RECOVERY_NOTIFICATION_ID = 82858
    }
}
