package com.vaultsync.feature.stealth

import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vaultsync.core.stealth.AppLockManager
import com.vaultsync.core.vault.VaultContainer

@Composable
fun SecretAppEntryDialog(
    onUnlockSuccess: () -> Unit,
    onDismiss: () -> Unit,
    onNavigateToSetup: () -> Unit = {},
    onNavigateToRecovery: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val container = remember { VaultContainer.get(context) }
    val credentialStore = container.credentialStore

    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun triggerBiometric() {
        if (activity == null || !credentialStore.canUseBiometric()) return
        val cipher = credentialStore.getBiometricCipherForDecryption() ?: return

        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authCipher = result.cryptoObject?.cipher ?: cipher
                    val unlocked = AppLockManager.unlockWithBiometricCipher(context, authCipher)
                    if (unlocked) {
                        onUnlockSuccess()
                    } else {
                        errorMessage = "Biometric authentication failed to unwrap key"
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        errorMessage = errString.toString()
                    }
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("VaultSync Access")
            .setSubtitle("Unlock main app using fingerprint or face")
            .setNegativeButtonText("Use PIN")
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    LaunchedEffect(Unit) {
        if (credentialStore.canUseBiometric()) {
            triggerBiometric()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF32D74B)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🛡️", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "VaultSync Access",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "Secret gesture verified",
                        color = Color(0xFF32D74B),
                        fontSize = 12.sp,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!credentialStore.isInitialized) {
                    Text(
                        text = "VaultSync master PIN has not been configured yet. Tap below to access the main app.",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            AppLockManager.setUnlockedDirectly()
                            onUnlockSuccess()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF32D74B)),
                    ) {
                        Text("Open Main App", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = "Enter your secret master PIN or use biometrics to open the main app.",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            enteredPin = it
                            errorMessage = null
                        },
                        placeholder = { Text("Enter Secret PIN", color = Color(0xFF636366)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF32D74B),
                            unfocusedBorderColor = Color(0xFF3A3A3C),
                            focusedContainerColor = Color(0xFF1C1C1E),
                            unfocusedContainerColor = Color(0xFF1C1C1E),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage!!,
                            color = Color(0xFFFF453A),
                            fontSize = 12.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Biometric button if available
                    if (credentialStore.canUseBiometric()) {
                        OutlinedButton(
                            onClick = { triggerBiometric() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("👆 Unlock with Biometrics", color = Color.White, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onNavigateToRecovery) {
                            Text("Forgot PIN?", color = Color(0xFF64D2FF), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (credentialStore.isInitialized) {
                Button(
                    onClick = {
                        val unlocked = AppLockManager.unlockWithPin(context, enteredPin)
                        if (unlocked) {
                            onUnlockSuccess()
                        } else {
                            errorMessage = "Incorrect secret PIN. Try again."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF32D74B)),
                ) {
                    Text("Unlock Main App", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        },
        containerColor = Color(0xFF1C1C1E),
    )
}
