package com.vaultsync.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationTest {
    @Test
    fun routesAreUniqueAndStable() {
        val routes = AppDestination.entries.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
        assertEquals(listOf("media", "vault", "pc_access", "settings", "trash"), routes)
    }

    @Test
    fun labelsAreReadyForTopLevelNavigation() {
        val labels = AppDestination.entries.map { it.label }

        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(listOf("Media", "Vault", "PC Access", "Settings", "Trash"), labels)
    }
}
