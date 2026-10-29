package com.vaultsync.feature.vault

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

@Composable
fun VaultLockScreen(
    state: VaultUiState,
    onSetupVault: (String, Boolean) -> Unit,
    onUnlockWithPin: (String) -> Unit,
    onUnlockWithBiometric: (javax.crypto.Cipher) -> Unit,
    onNavigateBack: () -> Unit = {},
    viewModel: VaultViewModel,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    var enteredPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isConfirmingSetup by remember { mutableStateOf(false) }
    var enableBiometricSetup by remember { mutableStateOf(true) }
    var useAlphanumericInput by remember { mutableStateOf(false) }

    val manageStorageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.checkForExistingVault()
    }

    fun triggerBiometricPrompt() {
        if (activity == null) return
        val cipher = viewModel.getBiometricCipher() ?: return

        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authCipher = result.cryptoObject?.cipher ?: cipher
                    onUnlockWithBiometric(authCipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Secure Vault")
            .setSubtitle("Authenticate using your biometric credential")
            .setNegativeButtonText("Use PIN / Password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        try {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Auto-prompt biometrics once on screen entry if enabled
    LaunchedEffect(state.isInitialized, state.canUseBiometric) {
        if (state.isInitialized && state.canUseBiometric) {
            triggerBiometricPrompt()
        }
    }

    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            enteredPin = ""
            confirmPin = ""
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.TopStart),
            ) {
                Text("←", fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
            // Shield Icon & Vault Branding
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.isInitialized) "🔒" else "🛡️",
                    fontSize = 38.sp,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = if (!state.isInitialized) "Set Up Vault Security" else "Vault Locked",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (!state.isInitialized) {
                    if (!isConfirmingSetup) "Create a master PIN or password (min 4 characters)"
                    else "Confirm your master PIN or password to initialize"
                } else {
                    "Enter your master PIN to access encrypted files"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Error display
            state.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Info display
            state.infoMessage?.let { info ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0369A1)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    Text(
                        text = info,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Existing Vault Detected Card
            if (!state.isInitialized && state.detectedVault != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF0284C7), RoundedCornerShape(16.dp))
                        .padding(bottom = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🛡️", fontSize = 28.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Existing Vault Discovered!",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                )
                                Text(
                                    text = "Found ${state.detectedVault.blobCount} files in Documents/VaultSync",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "A previous vault was found. Restore it using your Master PIN or Recovery Key without re-importing.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.openRestoreDialog() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            Text("Restore Detected Vault 🔑", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (useAlphanumericInput) {
                // Password text field
                OutlinedTextField(
                    value = if (state.isInitialized || !isConfirmingSetup) enteredPin else confirmPin,
                    onValueChange = {
                        if (state.isInitialized || !isConfirmingSetup) enteredPin = it else confirmPin = it
                    },
                    label = {
                        Text(
                            if (state.isInitialized) "Master Password"
                            else if (!isConfirmingSetup) "Create Password" else "Confirm Password",
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!state.isInitialized) {
                                if (!isConfirmingSetup) {
                                    if (enteredPin.length >= 4) {
                                        isConfirmingSetup = true
                                        viewModel.clearMessages()
                                    } else {
                                        viewModel.setErrorMessage("Password must be at least 4 characters.")
                                    }
                                } else {
                                    if (enteredPin == confirmPin) {
                                        onSetupVault(enteredPin, enableBiometricSetup)
                                    } else {
                                        viewModel.setErrorMessage("Passwords do not match. Please try again.")
                                        confirmPin = ""
                                    }
                                }
                            } else {
                                if (enteredPin.isNotEmpty()) {
                                    onUnlockWithPin(enteredPin)
                                }
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (!state.isInitialized) {
                            if (!isConfirmingSetup) {
                                if (enteredPin.length >= 4) {
                                    isConfirmingSetup = true
                                    viewModel.clearMessages()
                                } else {
                                    viewModel.setErrorMessage("Password must be at least 4 characters.")
                                }
                            } else {
                                if (enteredPin == confirmPin) {
                                    onSetupVault(enteredPin, enableBiometricSetup)
                                } else {
                                    viewModel.setErrorMessage("Passwords do not match. Please try again.")
                                    confirmPin = ""
                                }
                            }
                        } else {
                            if (enteredPin.isNotEmpty()) {
                                onUnlockWithPin(enteredPin)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (!state.isInitialized) {
                            if (!isConfirmingSetup) "Continue" else "Secure Vault"
                        } else {
                            "Unlock"
                        },
                    )
                }

                if (state.isInitialized && state.canUseBiometric) {
                    Spacer(modifier = Modifier.height(10.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { triggerBiometricPrompt() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Unlock with Biometrics 👆")
                    }
                }
            } else {
                // PIN visual dots
                val targetPin = if (state.isInitialized || !isConfirmingSetup) enteredPin else confirmPin
                PinDotsDisplay(pinLength = targetPin.length)

                Spacer(modifier = Modifier.height(28.dp))

                // Custom numeric keypad
                NumericKeypad(
                    onDigitClick = { digit ->
                        if (state.isInitialized || !isConfirmingSetup) {
                            if (enteredPin.length < 8) {
                                enteredPin += digit
                            }
                        } else {
                            if (confirmPin.length < 8) {
                                confirmPin += digit
                            }
                        }
                    },
                    onBackspaceClick = {
                        if (state.isInitialized || !isConfirmingSetup) {
                            if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                        } else {
                            if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
                        }
                    },
                    onBiometricClick = if (state.isInitialized && state.canUseBiometric) {
                        { triggerBiometricPrompt() }
                    } else null,
                    onConfirmClick = {
                        if (!state.isInitialized) {
                            if (!isConfirmingSetup) {
                                if (enteredPin.length >= 4) {
                                    isConfirmingSetup = true
                                    viewModel.clearMessages()
                                } else {
                                    viewModel.setErrorMessage("PIN must be at least 4 digits.")
                                }
                            } else {
                                if (enteredPin == confirmPin) {
                                    onSetupVault(enteredPin, enableBiometricSetup)
                                } else {
                                    viewModel.setErrorMessage("PINs do not match. Please re-enter.")
                                    confirmPin = ""
                                }
                            }
                        } else {
                            if (enteredPin.isNotEmpty()) {
                                onUnlockWithPin(enteredPin)
                            }
                        }
                    },
                    showConfirm = true,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Biometric checkbox during setup
            if (!state.isInitialized && isConfirmingSetup) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { enableBiometricSetup = !enableBiometricSetup },
                ) {
                    Checkbox(
                        checked = enableBiometricSetup,
                        onCheckedChange = { enableBiometricSetup = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        text = "Enable Biometric (Fingerprint) Unlock",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Toggle between numeric keypad and alphanumeric keyboard
            TextButton(
                onClick = {
                    useAlphanumericInput = !useAlphanumericInput
                    enteredPin = ""
                    confirmPin = ""
                    isConfirmingSetup = false
                },
            ) {
                Text(
                    text = if (useAlphanumericInput) "Switch to Numeric PIN Pad" else "Use Alphanumeric Password",
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Forgot PIN button when vault is locked
            if (state.isInitialized) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { viewModel.openForgotPinDialog() },
                ) {
                    Text(
                        text = "Forgot PIN? Recover Vault 🔑",
                        color = Color(0xFF0A84FF),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }

            if (!state.isInitialized && isConfirmingSetup) {
                TextButton(
                    onClick = {
                        isConfirmingSetup = false
                        confirmPin = ""
                    },
                ) {
                    Text("Back to change PIN", color = MaterialTheme.colorScheme.secondary)
                }
            }

            if (!state.isInitialized && state.detectedVault == null && !isConfirmingSetup) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                manageStorageLauncher.launch(intent)
                            } catch (e: Exception) {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    manageStorageLauncher.launch(intent)
                                } catch (e2: Exception) {
                                    viewModel.checkForExistingVault()
                                }
                            }
                        } else {
                            viewModel.checkForExistingVault()
                        }
                    },
                ) {
                    Text(
                        text = "Scan Documents for Existing Vault 🔍",
                        color = Color(0xFF38BDF8),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
}

@Composable
fun PinDotsDisplay(pinLength: Int, maxVisible: Int = 6) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val total = maxOf(maxVisible, pinLength)
        for (i in 0 until total) {
            val isFilled = i < pinLength
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFilled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
fun NumericKeypad(
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    onBiometricClick: (() -> Unit)? = null,
    onConfirmClick: () -> Unit,
    showConfirm: Boolean = true,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                for (digit in row) {
                    KeypadButton(text = digit, onClick = { onDigitClick(digit) })
                }
            }
        }

        // Bottom row: Biometric/Blank, 0, Backspace/Confirm
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBiometricClick != null) {
                KeypadIconButton(text = "👆", onClick = onBiometricClick)
            } else {
                Spacer(modifier = Modifier.size(68.dp))
            }

            KeypadButton(text = "0", onClick = { onDigitClick("0") })

            KeypadIconButton(text = "⌫", onClick = onBackspaceClick)
        }

        if (showConfirm) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onConfirmClick,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.width(250.dp).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(text = "Enter", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun KeypadButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun KeypadIconButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
