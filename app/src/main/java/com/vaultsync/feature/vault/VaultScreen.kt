package com.vaultsync.feature.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun VaultScreen(
    viewModel: VaultViewModel = viewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    if (!uiState.isUnlocked) {
        VaultLockScreen(
            state = uiState,
            onSetupVault = { pin, bio -> viewModel.setupVault(pin, bio) },
            onUnlockWithPin = { pin -> viewModel.unlockWithPin(pin) },
            onUnlockWithBiometric = { cipher -> viewModel.unlockWithBiometric(cipher) },
            onNavigateBack = onNavigateBack,
            viewModel = viewModel,
        )
    } else {
        VaultMainScreen(
            viewModel = viewModel,
            onNavigateBack = onNavigateBack,
        )
    }

    if (uiState.showRecoveryKeyDialog && uiState.activeRecoveryKey != null) {
        RecoveryKeyDisplayDialog(
            recoveryKey = uiState.activeRecoveryKey!!,
            onDismiss = { viewModel.dismissRecoveryKeyDialog() },
        )
    }

    if (uiState.showForgotPinDialog) {
        ForgotPinRecoveryDialog(
            isVerified = uiState.isRecoveryVerified,
            errorMessage = uiState.recoveryErrorMessage,
            onDismiss = { viewModel.dismissForgotPinDialog() },
            onVerifyKey = { key, callback ->
                viewModel.verifyRecoveryKey(key, callback)
            },
            onResetPin = { newPin, enableBiometric, callback ->
                viewModel.resetPinWithRecoveryKey(newPin, enableBiometric, callback)
            },
        )
    }

    if (uiState.showMigrationDialog) {
        VaultMigrationDialog(
            isOpen = true,
            isMigrationInProgress = uiState.isMigrationInProgress,
            progress = uiState.migrationProgress,
            onDismiss = { viewModel.closeMigrationDialog() },
            onStartMigration = { destDir ->
                viewModel.startMigrationToSharedStorage(destDir) { _, _ -> }
            },
        )
    }

    if (uiState.showRestoreDialog && uiState.detectedVault != null) {
        VaultRestoreDialog(
            isOpen = true,
            detectedVault = uiState.detectedVault,
            onDismiss = { viewModel.closeRestoreDialog() },
            onRestore = { credential, isRecoveryKey ->
                viewModel.recoverDetectedVault(credential, isRecoveryKey) { _, _ -> }
            },
        )
    }
}
