package com.vaultsync.core.stealth

import android.content.Context
import android.content.SharedPreferences

class StealthPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var iconMode: IconMode
        get() = IconMode.fromId(prefs.getString(KEY_ICON_MODE, IconMode.NORMAL.id))
        set(value) = prefs.edit().putString(KEY_ICON_MODE, value.id).apply()

    var isAppLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, value).apply()

    var isTwoFingerGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_TWO_FINGER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TWO_FINGER_ENABLED, value).apply()

    var gestureType: TwoFingerGestureType
        get() = TwoFingerGestureType.fromId(prefs.getString(KEY_GESTURE_TYPE, TwoFingerGestureType.SWIPE_DOWN.id))
        set(value) = prefs.edit().putString(KEY_GESTURE_TYPE, value.id).apply()

    var isCalculatorPinTriggerEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALC_PIN_TRIGGER, true)
        set(value) = prefs.edit().putBoolean(KEY_CALC_PIN_TRIGGER, value).apply()

    var hasAcknowledgedHiddenRisk: Boolean
        get() = prefs.getBoolean(KEY_ACKNOWLEDGED_HIDDEN_RISK, false)
        set(value) = prefs.edit().putBoolean(KEY_ACKNOWLEDGED_HIDDEN_RISK, value).apply()

    var lastRecoveryTimestamp: Long
        get() = prefs.getLong(KEY_LAST_RECOVERY_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RECOVERY_TIMESTAMP, value).apply()

    var isLongPressSettingsForVaultEnabled: Boolean
        get() = prefs.getBoolean(KEY_LONG_PRESS_SETTINGS_VAULT, true)
        set(value) = prefs.edit().putBoolean(KEY_LONG_PRESS_SETTINGS_VAULT, value).apply()

    var autoLockTimeoutSeconds: Long
        get() = prefs.getLong(KEY_AUTO_LOCK_TIMEOUT_SECONDS, 0L)
        set(value) = prefs.edit().putLong(KEY_AUTO_LOCK_TIMEOUT_SECONDS, value).apply()

    var backgroundTimestamp: Long
        get() = prefs.getLong(KEY_BACKGROUND_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_BACKGROUND_TIMESTAMP, value).apply()

    companion object {
        private const val PREFS_NAME = "vaultsync_stealth_preferences"
        private const val KEY_ICON_MODE = "icon_mode"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_TWO_FINGER_ENABLED = "two_finger_gesture_enabled"
        private const val KEY_GESTURE_TYPE = "two_finger_gesture_type"
        private const val KEY_CALC_PIN_TRIGGER = "calculator_pin_trigger_enabled"
        private const val KEY_ACKNOWLEDGED_HIDDEN_RISK = "acknowledged_hidden_risk"
        private const val KEY_LAST_RECOVERY_TIMESTAMP = "last_recovery_timestamp"
        private const val KEY_LONG_PRESS_SETTINGS_VAULT = "long_press_settings_vault"
        private const val KEY_AUTO_LOCK_TIMEOUT_SECONDS = "auto_lock_timeout_seconds"
        private const val KEY_BACKGROUND_TIMESTAMP = "background_timestamp"
    }
}

enum class AutoLockTimeout(val seconds: Long, val label: String) {
    IMMEDIATE(0L, "Immediately on exit"),
    SECONDS_30(30L, "After 30 seconds"),
    MINUTES_1(60L, "After 1 minute"),
    MINUTES_5(300L, "After 5 minutes"),
    NEVER(-1L, "Manual lock only");

    companion object {
        fun fromSeconds(seconds: Long): AutoLockTimeout =
            entries.find { it.seconds == seconds } ?: IMMEDIATE
    }
}

