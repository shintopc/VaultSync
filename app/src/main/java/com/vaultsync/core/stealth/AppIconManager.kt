package com.vaultsync.core.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object AppIconManager {
    private const val TAG = "AppIconManager"

    const val NORMAL_ALIAS = "com.vaultsync.MainActivityNormal"
    const val CALCULATOR_ALIAS = "com.vaultsync.MainActivityCalculator"
    const val NOTES_ALIAS = "com.vaultsync.MainActivityNotes"

    /**
     * Applies the selected icon mode.
     * To prevent Android OS from abruptly killing the foreground process when an active
     * activity-alias is disabled while the user is using the app, this method immediately
     * ENABLES the target alias and updates preferences, while deferring the disabling of
     * inactive aliases until the app is backgrounded (via syncAliases in MainActivity.onStop).
     */
    fun applyIconMode(context: Context, mode: IconMode): Boolean {
        val pm = context.packageManager
        val packageName = context.packageName

        val normalComponent = ComponentName(packageName, NORMAL_ALIAS)
        val calcComponent = ComponentName(packageName, CALCULATOR_ALIAS)
        val notesComponent = ComponentName(packageName, NOTES_ALIAS)

        return try {
            StealthPreferences(context).iconMode = mode

            // Immediately enable the requested target alias
            when (mode) {
                IconMode.NORMAL -> {
                    setComponentState(pm, normalComponent, true)
                }
                IconMode.CALCULATOR -> {
                    setComponentState(pm, calcComponent, true)
                }
                IconMode.NOTES -> {
                    setComponentState(pm, notesComponent, true)
                }
                IconMode.HIDDEN -> {
                    // In hidden mode, no launcher alias is enabled
                }
            }
            Log.d(TAG, "Applied icon mode: ${mode.name}. Target alias enabled.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply icon mode ${mode.name}", e)
            false
        }
    }

    /**
     * Disables inactive launcher aliases. Must be called when the app is in the background
     * (e.g. from MainActivity.onStop) to ensure the active foreground window is never terminated.
     */
    fun syncAliases(context: Context) {
        val pm = context.packageManager
        val packageName = context.packageName
        val mode = StealthPreferences(context).iconMode

        val normalComponent = ComponentName(packageName, NORMAL_ALIAS)
        val calcComponent = ComponentName(packageName, CALCULATOR_ALIAS)
        val notesComponent = ComponentName(packageName, NOTES_ALIAS)

        try {
            when (mode) {
                IconMode.NORMAL -> {
                    setComponentState(pm, normalComponent, true)
                    setComponentState(pm, calcComponent, false)
                    setComponentState(pm, notesComponent, false)
                }
                IconMode.CALCULATOR -> {
                    setComponentState(pm, calcComponent, true)
                    setComponentState(pm, normalComponent, false)
                    setComponentState(pm, notesComponent, false)
                }
                IconMode.NOTES -> {
                    setComponentState(pm, notesComponent, true)
                    setComponentState(pm, normalComponent, false)
                    setComponentState(pm, calcComponent, false)
                }
                IconMode.HIDDEN -> {
                    setComponentState(pm, normalComponent, false)
                    setComponentState(pm, calcComponent, false)
                    setComponentState(pm, notesComponent, false)
                }
            }
            Log.d(TAG, "Synced aliases for mode: ${mode.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync aliases", e)
        }
    }

    private fun setComponentState(pm: PackageManager, component: ComponentName, enable: Boolean) {
        val targetState = if (enable) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val currentState = pm.getComponentEnabledSetting(component)
        if (currentState != targetState) {
            pm.setComponentEnabledSetting(
                component,
                targetState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun isAliasEnabled(context: Context, aliasName: String): Boolean {
        val pm = context.packageManager
        val component = ComponentName(context.packageName, aliasName)
        val state = pm.getComponentEnabledSetting(component)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
