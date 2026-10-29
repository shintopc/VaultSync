package com.vaultsync.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaultsync.core.server.PcAccessManager
import com.vaultsync.core.server.PcAccessState
import com.vaultsync.core.server.ServerState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.vaultsync.core.stealth.AutoLockTimeout
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.imageLoader
import com.vaultsync.core.stealth.AppIconManager
import com.vaultsync.core.stealth.IconMode
import com.vaultsync.core.stealth.StealthPreferences
import com.vaultsync.core.stealth.TwoFingerGestureType
import com.vaultsync.core.stealth.detectTwoFingerVaultGesture
import com.vaultsync.core.storage.VaultStoragePreferences
import com.vaultsync.core.trash.TrashManager
import com.vaultsync.core.trash.TrashPreferences
import com.vaultsync.core.vault.VaultContainer
import com.vaultsync.feature.stealth.RecoveryGuideDialog
import androidx.biometric.BiometricManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.security.VaultRecoveryKeyHelper
import com.vaultsync.feature.vault.RecoveryKeyDisplayDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class SettingsSubScreen {
    MAIN,
    SECURITY_PRIVACY,
    STORAGE_RECOVERY,
    APPEARANCE_CAMOUFLAGE,
    PC_ACCESS,
    ABOUT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    onNavigateToVault: () -> Unit = {},
    onNavigateToPcAccess: () -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val container = remember { VaultContainer.get(context) }
    val trashPreferences = remember { TrashPreferences(context) }
    val trashManager = remember { TrashManager.get(context) }
    val pcAccessManager = remember { PcAccessManager.get(context) }
    val pcAccessState by pcAccessManager.state.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Notification permission handled */ },
    )

    val onTogglePcAccess: (Boolean) -> Unit = { enable ->
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val started = pcAccessManager.startServer()
            if (started) {
                val url = pcAccessManager.state.value.url
                Toast.makeText(context, "PC Access Live: ${url ?: "Port 8080"}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not start PC Access server", Toast.LENGTH_SHORT).show()
            }
        } else {
            pcAccessManager.stopServer()
            Toast.makeText(context, "PC Access Server Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    val credentialStore = container.credentialStore
    val storageManager = container.storageManager
    val storagePrefs = remember { VaultStoragePreferences(context) }
    val stealthPrefs = remember { StealthPreferences(context) }

    var isVaultInitialized by remember { mutableStateOf(credentialStore.isInitialized) }
    var hasRecoveryKey by remember { mutableStateOf(credentialStore.hasRecoveryKey) }

    var currentSubScreen by remember { mutableStateOf(SettingsSubScreen.MAIN) }

    var isTrashEnabled by remember { mutableStateOf(trashPreferences.isTrashEnabled) }
    var retentionDays by remember { mutableIntStateOf(trashPreferences.retentionDays) }
    var trashStats by remember { mutableStateOf(Pair(0, 0L)) }
    var vaultStorageBytes by remember { mutableLongStateOf(0L) }
    var availableStorageBytes by remember { mutableLongStateOf(0L) }
    var isSharedStorageActive by remember { mutableStateOf(storagePrefs.isSharedStorageEnabled) }

    var isBiometricEnabled by remember { mutableStateOf(credentialStore.isBiometricEnabled) }
    val isHardwareBiometricAvailable = remember(context) {
        try {
            val bm = BiometricManager.from(context)
            bm.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK,
            ) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    // Stealth & Camouflage In-line State (NO POPUP WINDOW)
    var currentIconMode by remember { mutableStateOf(stealthPrefs.iconMode) }
    var isAppLockEnabled by remember { mutableStateOf(stealthPrefs.isAppLockEnabled) }
    var isTwoFingerEnabled by remember { mutableStateOf(stealthPrefs.isTwoFingerGestureEnabled) }
    var selectedGestureType by remember { mutableStateOf(stealthPrefs.gestureType) }
    var isLongPressVaultEnabled by remember { mutableStateOf(stealthPrefs.isLongPressSettingsForVaultEnabled) }
    var gestureTestFeedback by remember { mutableStateOf<String?>(null) }
    var showConfirmHiddenDialog by remember { mutableStateOf(false) }

    // Dialog Visibility States
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showBiometricAuthPinDialog by remember { mutableStateOf(false) }
    var showRecoveryKeyPinPromptDialog by remember { mutableStateOf(false) }
    var showRecoveryKeyDialog by remember { mutableStateOf(false) }
    var activeRecoveryKeyToDisplay by remember { mutableStateOf<String?>(null) }

    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showRetentionDaysDialog by remember { mutableStateOf(false) }
    var showRecoveryGuideDialog by remember { mutableStateOf(false) }
    var showEncryptionInfoDialog by remember { mutableStateOf(false) }
    var showServerPortInfoDialog by remember { mutableStateOf(false) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showThemeInfoDialog by remember { mutableStateOf(false) }
    var showMigrationInfoDialog by remember { mutableStateOf(false) }
    var showVaultDiscoveryDialog by remember { mutableStateOf(false) }
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var autoLockTimeout by remember { mutableStateOf(AutoLockTimeout.fromSeconds(stealthPrefs.autoLockTimeoutSeconds)) }

    // Auto-clear gesture test feedback
    LaunchedEffect(gestureTestFeedback) {
        if (gestureTestFeedback != null) {
            delay(3500)
            gestureTestFeedback = null
        }
    }

    // Intercept back button when in a subscreen to return to main settings menu
    BackHandler(enabled = currentSubScreen != SettingsSubScreen.MAIN) {
        currentSubScreen = SettingsSubScreen.MAIN
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            trashStats = trashManager.getTrashStats()
            availableStorageBytes = storageManager.getAvailableStorageBytes()
            val blobs = storageManager.blobsDir.listFiles() ?: emptyArray()
            vaultStorageBytes = blobs.sumOf { it.length() }
            isSharedStorageActive = storagePrefs.isSharedStorageEnabled
        }
    }

    val topBarTitle = when (currentSubScreen) {
        SettingsSubScreen.MAIN -> "Settings"
        SettingsSubScreen.SECURITY_PRIVACY -> "Security & Privacy"
        SettingsSubScreen.STORAGE_RECOVERY -> "Storage & Recovery"
        SettingsSubScreen.APPEARANCE_CAMOUFLAGE -> "Appearance & Camouflage"
        SettingsSubScreen.PC_ACCESS -> "PC Access"
        SettingsSubScreen.ABOUT -> "About VaultSync"
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = topBarTitle,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentSubScreen != SettingsSubScreen.MAIN) {
                                currentSubScreen = SettingsSubScreen.MAIN
                            } else {
                                onNavigateBack()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedContent(
                targetState = currentSubScreen,
                transitionSpec = {
                    if (targetState != SettingsSubScreen.MAIN) {
                        (slideInHorizontally { width -> width / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> -width / 3 } + fadeOut())
                    } else {
                        (slideInHorizontally { width -> -width / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> width / 3 } + fadeOut())
                    }
                },
                label = "SettingsScreenTransition",
            ) { targetScreen ->
                when (targetScreen) {
                    SettingsSubScreen.MAIN -> {
                        MainMenuContent(
                            availableStorageBytes = availableStorageBytes,
                            vaultStorageBytes = vaultStorageBytes,
                            trashSizeBytes = trashStats.second,
                            pcAccessState = pcAccessState,
                            onTogglePcAccess = onTogglePcAccess,
                            onNavigateToSection = { subScreen -> currentSubScreen = subScreen },
                        )
                    }
                    SettingsSubScreen.SECURITY_PRIVACY -> {
                        SecurityPrivacySubScreen(
                            isVaultInitialized = isVaultInitialized,
                            hasRecoveryKey = hasRecoveryKey,
                            isBiometricEnabled = isBiometricEnabled,
                            isHardwareBiometricAvailable = isHardwareBiometricAvailable,
                            onToggleBiometric = { checked ->
                                if (!checked) {
                                    credentialStore.disableBiometric()
                                    isBiometricEnabled = false
                                    Toast.makeText(context, "Biometric authentication disabled", Toast.LENGTH_SHORT).show()
                                } else {
                                    if (!isHardwareBiometricAvailable) {
                                        Toast.makeText(
                                            context,
                                            "Please register a fingerprint in your Android device settings first.",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    } else if (!credentialStore.isInitialized) {
                                        Toast.makeText(
                                            context,
                                            "Please set up your Vault Master PIN first.",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        showChangePinDialog = true
                                    } else if (container.session.isUnlocked.value) {
                                        container.session.withVmk { vmk ->
                                            val success = credentialStore.enableBiometric(vmk)
                                            isBiometricEnabled = success
                                            if (success) {
                                                Toast.makeText(context, "Biometric authentication enabled", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Failed to enable biometrics", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } else {
                                        showBiometricAuthPinDialog = true
                                    }
                                }
                            },
                            onOpenChangePin = { showChangePinDialog = true },
                            onOpenRecoveryKey = {
                                if (!credentialStore.isInitialized) {
                                    Toast.makeText(context, "Please configure your Vault Master PIN first.", Toast.LENGTH_SHORT).show()
                                    showChangePinDialog = true
                                } else if (container.session.isUnlocked.value) {
                                    container.session.withVmk { vmk ->
                                        val existingEncrypted = credentialStore.getEncryptedDisplayRecoveryKey()
                                        var key: String? = null
                                        if (existingEncrypted != null) {
                                            key = VaultRecoveryKeyHelper.decryptRecoveryKeyForDisplay(existingEncrypted, vmk)
                                        }
                                        if (key == null) {
                                            val newKey = VaultRecoveryKeyHelper.generateRecoveryKey()
                                            val clean = VaultRecoveryKeyHelper.normalizeKey(newKey)
                                            val wrapped = VaultKeyManager.wrapVaultMasterKey(vmk, clean.toCharArray())
                                            val encDisplay = VaultRecoveryKeyHelper.encryptRecoveryKeyForDisplay(newKey, vmk)
                                            credentialStore.saveRecoveryCredentials(wrapped, encDisplay)
                                            hasRecoveryKey = true
                                            key = newKey
                                        }
                                        activeRecoveryKeyToDisplay = key
                                        showRecoveryKeyDialog = true
                                    }
                                } else {
                                    showRecoveryKeyPinPromptDialog = true
                                }
                            },
                            onNavigateToVault = onNavigateToVault,
                            onShowEncryptionInfo = { showEncryptionInfoDialog = true },
                            onShowRecoveryGuide = { showRecoveryGuideDialog = true },
                            autoLockTimeout = autoLockTimeout,
                            onOpenAutoLockDialog = { showAutoLockDialog = true },
                        )
                    }
                    SettingsSubScreen.STORAGE_RECOVERY -> {
                        StorageRecoverySubScreen(
                            availableStorageBytes = availableStorageBytes,
                            vaultStorageBytes = vaultStorageBytes,
                            trashStats = trashStats,
                            isTrashEnabled = isTrashEnabled,
                            retentionDays = retentionDays,
                            isSharedStorageActive = isSharedStorageActive,
                            vaultRootPath = storageManager.vaultRoot.absolutePath,
                            onToggleTrash = { enabled ->
                                trashPreferences.isTrashEnabled = enabled
                                isTrashEnabled = enabled
                            },
                            onOpenRetentionDialog = { showRetentionDaysDialog = true },
                            onNavigateToTrash = onNavigateToTrash,
                            onOpenEmptyTrashDialog = { showEmptyTrashDialog = true },
                            onOpenMigrationDialog = { showMigrationInfoDialog = true },
                            onOpenDiscoveryDialog = { showVaultDiscoveryDialog = true },
                            onCleanTempFiles = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    storageManager.cleanStaleTempFiles()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Stale temporary chunks cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onClearCache = {
                                try {
                                    context.imageLoader.memoryCache?.clear()
                                    Toast.makeText(context, "Memory cache cleared successfully", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Cache refreshed", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                    SettingsSubScreen.APPEARANCE_CAMOUFLAGE -> {
                        AppearanceCamouflageSubScreen(
                            currentIconMode = currentIconMode,
                            onSelectIconMode = { mode ->
                                if (mode == IconMode.HIDDEN) {
                                    showConfirmHiddenDialog = true
                                } else {
                                    val success = AppIconManager.applyIconMode(context, mode)
                                    currentIconMode = mode
                                    if (mode == IconMode.CALCULATOR || mode == IconMode.NOTES) {
                                        stealthPrefs.isTwoFingerGestureEnabled = true
                                        isTwoFingerEnabled = true
                                    }
                                    if (success) {
                                        Toast.makeText(
                                            context,
                                            "Applied: ${mode.displayName}. Icon will update on home screen when closed.",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            isAppLockEnabled = isAppLockEnabled,
                            onToggleAppLock = { checked ->
                                isAppLockEnabled = checked
                                stealthPrefs.isAppLockEnabled = checked
                            },
                            isTwoFingerEnabled = isTwoFingerEnabled,
                            onToggleTwoFinger = { checked ->
                                isTwoFingerEnabled = checked
                                stealthPrefs.isTwoFingerGestureEnabled = checked
                            },
                            selectedGestureType = selectedGestureType,
                            onSelectGestureType = { type ->
                                selectedGestureType = type
                                stealthPrefs.gestureType = type
                            },
                            gestureTestFeedback = gestureTestFeedback,
                            onGestureTriggered = {
                                gestureTestFeedback = "Gesture Triggered! Main App Entry Activated ✨"
                            },
                            isLongPressVaultEnabled = isLongPressVaultEnabled,
                            onToggleLongPressVault = { checked ->
                                isLongPressVaultEnabled = checked
                                stealthPrefs.isLongPressSettingsForVaultEnabled = checked
                            },
                            onOpenRecoveryGuide = { showRecoveryGuideDialog = true },
                            onOpenThemeInfo = { showThemeInfoDialog = true },
                        )
                    }
                    SettingsSubScreen.PC_ACCESS -> {
                        PcAccessSubScreen(
                            pcAccessState = pcAccessState,
                            onTogglePcAccess = onTogglePcAccess,
                            onRegeneratePasscode = {
                                val code = pcAccessManager.regeneratePasscode()
                                Toast.makeText(context, "New PIN: $code", Toast.LENGTH_SHORT).show()
                            },
                            onNavigateToPcAccess = onNavigateToPcAccess,
                            onOpenServerPortInfo = { showServerPortInfoDialog = true },
                        )
                    }
                    SettingsSubScreen.ABOUT -> {
                        AboutSubScreen(
                            onOpenPermissionsDialog = { showPermissionsDialog = true },
                            onOpenAboutDialog = { showAboutDialog = true },
                            onOpenEncryptionInfo = { showEncryptionInfoDialog = true },
                        )
                    }
                }
            }
        }
    }

    // 1. Retention Days Picker Dialog
    if (showRetentionDaysDialog) {
        val daysOptions = listOf(7, 15, 30, 60)
        var selectedOption by remember { mutableIntStateOf(retentionDays) }

        AlertDialog(
            onDismissRequest = { showRetentionDaysDialog = false },
            title = { Text("Auto-Purge Retention", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    Text(
                        "Deleted files in the Trash Bin are kept for this duration before automatic permanent purge:",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    daysOptions.forEach { days ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (days == selectedOption),
                                    onClick = { selectedOption = days },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (days == selectedOption),
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF30D158),
                                    unselectedColor = Color(0xFF8E8E93),
                                ),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "$days Days" + if (days == 15) " (Default)" else "",
                                color = Color.White,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        retentionDays = selectedOption
                        trashPreferences.retentionDays = selectedOption
                        showRetentionDaysDialog = false
                        Toast.makeText(context, "Trash auto-purge set to $selectedOption days", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRetentionDaysDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 1b. Auto-Lock Timeout Picker Dialog
    if (showAutoLockDialog) {
        val options = AutoLockTimeout.entries
        var selectedOption by remember { mutableStateOf(autoLockTimeout) }

        AlertDialog(
            onDismissRequest = { showAutoLockDialog = false },
            title = { Text("Auto-Lock Timeout", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    Text(
                        "Automatically lock vault and purge preview decryption keys after minimizing:",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (option == selectedOption),
                                    onClick = { selectedOption = option },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (option == selectedOption),
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF30D158),
                                    unselectedColor = Color(0xFF8E8E93),
                                ),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = option.label,
                                color = Color.White,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        autoLockTimeout = selectedOption
                        stealthPrefs.autoLockTimeoutSeconds = selectedOption.seconds
                        showAutoLockDialog = false
                        Toast.makeText(context, "Auto-lock set to ${selectedOption.label}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutoLockDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 2. Empty Trash Confirmation Dialog
    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            title = { Text("Empty Trash Bin", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Permanently delete all ${trashStats.first} items from Trash? This operation is immediate and irreversible.",
                    color = Color(0xFF8E8E93),
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val count = trashManager.emptyTrash()
                            trashStats = trashManager.getTrashStats()
                            Toast.makeText(context, "Emptied $count item(s) from Trash", Toast.LENGTH_SHORT).show()
                        }
                        showEmptyTrashDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Empty Trash Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 3. Cryptographic Info Dialog
    if (showEncryptionInfoDialog) {
        AlertDialog(
            onDismissRequest = { showEncryptionInfoDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡️", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cryptographic Specifications", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CryptoDetailItem("Algorithm", "AES-256-GCM (Authenticated Encryption)")
                    CryptoDetailItem("Key Hierarchy", "Per-file DEKs wrapped with Android KeyStore VMK")
                    CryptoDetailItem("Container Framing", ".vsf v1 (64 KB authenticated chunks)")
                    CryptoDetailItem("Integrity Auth", "128-bit GCM Tags & HMAC-SHA256 headers")
                    CryptoDetailItem("Key Derivation", "PBKDF2WithHmacSHA256 (100,000 iterations)")
                    CryptoDetailItem("Anti-Lockout", "128-bit Hex Master Recovery Key")
                }
            },
            confirmButton = {
                Button(
                    onClick = { showEncryptionInfoDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Text("Close", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 4. Server Port Info Dialog
    if (showServerPortInfoDialog) {
        AlertDialog(
            onDismissRequest = { showServerPortInfoDialog = false },
            title = { Text("Local PC Server (Port 8080)", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "VaultSync runs an embedded HTTP server strictly on your local Wi-Fi interface.",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                    Text("• Private Subnet Isolation: External websites cannot bridge to the server.", color = Color.White, fontSize = 13.sp)
                    Text("• Zero Internet Routing: Media never travels through cloud servers.", color = Color.White, fontSize = 13.sp)
                    Text("• Strict PIN Elevation: Accessing the vault requires Master PIN entry.", color = Color.White, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showServerPortInfoDialog = false
                        onNavigateToPcAccess()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                ) {
                    Text("Go to PC Sync", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerPortInfoDialog = false }) {
                    Text("Close", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 5. Permissions Inspector Dialog
    if (showPermissionsDialog) {
        val hasImages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        val hasVideo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        AlertDialog(
            onDismissRequest = { showPermissionsDialog = false },
            title = { Text("System Permissions Status", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionStatusRow("Media & Photos", hasImages && hasVideo)
                    PermissionStatusRow("Hardware Biometrics", isHardwareBiometricAvailable)
                    PermissionStatusRow("Local Wi-Fi Network", true)
                    PermissionStatusRow("Internet Tracking / Telemetry", false, isGoodWhenDisabled = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPermissionsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Text("Done", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 6. Theme Info Dialog
    if (showThemeInfoDialog) {
        AlertDialog(
            onDismissRequest = { showThemeInfoDialog = false },
            title = { Text("AMOLED Pure Black", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "VaultSync utilizes pure true black (#000000) backgrounds across all screens. On OLED and AMOLED displays, black pixels are completely turned off, reducing battery drain significantly while maximizing contrast.",
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = { showThemeInfoDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Text("OK", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 7. About App Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡️", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("VaultSync Android", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version: 0.1.0 (Production Hardened)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Architecture: Jetpack Compose + Ktor CIO + Room", color = Color(0xFF8E8E93), fontSize = 12.sp)
                    Text("Cryptography: AES-256-GCM + Android KeyStore", color = Color(0xFF8E8E93), fontSize = 12.sp)
                    Text("Privacy: 100% Zero-Cloud, completely offline media gallery.", color = Color(0xFF8E8E93), fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Text("Dismiss", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 8. Shared Storage Migration Dialog
    if (showMigrationInfoDialog) {
        val sharedDir = storagePrefs.getSharedStorageDirectory()
        AlertDialog(
            onDismissRequest = { showMigrationInfoDialog = false },
            title = { Text("Shared Storage Architecture", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Shared Storage places your encrypted vault files in the public Documents directory:\n${sharedDir.absolutePath}",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                    Text("• Survives App Uninstall: Even if you uninstall VaultSync, your encrypted `.vsf` files are NOT deleted by Android.", color = Color.White, fontSize = 13.sp)
                    Text("• Zero Leakage: All files remain strictly AES-256-GCM encrypted and protected by your Master PIN.", color = Color.White, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        storagePrefs.configureSharedStorage(sharedDir.absolutePath)
                        isSharedStorageActive = true
                        showMigrationInfoDialog = false
                        Toast.makeText(context, "Shared Storage active in ${sharedDir.name}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                ) {
                    Text("Use Shared Storage", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMigrationInfoDialog = false }) {
                    Text("Close", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 9. Vault Discovery Dialog
    if (showVaultDiscoveryDialog) {
        val sharedDir = storagePrefs.getSharedStorageDirectory()
        val exists = sharedDir.exists()
        val blobCount = if (exists) File(sharedDir, "blobs").listFiles()?.size ?: 0 else 0

        AlertDialog(
            onDismissRequest = { showVaultDiscoveryDialog = false },
            title = { Text("Vault Discovery", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scanned storage location:", color = Color(0xFF8E8E93), fontSize = 12.sp)
                    Text(sharedDir.absolutePath, color = Color(0xFF64D2FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (exists && blobCount > 0) "Found existing vault container with $blobCount encrypted item(s)."
                        else if (exists) "Vault directory exists but contains no items."
                        else "No existing shared vault directory found.",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showVaultDiscoveryDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Text("OK", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // 10. Hidden Mode Safety Confirmation Dialog
    if (showConfirmHiddenDialog) {
        RecoveryGuideDialog(
            isConfirmationForHiding = true,
            onConfirmHide = {
                stealthPrefs.iconMode = IconMode.HIDDEN
                val success = AppIconManager.applyIconMode(context, IconMode.HIDDEN)
                currentIconMode = IconMode.HIDDEN
                stealthPrefs.hasAcknowledgedHiddenRisk = true
                stealthPrefs.isTwoFingerGestureEnabled = true
                isTwoFingerEnabled = true
                showConfirmHiddenDialog = false
                if (success) {
                    Toast.makeText(
                        context,
                        "Applied: Hidden Mode. Access via *#*#82858#*#* or vaultsync://open",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onDismiss = {
                showConfirmHiddenDialog = false
            },
        )
    }

    // 11. Recovery Guide Dialog
    if (showRecoveryGuideDialog) {
        RecoveryGuideDialog(
            isConfirmationForHiding = false,
            onDismiss = { showRecoveryGuideDialog = false },
        )
    }

    // 12. Change Vault PIN Dialog
    if (showChangePinDialog) {
        ChangeVaultPinDialog(
            isVaultInitialized = isVaultInitialized,
            isHardwareBiometricAvailable = isHardwareBiometricAvailable,
            onDismiss = { showChangePinDialog = false },
            onSubmitChangePin = { currentPinInput, newPinInput, confirmPinInput, onError, onSuccess ->
                coroutineScope.launch(Dispatchers.Default) {
                    val cleanCurrent = currentPinInput.trim()
                    val cleanNew = newPinInput.trim()
                    val cleanConfirm = confirmPinInput.trim()

                    if (cleanCurrent.isEmpty() || cleanNew.isEmpty() || cleanConfirm.isEmpty()) {
                        withContext(Dispatchers.Main) { onError("Please fill in all PIN fields") }
                        return@launch
                    }
                    if (cleanNew.length < 4) {
                        withContext(Dispatchers.Main) { onError("New PIN must be at least 4 digits") }
                        return@launch
                    }
                    if (cleanNew != cleanConfirm) {
                        withContext(Dispatchers.Main) { onError("New PIN and Confirm PIN do not match") }
                        return@launch
                    }
                    if (cleanNew == cleanCurrent) {
                        withContext(Dispatchers.Main) { onError("New PIN must be different from current PIN") }
                        return@launch
                    }

                    val wrapped = credentialStore.getWrappedVmk()
                    if (wrapped == null) {
                        withContext(Dispatchers.Main) { onError("Vault is not initialized") }
                        return@launch
                    }

                    val vmk = try {
                        VaultKeyManager.unwrapVaultMasterKey(wrapped, cleanCurrent.toCharArray())
                    } catch (_: Exception) {
                        null
                    }

                    if (vmk == null) {
                        withContext(Dispatchers.Main) { onError("Current PIN is incorrect") }
                        return@launch
                    }

                    try {
                        val newWrapped = VaultKeyManager.wrapVaultMasterKey(vmk, cleanNew.toCharArray())
                        credentialStore.saveCredentials(newWrapped)

                        if (credentialStore.isBiometricEnabled) {
                            credentialStore.enableBiometric(vmk)
                        }

                        if (container.session.isUnlocked.value) {
                            container.session.unlock(vmk)
                        }

                        VaultKeyManager.zeroize(vmk)

                        withContext(Dispatchers.Main) {
                            onSuccess()
                            Toast.makeText(context, "Vault PIN updated successfully ✨", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { onError("Failed to update PIN: ${e.message}") }
                    }
                }
            },
            onSubmitSetupPin = { newPinInput, confirmPinInput, enableBio, onError, onSuccess ->
                coroutineScope.launch(Dispatchers.Default) {
                    val cleanNew = newPinInput.trim()
                    val cleanConfirm = confirmPinInput.trim()

                    if (cleanNew.length < 4) {
                        withContext(Dispatchers.Main) { onError("PIN must be at least 4 digits") }
                        return@launch
                    }
                    if (cleanNew != cleanConfirm) {
                        withContext(Dispatchers.Main) { onError("PINs do not match") }
                        return@launch
                    }

                    try {
                        val vmk = VaultKeyManager.generateVaultMasterKey()
                        val wrapped = VaultKeyManager.wrapVaultMasterKey(vmk, cleanNew.toCharArray())
                        credentialStore.saveCredentials(wrapped)

                        val recoveryKey = VaultRecoveryKeyHelper.generateRecoveryKey()
                        val cleanRecovery = VaultRecoveryKeyHelper.normalizeKey(recoveryKey)
                        val recoveryWrapped = VaultKeyManager.wrapVaultMasterKey(vmk, cleanRecovery.toCharArray())
                        val encDisplay = VaultRecoveryKeyHelper.encryptRecoveryKeyForDisplay(recoveryKey, vmk)
                        credentialStore.saveRecoveryCredentials(recoveryWrapped, encDisplay)

                        if (enableBio && isHardwareBiometricAvailable) {
                            val bioSuccess = credentialStore.enableBiometric(vmk)
                            withContext(Dispatchers.Main) {
                                isBiometricEnabled = bioSuccess
                            }
                        }

                        container.session.unlock(vmk)
                        VaultKeyManager.zeroize(vmk)

                        withContext(Dispatchers.Main) {
                            isVaultInitialized = true
                            hasRecoveryKey = true
                            onSuccess()
                            Toast.makeText(context, "Vault Master PIN created successfully! 🎉", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { onError("Failed to create vault: ${e.message}") }
                    }
                }
            },
        )
    }

    // 13. Biometric Registration PIN Verification Dialog
    if (showBiometricAuthPinDialog) {
        BiometricAuthPinDialog(
            onDismiss = { showBiometricAuthPinDialog = false },
            onPinVerified = { enteredPin, onError, onSuccess ->
                coroutineScope.launch(Dispatchers.Default) {
                    val wrapped = credentialStore.getWrappedVmk()
                    if (wrapped == null) {
                        withContext(Dispatchers.Main) { onError("Vault not initialized") }
                        return@launch
                    }
                    val vmk = try {
                        VaultKeyManager.unwrapVaultMasterKey(wrapped, enteredPin.trim().toCharArray())
                    } catch (_: Exception) {
                        null
                    }
                    if (vmk == null) {
                        withContext(Dispatchers.Main) { onError("Incorrect Master PIN") }
                        return@launch
                    }
                    val success = credentialStore.enableBiometric(vmk)
                    VaultKeyManager.zeroize(vmk)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            isBiometricEnabled = true
                            onSuccess()
                            Toast.makeText(context, "Biometric authentication enabled", Toast.LENGTH_SHORT).show()
                        } else {
                            onError("Failed to register biometric key")
                        }
                    }
                }
            },
        )
    }

    // 14. Master Recovery Key PIN Prompt Dialog
    if (showRecoveryKeyPinPromptDialog) {
        RecoveryKeyPinPromptDialog(
            onDismiss = { showRecoveryKeyPinPromptDialog = false },
            onPinVerified = { enteredPin, onError, onSuccess ->
                coroutineScope.launch(Dispatchers.Default) {
                    val wrapped = credentialStore.getWrappedVmk()
                    if (wrapped == null) {
                        withContext(Dispatchers.Main) { onError("Vault not initialized") }
                        return@launch
                    }
                    val vmk = try {
                        VaultKeyManager.unwrapVaultMasterKey(wrapped, enteredPin.trim().toCharArray())
                    } catch (_: Exception) {
                        null
                    }
                    if (vmk == null) {
                        withContext(Dispatchers.Main) { onError("Incorrect Master PIN") }
                        return@launch
                    }

                    val existingEncrypted = credentialStore.getEncryptedDisplayRecoveryKey()
                    var key: String? = null
                    if (existingEncrypted != null) {
                        key = VaultRecoveryKeyHelper.decryptRecoveryKeyForDisplay(existingEncrypted, vmk)
                    }
                    if (key == null) {
                        val newKey = VaultRecoveryKeyHelper.generateRecoveryKey()
                        val clean = VaultRecoveryKeyHelper.normalizeKey(newKey)
                        val recWrapped = VaultKeyManager.wrapVaultMasterKey(vmk, clean.toCharArray())
                        val encDisplay = VaultRecoveryKeyHelper.encryptRecoveryKeyForDisplay(newKey, vmk)
                        credentialStore.saveRecoveryCredentials(recWrapped, encDisplay)
                        key = newKey
                    }
                    VaultKeyManager.zeroize(vmk)

                    withContext(Dispatchers.Main) {
                        hasRecoveryKey = true
                        activeRecoveryKeyToDisplay = key
                        showRecoveryKeyPinPromptDialog = false
                        showRecoveryKeyDialog = true
                    }
                }
            },
        )
    }

    // 15. Master Recovery Key Display Dialog
    if (showRecoveryKeyDialog && activeRecoveryKeyToDisplay != null) {
        RecoveryKeyDisplayDialog(
            recoveryKey = activeRecoveryKeyToDisplay ?: "",
            onDismiss = {
                showRecoveryKeyDialog = false
                activeRecoveryKeyToDisplay = null
            },
        )
    }
}

/**
 * Main Settings Menu matching the user reference photo with the 5 primary navigation categories.
 */
@Composable
private fun MainMenuContent(
    availableStorageBytes: Long,
    vaultStorageBytes: Long,
    trashSizeBytes: Long,
    pcAccessState: PcAccessState,
    onTogglePcAccess: (Boolean) -> Unit,
    onNavigateToSection: (SettingsSubScreen) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Quick Metrics Hero Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                border = BorderStroke(1.dp, Color(0xFF242428)),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1F2420)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color(0xFF30D158),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "VaultSync Pro",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF30D158)),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Hardware Encrypted • 100% Offline",
                                    color = Color(0xFF30D158),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Storage Metrics Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1C1C1E))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Device Free Space", color = Color(0xFF8E8E93), fontSize = 11.sp)
                            Text(
                                formatBytes(availableStorageBytes),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Column {
                            Text("Encrypted Vault", color = Color(0xFF8E8E93), fontSize = 11.sp)
                            Text(
                                formatBytes(vaultStorageBytes),
                                color = Color(0xFF64D2FF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Column {
                            Text("Recycle Bin", color = Color(0xFF8E8E93), fontSize = 11.sp)
                            Text(
                                formatBytes(trashSizeBytes),
                                color = Color(0xFFFF9F0A),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        // The 5 Main Settings Navigation Cards Container (Directly matching sample photo)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                border = BorderStroke(1.dp, Color(0xFF242428)),
            ) {
                Column {
                    // 1. Security & Privacy
                    SettingsNavigationTile(
                        icon = { SecurityIcon() },
                        title = "Security & Privacy",
                        subtitle = "PIN, biometrics, recovery key, auto-lock",
                        onClick = { onNavigateToSection(SettingsSubScreen.SECURITY_PRIVACY) },
                    )
                    SettingsCardDivider()

                    // 2. Storage & Recovery
                    SettingsNavigationTile(
                        icon = { StorageIcon() },
                        title = "Storage & Recovery",
                        subtitle = "Storage location, migration, vault discovery",
                        onClick = { onNavigateToSection(SettingsSubScreen.STORAGE_RECOVERY) },
                    )
                    SettingsCardDivider()

                    // 3. Appearance & Camouflage
                    SettingsNavigationTile(
                        icon = { AppearanceIcon() },
                        title = "Appearance & Camouflage",
                        subtitle = "Theme, icons, disguise, secret access gesture",
                        onClick = { onNavigateToSection(SettingsSubScreen.APPEARANCE_CAMOUFLAGE) },
                    )
                    SettingsCardDivider()

                    // 4. PC Access
                    val isPcRunning = pcAccessState.serverState == ServerState.RUNNING
                    SettingsNavigationTile(
                        icon = {
                            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                PcAccessIcon()
                                if (isPcRunning) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF30D158))
                                            .align(Alignment.TopEnd),
                                    )
                                }
                            }
                        },
                        title = "PC Access",
                        subtitle = if (isPcRunning) {
                            "Active on ${pcAccessState.url ?: "Port 8080"} • PIN ${pcAccessState.passcode}"
                        } else {
                            "Wi-Fi server, authentication, session controls"
                        },
                        onClick = { onNavigateToSection(SettingsSubScreen.PC_ACCESS) },
                        trailingContent = {
                            Switch(
                                checked = isPcRunning,
                                onCheckedChange = { onTogglePcAccess(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF30D158),
                                    uncheckedThumbColor = Color(0xFF8E8E93),
                                    uncheckedTrackColor = Color(0xFF2C2C2E),
                                ),
                            )
                        },
                    )
                    SettingsCardDivider()

                    // 5. About VaultSync
                    SettingsNavigationTile(
                        icon = { AboutIcon() },
                        title = "About VaultSync",
                        subtitle = "Version, diagnostics, help and licences",
                        onClick = { onNavigateToSection(SettingsSubScreen.ABOUT) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Modern, Compact & Colourful Developer Footer
        item {
            DeveloperFooter()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 1. Dedicated Security & Privacy Sub-Screen
 */
@Composable
private fun SecurityPrivacySubScreen(
    isVaultInitialized: Boolean,
    hasRecoveryKey: Boolean,
    isBiometricEnabled: Boolean,
    isHardwareBiometricAvailable: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    onOpenChangePin: () -> Unit,
    onOpenRecoveryKey: () -> Unit,
    onNavigateToVault: () -> Unit,
    onShowEncryptionInfo: () -> Unit,
    onShowRecoveryGuide: () -> Unit,
    autoLockTimeout: AutoLockTimeout,
    onOpenAutoLockDialog: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSection(title = "VAULT CREDENTIALS & ACCESS") {
                SettingsTile(
                    icon = "🔐",
                    iconBg = Color(0xFF192538),
                    title = if (isVaultInitialized) "Change Vault Password" else "Create Vault Password",
                    subtitle = if (isVaultInitialized) "Update your 4-12 digit master PIN password" else "Set up a Master PIN to protect your private vault",
                    status = if (isVaultInitialized) "Change" else "Setup",
                    statusColor = if (isVaultInitialized) Color(0xFF30D158) else Color(0xFFFF9F0A),
                    isClickable = true,
                    onClick = onOpenChangePin,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "📂",
                    iconBg = Color(0xFF1B2E24),
                    title = "Open Secret Vault",
                    subtitle = "Access private encrypted albums, photos and videos",
                    status = "Open",
                    isClickable = true,
                    onClick = onNavigateToVault,
                )
                SettingsDivider()
                SettingsSwitchTile(
                    icon = "👆",
                    iconBg = Color(0xFF281E38),
                    title = "Biometric Authentication",
                    subtitle = if (isHardwareBiometricAvailable) "Unlock vault with fingerprint or face" else "Device biometrics not enrolled or supported",
                    checked = isBiometricEnabled && isHardwareBiometricAvailable,
                    onCheckedChange = onToggleBiometric,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "🔑",
                    iconBg = Color(0xFF2A2016),
                    title = "Master Recovery Key",
                    subtitle = if (hasRecoveryKey) "Active • Offline 128-bit backup key configured"
                    else if (isVaultInitialized) "Action Needed • Tap to view or generate key"
                    else "Setup Vault First",
                    status = if (hasRecoveryKey) "Active" else if (isVaultInitialized) "Setup" else "Not Ready",
                    statusColor = if (hasRecoveryKey) Color(0xFF30D158) else Color(0xFFFF9F0A),
                    isClickable = true,
                    onClick = onOpenRecoveryKey,
                )
            }
        }

        item {
            SettingsSection(title = "APPLICATION LOCK & PROTECTION") {
                SettingsTile(
                    icon = "🛡️",
                    iconBg = Color(0xFF1B2E24),
                    title = "Screen Protection (FLAG_SECURE)",
                    subtitle = "Blocks screenshots and masks app in Android Recent Tasks",
                    status = "Active 🛡️",
                    statusColor = Color(0xFF30D158),
                    isClickable = false,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "⏱️",
                    iconBg = Color(0xFF1B2E24),
                    title = "Auto-Lock on Background",
                    subtitle = "Lock sensitive vault content after app is minimized",
                    status = autoLockTimeout.label,
                    statusColor = Color(0xFF30D158),
                    isClickable = true,
                    onClick = onOpenAutoLockDialog,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "📖",
                    iconBg = Color(0xFF1B2E24),
                    title = "Lockout Recovery Guide",
                    subtitle = "Dialer code (*#*#82858#*#*) & deep links (vaultsync://open)",
                    status = "View",
                    isClickable = true,
                    onClick = onShowRecoveryGuide,
                )
            }
        }

        item {
            SettingsSection(title = "CRYPTOGRAPHIC SPECIFICATIONS") {
                SettingsTile(
                    icon = "🔒",
                    iconBg = Color(0xFF192538),
                    title = "Hardware Encryption Standard",
                    subtitle = "AES-256-GCM chunked file format with per-file DEKs & KeyStore VMK",
                    status = "AES-256",
                    isClickable = true,
                    onClick = onShowEncryptionInfo,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * 2. Dedicated Storage & Recovery Sub-Screen
 */
@Composable
private fun StorageRecoverySubScreen(
    availableStorageBytes: Long,
    vaultStorageBytes: Long,
    trashStats: Pair<Int, Long>,
    isTrashEnabled: Boolean,
    retentionDays: Int,
    isSharedStorageActive: Boolean,
    vaultRootPath: String,
    onToggleTrash: (Boolean) -> Unit,
    onOpenRetentionDialog: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onOpenEmptyTrashDialog: () -> Unit,
    onOpenMigrationDialog: () -> Unit,
    onOpenDiscoveryDialog: () -> Unit,
    onCleanTempFiles: () -> Unit,
    onClearCache: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Storage Metrics Breakdown
        item {
            SettingsSection(title = "STORAGE BREAKDOWN") {
                SettingsTile(
                    icon = "📱",
                    iconBg = Color(0xFF1C1C1E),
                    title = "Available Device Space",
                    subtitle = "Remaining storage on internal flash",
                    status = formatBytes(availableStorageBytes),
                    isClickable = false,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "🔐",
                    iconBg = Color(0xFF162534),
                    title = "Encrypted Vault Storage",
                    subtitle = "AES-256 protected blobs and manifest metadata",
                    status = formatBytes(vaultStorageBytes),
                    statusColor = Color(0xFF64D2FF),
                    isClickable = false,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "♻️",
                    iconBg = Color(0xFF2C2214),
                    title = "Recycle Bin Space",
                    subtitle = "${trashStats.first} soft-deleted item(s)",
                    status = formatBytes(trashStats.second),
                    statusColor = Color(0xFFFF9F0A),
                    isClickable = false,
                )
            }
        }

        // Vault Location & Migration
        item {
            SettingsSection(title = "VAULT LOCATION & SURVIVABILITY") {
                SettingsTile(
                    icon = "📂",
                    iconBg = Color(0xFF1B2E24),
                    title = "Storage Mode",
                    subtitle = if (isSharedStorageActive) "Documents/VaultSync (Survives app uninstallation)" else "App-private internal files directory",
                    status = if (isSharedStorageActive) "Shared 📂" else "Internal 🔒",
                    statusColor = if (isSharedStorageActive) Color(0xFF38BDF8) else Color(0xFFF59E0B),
                    isClickable = true,
                    onClick = onOpenMigrationDialog,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "📦",
                    iconBg = Color(0xFF1B2E24),
                    title = if (isSharedStorageActive) "Shared Vault Configured" else "Migrate to Shared Storage",
                    subtitle = "Ensures encrypted vault survives app uninstall without data loss",
                    status = if (isSharedStorageActive) "Active" else "Migrate",
                    statusColor = if (isSharedStorageActive) Color(0xFF30D158) else Color(0xFFFF9F0A),
                    isClickable = true,
                    onClick = onOpenMigrationDialog,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "🔍",
                    iconBg = Color(0xFF1B2E24),
                    title = "Scan for Existing Vaults",
                    subtitle = "Discover `.vsf` containers in Documents/VaultSync",
                    status = "Discover",
                    isClickable = true,
                    onClick = onOpenDiscoveryDialog,
                )
            }
        }

        // Trash Bin Management
        item {
            SettingsSection(title = "TRASH & RECYCLE BIN") {
                SettingsSwitchTile(
                    icon = "♻️",
                    iconBg = Color(0xFF2C2214),
                    title = "Enable Recycle Bin",
                    subtitle = "Soft-deletes media for recovery before permanent purging",
                    checked = isTrashEnabled,
                    onCheckedChange = onToggleTrash,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "⏱️",
                    iconBg = Color(0xFF2C2214),
                    title = "Auto-Purge Duration",
                    subtitle = "Automatically clean files older than retention limit",
                    status = "$retentionDays Days",
                    isClickable = true,
                    onClick = onOpenRetentionDialog,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "♻️",
                    iconBg = Color(0xFF2C2214),
                    title = "Open Trash Bin",
                    subtitle = if (trashStats.first > 0) "${trashStats.first} item(s) • ${formatBytes(trashStats.second)}" else "Trash is empty",
                    status = if (trashStats.first > 0) "${trashStats.first}" else "Empty",
                    isClickable = true,
                    onClick = onNavigateToTrash,
                )
                if (trashStats.first > 0) {
                    SettingsDivider()
                    SettingsTile(
                        icon = "🧹",
                        iconBg = Color(0xFF2C1818),
                        title = "Empty Trash Bin",
                        subtitle = "Permanently shred all ${trashStats.first} deleted item(s) now",
                        status = "Purge",
                        statusColor = Color(0xFFFF453A),
                        isClickable = true,
                        onClick = onOpenEmptyTrashDialog,
                    )
                }
            }
        }

        // Maintenance & Cache
        item {
            SettingsSection(title = "STORAGE MAINTENANCE") {
                SettingsTile(
                    icon = "🧹",
                    iconBg = Color(0xFF242428),
                    title = "Clean Stale Temporary Files",
                    subtitle = "Purge unfinished .part chunks and staging artifacts",
                    status = "Clean",
                    isClickable = true,
                    onClick = onCleanTempFiles,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "⚡",
                    iconBg = Color(0xFF242428),
                    title = "Flush Memory Image Cache",
                    subtitle = "Clear in-memory preview cache to free device RAM",
                    status = "Clear RAM",
                    isClickable = true,
                    onClick = onClearCache,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * 3. Dedicated Appearance & Camouflage Sub-Screen (NO POPUP WINDOW!)
 * Launcher Icon Mode options and two-finger gesture settings are directly selectable inline.
 */
@Composable
private fun AppearanceCamouflageSubScreen(
    currentIconMode: IconMode,
    onSelectIconMode: (IconMode) -> Unit,
    isAppLockEnabled: Boolean,
    onToggleAppLock: (Boolean) -> Unit,
    isTwoFingerEnabled: Boolean,
    onToggleTwoFinger: (Boolean) -> Unit,
    selectedGestureType: TwoFingerGestureType,
    onSelectGestureType: (TwoFingerGestureType) -> Unit,
    gestureTestFeedback: String?,
    onGestureTriggered: () -> Unit,
    isLongPressVaultEnabled: Boolean,
    onToggleLongPressVault: (Boolean) -> Unit,
    onOpenRecoveryGuide: () -> Unit,
    onOpenThemeInfo: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Section 1: Launcher Appearance Mode Cards (Directly inline, no popup!)
        item {
            Column {
                Text(
                    text = "LAUNCHER ICON MODE",
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeOptionCard(
                        icon = "🖼️",
                        title = "Normal Mode",
                        subtitle = "Standard VaultSync gallery icon on home screen",
                        isSelected = currentIconMode == IconMode.NORMAL,
                        onClick = { onSelectIconMode(IconMode.NORMAL) },
                    )
                    ModeOptionCard(
                        icon = "🧮",
                        title = "Disguised: Calculator",
                        subtitle = "Working calculator cover. Secret gesture unlocks gallery",
                        isSelected = currentIconMode == IconMode.CALCULATOR,
                        onClick = { onSelectIconMode(IconMode.CALCULATOR) },
                    )
                    ModeOptionCard(
                        icon = "📝",
                        title = "Disguised: Notes",
                        subtitle = "Working notes memo cover. Secret gesture unlocks gallery",
                        isSelected = currentIconMode == IconMode.NOTES,
                        onClick = { onSelectIconMode(IconMode.NOTES) },
                    )
                    ModeOptionCard(
                        icon = "👻",
                        title = "Hidden Mode",
                        subtitle = "Launcher icon disabled. Access via *#*#82858#*#* or deep link",
                        isSelected = currentIconMode == IconMode.HIDDEN,
                        onClick = { onSelectIconMode(IconMode.HIDDEN) },
                    )
                }
            }
        }

        // Section 2: Normal Mode App Lock Option
        if (currentIconMode == IconMode.NORMAL) {
            item {
                SettingsSection(title = "NORMAL MODE SECURITY GATE") {
                    SettingsSwitchTile(
                        icon = "🔒",
                        iconBg = Color(0xFF1B2E24),
                        title = "Require Secret Access on Launch",
                        subtitle = "Prompts for master PIN or biometric before opening gallery",
                        checked = isAppLockEnabled,
                        onCheckedChange = onToggleAppLock,
                    )
                }
            }
        }

        // Section 3: Disguise Note (if Calculator or Notes active)
        if (currentIconMode == IconMode.CALCULATOR || currentIconMode == IconMode.NOTES) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                    border = BorderStroke(1.dp, Color(0xFF222226)),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🛡️", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Pure Disguise Security Active",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No obvious edit buttons or triggers are displayed on the disguise cover. Access to your gallery is granted exclusively via your two-finger gesture.",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        // Section 4: Secret Two-Finger Gesture
        item {
            SettingsSection(title = "SECRET TWO-FINGER GESTURE ACCESS") {
                SettingsSwitchTile(
                    icon = "👆",
                    iconBg = Color(0xFF1B2E24),
                    title = "Enable Two-Finger Gesture",
                    subtitle = "Unlocks the main gallery from disguised covers (Calculator/Notes/Hidden)",
                    checked = isTwoFingerEnabled,
                    onCheckedChange = onToggleTwoFinger,
                )

                if (isTwoFingerEnabled) {
                    SettingsDivider()
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = "Select Gesture Pattern:",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        TwoFingerGestureType.entries.forEach { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectGestureType(type) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedGestureType == type,
                                    onClick = { onSelectGestureType(type) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF30D158)),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = type.displayName,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = type.description,
                                        color = Color(0xFF8E8E93),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Interactive Gesture Test Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp)
                                .background(
                                    if (gestureTestFeedback != null) Color(0xFF1B382B) else Color(0xFF1C1C1E),
                                    RoundedCornerShape(12.dp),
                                )
                                .border(
                                    1.dp,
                                    if (gestureTestFeedback != null) Color(0xFF30D158) else Color(0xFF3A3A3C),
                                    RoundedCornerShape(12.dp),
                                )
                                .detectTwoFingerVaultGesture(
                                    enabled = true,
                                    gestureType = selectedGestureType,
                                    onGestureTriggered = onGestureTriggered,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = gestureTestFeedback
                                    ?: "👆 Test Area: Try your ${selectedGestureType.displayName.lowercase()} here",
                                color = if (gestureTestFeedback != null) Color(0xFF30D158) else Color(0xFF8E8E93),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        // Section 5: Gallery Secret Vault Access
        item {
            SettingsSection(title = "GALLERY SECRET SHORTCUTS") {
                SettingsSwitchTile(
                    icon = "🔒",
                    iconBg = Color(0xFF1B2E24),
                    title = "Secret Vault via Settings Long-Press",
                    subtitle = if (isLongPressVaultEnabled) "Hold ⚙️ in gallery to open Secret Vault • Lock buttons hidden" else "Standard lock button shown in gallery",
                    checked = isLongPressVaultEnabled,
                    onCheckedChange = onToggleLongPressVault,
                )
            }
        }

        // Section 6: Emergency Recovery Routes
        item {
            SettingsSection(title = "EMERGENCY RECOVERY ROUTES") {
                SettingsTile(
                    icon = "📞",
                    iconBg = Color(0xFF1B2E24),
                    title = "Emergency Dialer Code",
                    subtitle = "Dial *#*#82858#*#* from phone keypad to launch app even if hidden",
                    status = "*#*#82858#*#*",
                    isClickable = true,
                    onClick = onOpenRecoveryGuide,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "🌐",
                    iconBg = Color(0xFF1B2E24),
                    title = "Browser Recovery Route",
                    subtitle = "Open vaultsync://open in mobile browser to launch main app",
                    status = "vaultsync://open",
                    isClickable = true,
                    onClick = onOpenRecoveryGuide,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "📖",
                    iconBg = Color(0xFF1B2E24),
                    title = "Lockout Recovery Guide",
                    subtitle = "Complete instructions for recovering hidden or locked app",
                    status = "View",
                    isClickable = true,
                    onClick = onOpenRecoveryGuide,
                )
            }
        }

        // Section 7: Theme & Display
        item {
            SettingsSection(title = "THEME & DISPLAY") {
                SettingsTile(
                    icon = "🎨",
                    iconBg = Color(0xFF1F1F24),
                    title = "Pure AMOLED Black Theme",
                    subtitle = "True #000000 dark canvas with zero OLED backlight draw",
                    status = "AMOLED",
                    isClickable = true,
                    onClick = onOpenThemeInfo,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * Interactive card for selecting Launcher Icon Mode directly inline (no popup window).
 */
@Composable
private fun ModeOptionCard(
    icon: String,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) Color(0xFF30D158) else Color(0xFF242428),
                shape = RoundedCornerShape(14.dp),
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF1A261D) else Color(0xFF141416),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF223A2A) else Color(0xFF1E1E22)),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = if (isSelected) Color(0xFFB0D8BC) else Color(0xFF8E8E93),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = Color(0xFF30D158),
                    unselectedColor = Color(0xFF636366),
                ),
            )
        }
    }
}

/**
 * 4. Dedicated PC Access Sub-Screen
 */
@Composable
private fun PcAccessSubScreen(
    pcAccessState: PcAccessState,
    onTogglePcAccess: (Boolean) -> Unit,
    onRegeneratePasscode: () -> Unit,
    onNavigateToPcAccess: () -> Unit,
    onOpenServerPortInfo: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isRunning = pcAccessState.serverState == ServerState.RUNNING

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            // Master Server Control Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) Color(0xFF0F261B) else Color(0xFF141416),
                ),
                border = BorderStroke(
                    1.dp,
                    if (isRunning) Color(0xFF30D158).copy(alpha = 0.5f) else Color(0xFF242428),
                ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) Color(0xFF30D158) else Color(0xFF8E8E93)),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRunning) "SERVER LIVE" else "SERVER STOPPED",
                                color = if (isRunning) Color(0xFF30D158) else Color(0xFF8E8E93),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            )
                        }
                        Switch(
                            checked = isRunning,
                            onCheckedChange = { onTogglePcAccess(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF30D158),
                                uncheckedThumbColor = Color(0xFF8E8E93),
                                uncheckedTrackColor = Color(0xFF2C2C2E),
                            ),
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "PC Access & Wi-Fi Sync",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isRunning) {
                            "Stream, browse, and download media on any computer browser via local Wi-Fi."
                        } else {
                            "Turn on the server to access media from your computer browser without cloud or cables."
                        },
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )

                    if (isRunning) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // URL Card
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF163826),
                            border = BorderStroke(1.dp, Color(0xFF30D158).copy(alpha = 0.3f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "WEB BROWSER URL",
                                        color = Color(0xFF30D158),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = pcAccessState.url ?: "http://localhost:8080",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                pcAccessState.url?.let { url ->
                                    Button(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(url))
                                            Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF245B38)),
                                    ) {
                                        Text("Copy", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Passcode & Sessions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF163826),
                                border = BorderStroke(1.dp, Color(0xFF30D158).copy(alpha = 0.3f)),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("PAIRING PIN", color = Color(0xFF30D158), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = "Refresh",
                                            color = Color(0xFF64D2FF),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.clickable(onClick = onRegeneratePasscode),
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = pcAccessState.passcode,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 2.sp,
                                    )
                                }
                            }
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF163826),
                                border = BorderStroke(1.dp, Color(0xFF30D158).copy(alpha = 0.3f)),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("ACTIVE SESSIONS", color = Color(0xFF30D158), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${pcAccessState.activeSessionsCount} Connected",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Launch Full Screen Button
                    Button(
                        onClick = onNavigateToPcAccess,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) Color(0xFF245B38) else Color(0xFF1A2A3A),
                        ),
                    ) {
                        Text(
                            text = "Open Full Screen & QR Code →",
                            color = if (isRunning) Color(0xFF30D158) else Color(0xFF64D2FF),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = "WI-FI SERVER CONFIGURATION") {
                SettingsTile(
                    icon = "🔌",
                    iconBg = Color(0xFF162534),
                    title = "Server Port & Subnet Policy",
                    subtitle = "Embedded Ktor HTTP server on port 8080 (Private Subnet Protected)",
                    status = "Port 8080",
                    isClickable = true,
                    onClick = onOpenServerPortInfo,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "🛡️",
                    iconBg = Color(0xFF162534),
                    title = "Local Subnet Isolation",
                    subtitle = "Server strictly operates on local Wi-Fi. External web pages cannot bridge to it.",
                    status = "Protected",
                    statusColor = Color(0xFF30D158),
                    isClickable = false,
                )
            }
        }

        item {
            SettingsSection(title = "AUTHENTICATION & SESSION CONTROLS") {
                SettingsTile(
                    icon = "🔑",
                    iconBg = Color(0xFF162534),
                    title = "Dynamic Passcode Pairing",
                    subtitle = "Each session generates a temporary 6-digit PIN required on the browser",
                    status = "Enforced",
                    statusColor = Color(0xFF30D158),
                    isClickable = false,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "🔐",
                    iconBg = Color(0xFF162534),
                    title = "Strict Vault Elevation",
                    subtitle = "Vault media stays encrypted until Master PIN is submitted in the browser",
                    status = "Active",
                    statusColor = Color(0xFF30D158),
                    isClickable = false,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "⚡",
                    iconBg = Color(0xFF162534),
                    title = "Zero-Cloud Peer-to-Peer",
                    subtitle = "100% direct connection between phone and computer over Wi-Fi",
                    status = "Zero-Cloud",
                    isClickable = false,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * 5. Dedicated About VaultSync Sub-Screen
 */
@Composable
private fun AboutSubScreen(
    onOpenPermissionsDialog: () -> Unit,
    onOpenAboutDialog: () -> Unit,
    onOpenEncryptionInfo: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                border = BorderStroke(1.dp, Color(0xFF242428)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1F2420)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🛡️", fontSize = 32.sp)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "VaultSync Android",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Version 0.1.0 • Security Verified",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                }
            }
        }

        item {
            SettingsSection(title = "DIAGNOSTICS & SYSTEM PERMISSIONS") {
                SettingsTile(
                    icon = "📋",
                    iconBg = Color(0xFF24242A),
                    title = "Device Permissions Overview",
                    subtitle = "Inspect storage, biometric, and network permission grants",
                    status = "Inspect",
                    isClickable = true,
                    onClick = onOpenPermissionsDialog,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "🛡️",
                    iconBg = Color(0xFF24242A),
                    title = "Cryptographic Architecture",
                    subtitle = "AES-256-GCM + Android KeyStore hardware root of trust",
                    status = "Details",
                    isClickable = true,
                    onClick = onOpenEncryptionInfo,
                )
            }
        }

        item {
            SettingsSection(title = "PRIVACY ASSURANCE & LICENSES") {
                SettingsTile(
                    icon = "🔒",
                    iconBg = Color(0xFF1B2E24),
                    title = "100% Zero-Cloud Pledge",
                    subtitle = "No analytics, no telemetry, no tracking, zero cloud servers",
                    status = "Verified ✅",
                    statusColor = Color(0xFF30D158),
                    isClickable = false,
                )
                SettingsDivider()
                SettingsTile(
                    icon = "ℹ️",
                    iconBg = Color(0xFF24242A),
                    title = "App Architecture & Licenses",
                    subtitle = "Jetpack Compose, Room, Ktor, Coil, ExoPlayer/Media3",
                    status = "Licenses",
                    isClickable = true,
                    onClick = onOpenAboutDialog,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Modern, Compact & Colourful Developer Footer
        item {
            DeveloperFooter()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Navigation Tile matching the clean layout from the reference image.
 */
@Composable
private fun SettingsNavigationTile(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = if (subtitle.startsWith("Active")) Color(0xFF30D158) else Color(0xFF8E8E93),
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (trailingContent != null) {
            trailingContent()
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = "›",
            color = Color(0xFF636366),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SettingsCardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 62.dp, end = 18.dp),
        thickness = 0.5.dp,
        color = Color(0xFF242428),
    )
}

// ----------------- Custom Vector Outline Icons -----------------

@Composable
private fun SecurityIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            lineTo(w * 0.9f, h * 0.22f)
            cubicTo(w * 0.9f, h * 0.62f, w * 0.5f, h * 0.92f, w * 0.5f, h * 0.92f)
            cubicTo(w * 0.5f, h * 0.92f, w * 0.1f, h * 0.62f, w * 0.1f, h * 0.22f)
            close()
        }
        drawPath(path, color = Color.White, style = stroke)
        val check = Path().apply {
            moveTo(w * 0.35f, h * 0.48f)
            lineTo(w * 0.47f, h * 0.60f)
            lineTo(w * 0.68f, h * 0.38f)
        }
        drawPath(check, color = Color.White, style = stroke)
    }
}

@Composable
private fun StorageIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val r = 3.dp.toPx()
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.1f, h * 0.2f),
            size = Size(w * 0.8f, h * 0.62f),
            cornerRadius = CornerRadius(r, r),
            style = stroke,
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.1f, h * 0.55f),
            end = Offset(w * 0.9f, h * 0.55f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.38f, h * 0.72f),
            end = Offset(w * 0.62f, h * 0.72f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun AppearanceIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.1f)
            cubicTo(w * 0.85f, h * 0.1f, w * 0.95f, h * 0.45f, w * 0.85f, h * 0.72f)
            cubicTo(w * 0.78f, h * 0.9f, w * 0.58f, h * 0.92f, w * 0.45f, h * 0.82f)
            cubicTo(w * 0.38f, h * 0.75f, w * 0.28f, h * 0.75f, w * 0.2f, h * 0.82f)
            cubicTo(w * 0.08f, h * 0.7f, w * 0.08f, h * 0.4f, w * 0.2f, h * 0.22f)
            cubicTo(w * 0.28f, h * 0.12f, w * 0.38f, h * 0.1f, w * 0.5f, h * 0.1f)
            close()
        }
        drawPath(path, color = Color.White, style = stroke)
        drawCircle(color = Color.White, radius = 1.6.dp.toPx(), center = Offset(w * 0.42f, h * 0.32f))
        drawCircle(color = Color.White, radius = 1.6.dp.toPx(), center = Offset(w * 0.62f, h * 0.32f))
        drawCircle(color = Color.White, radius = 1.6.dp.toPx(), center = Offset(w * 0.72f, h * 0.52f))
    }
}

@Composable
private fun PcAccessIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.08f, h * 0.15f),
            size = Size(w * 0.65f, h * 0.52f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = stroke,
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.35f, h * 0.67f),
            end = Offset(w * 0.35f, h * 0.82f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.2f, h * 0.82f),
            end = Offset(w * 0.5f, h * 0.82f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.68f, h * 0.4f),
            size = Size(w * 0.26f, h * 0.46f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = stroke,
        )
    }
}

@Composable
private fun AboutIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color = Color.White, radius = w * 0.44f, style = stroke)
        drawCircle(color = Color.White, radius = 1.5.dp.toPx(), center = Offset(w * 0.5f, h * 0.32f))
        drawLine(
            color = Color.White,
            start = Offset(w * 0.5f, h * 0.46f),
            end = Offset(w * 0.5f, h * 0.70f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

// ----------------- Existing Helper UI Components -----------------

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            color = Color(0xFF8E8E93),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
            border = BorderStroke(1.dp, Color(0xFF222226)),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsTile(
    icon: String,
    iconBg: Color = Color(0xFF2C2C2E),
    title: String,
    subtitle: String,
    status: String,
    statusColor: Color = Color.White,
    isClickable: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0xFF8E8E93),
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF222226),
            ) {
                Text(
                    text = status,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (isClickable) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "›",
                    color = Color(0xFF636366),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchTile(
    icon: String,
    iconBg: Color = Color(0xFF2C2C2E),
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0xFF8E8E93),
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF30D158),
                uncheckedThumbColor = Color(0xFF8E8E93),
                uncheckedTrackColor = Color(0xFF2C2C2E),
            ),
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = Color(0xFF222226),
    )
}

@Composable
private fun CryptoDetailItem(label: String, value: String) {
    Column {
        Text(label, color = Color(0xFF8E8E93), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun PermissionStatusRow(name: String, isGranted: Boolean, isGoodWhenDisabled: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Color.White, fontSize = 13.sp)
        val isPositive = if (isGoodWhenDisabled) !isGranted else isGranted
        val text = if (isPositive) "Optimal ✅" else "Disabled ⚠️"
        val color = if (isPositive) Color(0xFF30D158) else Color(0xFFFF9F0A)
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

/**
 * Dialog to Change or Setup the Vault Master PIN / Password.
 */
@Composable
private fun ChangeVaultPinDialog(
    isVaultInitialized: Boolean,
    isHardwareBiometricAvailable: Boolean,
    onDismiss: () -> Unit,
    onSubmitChangePin: (currentPin: String, newPin: String, confirmPin: String, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit,
    onSubmitSetupPin: (newPin: String, confirmPin: String, enableBiometrics: Boolean, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit,
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var showCurrentPin by remember { mutableStateOf(false) }
    var showNewPin by remember { mutableStateOf(false) }
    var showConfirmPin by remember { mutableStateOf(false) }
    var enableBiometrics by remember { mutableStateOf(isHardwareBiometricAvailable) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        containerColor = Color(0xFF1C1C1E),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF192538)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🔐", fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isVaultInitialized) "Change Vault Password" else "Create Vault Password",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = if (isVaultInitialized) "Enter current and new Master PIN" else "Set a master PIN (min 4 digits)",
                        color = Color(0xFF8E8E93),
                        fontSize = 12.sp,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (errorMessage != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF381818)),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFFF453A),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                if (isVaultInitialized) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = {
                            if (it.length <= 12) {
                                currentPin = it
                                errorMessage = null
                            }
                        },
                        label = { Text("Current Master PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = if (showCurrentPin) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showCurrentPin = !showCurrentPin }) {
                                Text(if (showCurrentPin) "👁️" else "🙈", fontSize = 16.sp)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF30D158),
                            unfocusedBorderColor = Color(0xFF3A3A3C),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = Color(0xFF30D158),
                            unfocusedLabelColor = Color(0xFF8E8E93),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = newPin,
                    onValueChange = {
                        if (it.length <= 12) {
                            newPin = it
                            errorMessage = null
                        }
                    },
                    label = { Text(if (isVaultInitialized) "New Master PIN" else "Enter Master PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showNewPin) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showNewPin = !showNewPin }) {
                            Text(if (showNewPin) "👁️" else "🙈", fontSize = 16.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF30D158),
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Color(0xFF30D158),
                        unfocusedLabelColor = Color(0xFF8E8E93),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = {
                        if (it.length <= 12) {
                            confirmPin = it
                            errorMessage = null
                        }
                    },
                    label = { Text("Confirm Master PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showConfirmPin) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showConfirmPin = !showConfirmPin }) {
                            Text(if (showConfirmPin) "👁️" else "🙈", fontSize = 16.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF30D158),
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Color(0xFF30D158),
                        unfocusedLabelColor = Color(0xFF8E8E93),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!isVaultInitialized && isHardwareBiometricAvailable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Enable Biometric Authentication",
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                        Switch(
                            checked = enableBiometrics,
                            onCheckedChange = { enableBiometrics = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF30D158),
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSubmitting = true
                    errorMessage = null
                    if (isVaultInitialized) {
                        onSubmitChangePin(
                            currentPin,
                            newPin,
                            confirmPin,
                            { err ->
                                errorMessage = err
                                isSubmitting = false
                            },
                            {
                                isSubmitting = false
                                onDismiss()
                            },
                        )
                    } else {
                        onSubmitSetupPin(
                            newPin,
                            confirmPin,
                            enableBiometrics,
                            { err ->
                                errorMessage = err
                                isSubmitting = false
                            },
                            {
                                isSubmitting = false
                                onDismiss()
                            },
                        )
                    }
                },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
            ) {
                Text(
                    text = if (isSubmitting) "Saving..." else if (isVaultInitialized) "Update PIN" else "Create PIN",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
            ) {
                Text("Cancel", color = Color.White)
            }
        },
    )
}

/**
 * Dialog to verify Master PIN before enabling Biometrics.
 */
@Composable
private fun BiometricAuthPinDialog(
    onDismiss: () -> Unit,
    onPinVerified: (String, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        containerColor = Color(0xFF1C1C1E),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("👆", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Enable Biometrics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Enter your Vault Master PIN to link biometric authentication securely with the Android KeyStore:",
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp,
                )
                if (errorMessage != null) {
                    Text(errorMessage ?: "", color = Color(0xFFFF453A), fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 12) {
                            pin = it
                            errorMessage = null
                        }
                    },
                    label = { Text("Master PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPin = !showPin }) {
                            Text(if (showPin) "👁️" else "🙈", fontSize = 16.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF30D158),
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isVerifying = true
                    onPinVerified(
                        pin,
                        { err ->
                            errorMessage = err
                            isVerifying = false
                        },
                        {
                            isVerifying = false
                            onDismiss()
                        },
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
            ) {
                Text("Verify & Enable", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        },
    )
}

/**
 * Dialog to verify Master PIN before viewing/generating Master Recovery Key.
 */
@Composable
private fun RecoveryKeyPinPromptDialog(
    onDismiss: () -> Unit,
    onPinVerified: (String, onError: (String) -> Unit, onSuccess: (String) -> Unit) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        containerColor = Color(0xFF1C1C1E),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔑", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Master Recovery Key", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Enter your Vault Master PIN to view or backup your 128-bit Master Recovery Key:",
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp,
                )
                if (errorMessage != null) {
                    Text(errorMessage ?: "", color = Color(0xFFFF453A), fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 12) {
                            pin = it
                            errorMessage = null
                        }
                    },
                    label = { Text("Master PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPin = !showPin }) {
                            Text(if (showPin) "👁️" else "🙈", fontSize = 16.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF30D158),
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isVerifying = true
                    onPinVerified(
                        pin,
                        { err ->
                            errorMessage = err
                            isVerifying = false
                        },
                        { key ->
                            isVerifying = false
                            onDismiss()
                        },
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
            ) {
                Text("View Key", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        },
    )
}

/**
 * Modern, Colourful & Compact Developer Footer Component
 * Features dark premium gradient card with subtle cyan, blue, and purple accents,
 * clickable phone and website buttons, 4 core philosophy badges, and responsive layout.
 */
@Composable
fun DeveloperFooter(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    fun openDialer() {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+919419927293")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Phone: +91 9419927293", Toast.LENGTH_LONG).show()
        }
    }

    fun openWebsite() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shintopc.vercel.app")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Visit: https://shintopc.vercel.app", Toast.LENGTH_LONG).show()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF0C101B) else Color(0xFF131826),
        ),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF38BDF8).copy(alpha = 0.55f), // Radiant Cyan
                    Color(0xFF818CF8).copy(alpha = 0.35f), // Soft Indigo/Blue
                    Color(0xFFC084FC).copy(alpha = 0.55f), // Luminous Purple
                ),
            ),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0B132B).copy(alpha = 0.95f), // Deep Navy
                            Color(0xFF1C1A4A).copy(alpha = 0.88f), // Midnight Indigo
                            Color(0xFF0F1E36).copy(alpha = 0.95f), // Deep Cyan/Blue
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Subtle glowing accent top indicator bar
                Box(
                    modifier = Modifier
                        .width(46.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF00F2FE), // Bright Cyan
                                    Color(0xFF818CF8), // Blue Accent
                                    Color(0xFFC084FC), // Bright Purple
                                ),
                            ),
                        ),
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Overline Badge: "DEVELOPED BY"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.85f))
                        .border(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.65f),
                                    Color(0xFFA855F7).copy(alpha = 0.65f),
                                ),
                            ),
                            RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF38BDF8)),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "DEVELOPED BY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = Color(0xFFBAE6FD),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Developer Brand Name: "Shinto PC"
                Text(
                    text = "Shinto PC",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                    color = Color.White,
                )

                Spacer(modifier = Modifier.height(3.dp))

                // Subtitle: "Android Apps & Software"
                Text(
                    text = "Android Apps & Software",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF94A3B8),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Two Colourful, Rounded Contact Buttons (Responsive Row / Column)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth < 360.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ContactButton(
                                icon = Icons.Default.Phone,
                                label = "+91 9419927293",
                                gradient = Brush.horizontalGradient(
                                    listOf(Color(0xFF0284C7), Color(0xFF2563EB)),
                                ),
                                onClick = { openDialer() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ContactButton(
                                icon = Icons.Default.Language,
                                label = "shintopc.vercel.app",
                                trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                gradient = Brush.horizontalGradient(
                                    listOf(Color(0xFF7C3AED), Color(0xFFC026D3)),
                                ),
                                onClick = { openWebsite() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ContactButton(
                                icon = Icons.Default.Phone,
                                label = "+91 9419927293",
                                gradient = Brush.horizontalGradient(
                                    listOf(Color(0xFF0284C7), Color(0xFF2563EB)),
                                ),
                                onClick = { openDialer() },
                                modifier = Modifier.weight(1f),
                            )
                            ContactButton(
                                icon = Icons.Default.Language,
                                label = "shintopc.vercel.app",
                                trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                gradient = Brush.horizontalGradient(
                                    listOf(Color(0xFF7C3AED), Color(0xFFC026D3)),
                                ),
                                onClick = { openWebsite() },
                                modifier = Modifier.weight(1.18f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Four Small Feature Badges with Icons & Labels (2x2 Grid)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeveloperFeatureBadge(
                            icon = Icons.Default.Code,
                            label = "Independent Developer",
                            iconTint = Color(0xFF38BDF8),
                            containerBg = Color(0xFF0C4A6E).copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                        )
                        DeveloperFeatureBadge(
                            icon = Icons.Default.Shield,
                            label = "Privacy Focused",
                            iconTint = Color(0xFF4ADE80),
                            containerBg = Color(0xFF064E3B).copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeveloperFeatureBadge(
                            icon = Icons.Default.Favorite,
                            label = "Built for You",
                            iconTint = Color(0xFFF472B6),
                            containerBg = Color(0xFF831843).copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                        )
                        DeveloperFeatureBadge(
                            icon = Icons.Default.Celebration,
                            label = "Thank You for Your Support",
                            iconTint = Color(0xFFFBBF24),
                            containerBg = Color(0xFF78350F).copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                HorizontalDivider(
                    color = Color(0xFF334155).copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Baseline signature: "VaultSync • Developed by Shinto PC"
                Text(
                    text = "VaultSync • Developed by Shinto PC",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF64748B),
                    letterSpacing = 0.3.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Modern, Colourful Rounded Contact Button with Subtle Gradient and Ripple Feedback
 */
@Composable
private fun ContactButton(
    icon: ImageVector,
    label: String,
    gradient: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp)),
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 3.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trailingIcon != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Compact Feature Badge Pill with Tinted Icon and Dual-Line Responsive Label
 */
@Composable
private fun DeveloperFeatureBadge(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    containerBg: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF131C2E).copy(alpha = 0.7f))
            .border(1.dp, Color(0xFF26334D).copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(containerBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFCBD5E1),
            lineHeight = 12.5.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

