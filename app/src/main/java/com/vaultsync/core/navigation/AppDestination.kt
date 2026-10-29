package com.vaultsync.core.navigation

enum class AppDestination(
    val route: String,
    val label: String,
) {
    Media("media", "Media"),
    Vault("vault", "Vault"),
    PcAccess("pc_access", "PC Access"),
    Settings("settings", "Settings"),
    Trash("trash", "Trash"),
}
