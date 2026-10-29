package com.vaultsync.core.stealth

const val ROUTE_DISGUISED_CALCULATOR = "disguised_calculator"
const val ROUTE_DISGUISED_NOTES = "disguised_notes"

enum class IconMode(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val aliasName: String?,
) {
    NORMAL(
        id = "normal",
        displayName = "Normal Mode",
        subtitle = "VaultSync appears with its regular gallery icon",
        aliasName = "MainActivityNormal",
    ),
    CALCULATOR(
        id = "calculator",
        displayName = "Disguised: Calculator",
        subtitle = "Appears as \"Calculator\" with fully functional math app. Secret gesture unlocks gallery",
        aliasName = "MainActivityCalculator",
    ),
    NOTES(
        id = "notes",
        displayName = "Disguised: Notes",
        subtitle = "Appears as \"Notes\" with clean memo facade. Secret gesture unlocks gallery",
        aliasName = "MainActivityNotes",
    ),
    HIDDEN(
        id = "hidden",
        displayName = "Hidden Mode",
        subtitle = "Launcher icon is disabled. Access via secret code, deep link, or gesture",
        aliasName = null,
    );

    companion object {
        fun fromId(id: String?): IconMode {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: NORMAL
        }
    }
}

enum class TwoFingerGestureType(
    val id: String,
    val displayName: String,
    val description: String,
) {
    SWIPE_DOWN(
        id = "swipe_down",
        displayName = "Two-Finger Swipe Down",
        description = "Drag two fingers downward anywhere on the screen",
    ),
    SWIPE_UP(
        id = "swipe_up",
        displayName = "Two-Finger Swipe Up",
        description = "Drag two fingers upward anywhere on the screen",
    ),
    DOUBLE_TAP(
        id = "double_tap",
        displayName = "Two-Finger Double Tap",
        description = "Tap the screen twice quickly with two fingers",
    );

    companion object {
        fun fromId(id: String?): TwoFingerGestureType {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: SWIPE_DOWN
        }
    }
}
