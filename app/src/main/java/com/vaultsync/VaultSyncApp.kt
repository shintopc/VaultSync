package com.vaultsync

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vaultsync.core.floating.FloatingWindowManager
import com.vaultsync.core.floating.FloatingWindowOverlay
import com.vaultsync.core.floating.LocalFloatingWindowManager
import com.vaultsync.core.navigation.AppDestination
import com.vaultsync.core.stealth.AppIconManager
import com.vaultsync.core.stealth.AppLockManager
import com.vaultsync.core.stealth.IconMode
import com.vaultsync.core.stealth.StealthPreferences
import com.vaultsync.feature.media.MediaScreen
import com.vaultsync.feature.pcaccess.PcAccessScreen
import com.vaultsync.feature.settings.SettingsScreen
import com.vaultsync.feature.stealth.DisguisedCalculatorScreen
import com.vaultsync.feature.stealth.DisguisedNotesScreen
import com.vaultsync.feature.stealth.SecretAppEntryDialog
import com.vaultsync.feature.stealth.SecretAppEntryScreen
import com.vaultsync.feature.trash.TrashScreen
import com.vaultsync.feature.vault.VaultScreen
import com.vaultsync.core.vault.VaultContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun VaultSyncApp(
    recoveryNotice: StateFlow<String?> = MutableStateFlow(null),
) {
    val context = LocalContext.current
    val floatingWindowManager = remember { FloatingWindowManager.get(context) }
    val navController = rememberNavController()
    val stealthPrefs = remember { StealthPreferences(context) }
    val container = remember { VaultContainer.get(context) }

    val isAppUnlocked by AppLockManager.isAppUnlocked.collectAsState()
    val activeNotice by recoveryNotice.collectAsState()
    var isNoticeDismissed by remember { mutableStateOf(false) }

    var showSecretEntryDialog by remember { mutableStateOf(false) }

    // In Normal mode without launch lock, auto-unlock main app
    LaunchedEffect(stealthPrefs.iconMode, stealthPrefs.isAppLockEnabled) {
        if (stealthPrefs.iconMode == IconMode.NORMAL && !stealthPrefs.isAppLockEnabled) {
            AppLockManager.setUnlockedDirectly()
        }
    }

    CompositionLocalProvider(LocalFloatingWindowManager provides floatingWindowManager) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!isAppUnlocked) {
                // Front-door App Security Gate: Disguise Cover or Secret Entry Screen
                when (stealthPrefs.iconMode) {
                    IconMode.CALCULATOR -> {
                        DisguisedCalculatorScreen(
                            isTwoFingerGestureEnabled = true,
                            gestureType = stealthPrefs.gestureType,
                            onSecretAccessTriggered = {
                                Toast.makeText(context, "Welcome to Main Gallery 🖼️", Toast.LENGTH_SHORT).show()
                                AppLockManager.setUnlockedDirectly()
                            },
                        )
                    }
                    IconMode.NOTES -> {
                        DisguisedNotesScreen(
                            isTwoFingerGestureEnabled = true,
                            gestureType = stealthPrefs.gestureType,
                            onSecretAccessTriggered = {
                                Toast.makeText(context, "Welcome to Main Gallery 🖼️", Toast.LENGTH_SHORT).show()
                                AppLockManager.setUnlockedDirectly()
                            },
                        )
                    }
                    IconMode.HIDDEN -> {
                        SecretAppEntryScreen(
                            title = "VaultSync (Hidden Mode)",
                            subtitle = "Perform two-finger swipe or enter PIN to access the main app",
                            isTwoFingerGestureEnabled = stealthPrefs.isTwoFingerGestureEnabled,
                            gestureType = stealthPrefs.gestureType,
                            onUnlockSuccess = {
                                // isAppUnlocked automatically becomes true, revealing the main app UI
                            },
                            onNavigateToSetup = {
                                AppLockManager.setUnlockedDirectly()
                            },
                        )
                    }
                    IconMode.NORMAL -> {
                        // Normal mode with app lock enabled
                        SecretAppEntryScreen(
                            title = "VaultSync Protected",
                            subtitle = "Perform two-finger swipe or enter PIN to access the main app",
                            isTwoFingerGestureEnabled = stealthPrefs.isTwoFingerGestureEnabled,
                            gestureType = stealthPrefs.gestureType,
                            onUnlockSuccess = {
                                // isAppUnlocked automatically becomes true, revealing the main app UI
                            },
                            onNavigateToSetup = {
                                AppLockManager.setUnlockedDirectly()
                            },
                        )
                    }
                }
            } else {
                // The MAIN APP: Media Gallery, Vault, PC Access, Settings, Trash
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Black,
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = AppDestination.Media.route,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        composable(AppDestination.Media.route) {
                            MediaScreen(
                                onNavigateToVault = {
                                    navController.navigate(AppDestination.Vault.route)
                                },
                                onNavigateToPcAccess = {
                                    navController.navigate(AppDestination.PcAccess.route)
                                },
                                onNavigateToSettings = {
                                    navController.navigate(AppDestination.Settings.route)
                                },
                                onNavigateToTrash = {
                                    navController.navigate(AppDestination.Trash.route)
                                },
                                onLockAndDisguise = {
                                    AppLockManager.relock(context)
                                },
                            )
                        }
                        composable(AppDestination.Vault.route) {
                            VaultScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                            )
                        }
                        composable(AppDestination.PcAccess.route) {
                            PcAccessScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                            )
                        }
                        composable(AppDestination.Settings.route) {
                            SettingsScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onNavigateToTrash = {
                                    navController.navigate(AppDestination.Trash.route)
                                },
                                onNavigateToVault = {
                                    navController.navigate(AppDestination.Vault.route)
                                },
                                onNavigateToPcAccess = {
                                    navController.navigate(AppDestination.PcAccess.route)
                                },
                            )
                        }
                        composable(AppDestination.Trash.route) {
                            TrashScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                            )
                        }
                    }
                }

                // In-app Floating Multi-Window layer (supports multiple draggable photos & videos)
                FloatingWindowOverlay(manager = floatingWindowManager)
            }

            // Secret Entry Dialog (triggered by two-finger swipe on Disguise screen)
            if (showSecretEntryDialog) {
                SecretAppEntryDialog(
                    onUnlockSuccess = {
                        showSecretEntryDialog = false
                    },
                    onDismiss = {
                        showSecretEntryDialog = false
                    },
                    onNavigateToSetup = {
                        showSecretEntryDialog = false
                        AppLockManager.setUnlockedDirectly()
                    },
                )
            }

            // Recovery Notice Banner
            AnimatedVisibility(
                visible = activeNotice != null && !isNoticeDismissed,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2E24)),
                    border = BorderStroke(1.dp, Color(0xFF30D158)),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🔓", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeNotice ?: "",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                            Text(
                                text = "Current mode: ${stealthPrefs.iconMode.displayName}",
                                color = Color(0xFF9EF2E3),
                                fontSize = 11.sp,
                            )
                        }
                        if (stealthPrefs.iconMode != IconMode.NORMAL) {
                            TextButton(
                                onClick = {
                                    AppIconManager.applyIconMode(context, IconMode.NORMAL)
                                    isNoticeDismissed = true
                                    Toast.makeText(context, "Restored normal gallery launcher icon", Toast.LENGTH_SHORT).show()
                                },
                            ) {
                                Text("Restore Icon", color = Color(0xFF30D158), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = { isNoticeDismissed = true }) {
                            Text("✕", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
