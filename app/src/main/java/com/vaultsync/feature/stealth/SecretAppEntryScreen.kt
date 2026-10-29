package com.vaultsync.feature.stealth

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vaultsync.core.stealth.AppLockManager
import com.vaultsync.core.stealth.TwoFingerGestureType
import com.vaultsync.core.stealth.detectTwoFingerVaultGesture
import com.vaultsync.core.vault.VaultContainer

@Composable
fun SecretAppEntryScreen(
    title: String = "VaultSync Access",
    subtitle: String = "Enter master PIN or use biometrics to access the main app",
    isTwoFingerGestureEnabled: Boolean = true,
    gestureType: TwoFingerGestureType = TwoFingerGestureType.SWIPE_DOWN,
    onUnlockSuccess: () -> Unit,
    onNavigateToSetup: () -> Unit = {},
    onNavigateToRecovery: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val container = remember { VaultContainer.get(context) }
    val credentialStore = container.credentialStore

    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPinFieldVisible by remember { mutableStateOf(!isTwoFingerGestureEnabled || !credentialStore.isInitialized) }

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
                        errorMessage = "Biometric authentication failed"
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
            .setSubtitle("Authenticate to access main app")
            .setNegativeButtonText("Use PIN")
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    LaunchedEffect(Unit) {
        if (credentialStore.canUseBiometric()) {
            triggerBiometric()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .detectTwoFingerVaultGesture(
                enabled = isTwoFingerGestureEnabled,
                gestureType = gestureType,
                onGestureTriggered = {
                    AppLockManager.setUnlockedDirectly()
                    onUnlockSuccess()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🛡️", fontSize = 28.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (!isPinFieldVisible) {
                        "Swipe down with two fingers anywhere on this screen to open secret entry"
                    } else {
                        subtitle
                    },
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (!credentialStore.isInitialized) {
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
                } else if (!isPinFieldVisible) {
                    Button(
                        onClick = { isPinFieldVisible = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                    ) {
                        Text("👆 Enter PIN / Biometrics", color = Color.White)
                    }
                } else {
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            enteredPin = it
                            errorMessage = null
                        },
                        placeholder = { Text("Secret PIN", color = Color(0xFF636366)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF32D74B),
                            unfocusedBorderColor = Color(0xFF3A3A3C),
                            focusedContainerColor = Color(0xFF2C2C2E),
                            unfocusedContainerColor = Color(0xFF2C2C2E),
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

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val unlocked = AppLockManager.unlockWithPin(context, enteredPin)
                            if (unlocked) {
                                onUnlockSuccess()
                            } else {
                                errorMessage = "Incorrect PIN. Please try again."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF32D74B)),
                    ) {
                        Text("Unlock Main App", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    if (credentialStore.canUseBiometric()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { triggerBiometric() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("👆 Unlock with Biometrics", color = Color.White, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = onNavigateToRecovery) {
                        Text("Forgot PIN / Recovery Guide", color = Color(0xFF64D2FF), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
