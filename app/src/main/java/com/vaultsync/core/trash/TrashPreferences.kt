package com.vaultsync.core.trash

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages preferences for Trash (Recycle Bin) functionality,
 * including enabling/disabling trash and retention duration (default: 15 days).
 */
class TrashPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isTrashEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRASH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TRASH_ENABLED, value).apply()

    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()

    companion object {
        private const val PREFS_NAME = "vaultsync_trash_prefs"
        private const val KEY_TRASH_ENABLED = "trash_enabled"
        private const val KEY_RETENTION_DAYS = "trash_retention_days"
        const val DEFAULT_RETENTION_DAYS = 15
    }
}
