package com.vaultsync.feature.vault

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vaultsync.core.security.VaultRecoveryKeyHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog displaying the generated Vault Recovery Key with options to:
 * 1. View the formatted key in monospace
 * 2. Copy the key to clipboard
 * 3. Save the key to a file using Android's system Save File dialog (SAF)
 */
@Composable
fun RecoveryKeyDisplayDialog(
    recoveryKey: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isSaving = true
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            val content = VaultRecoveryKeyHelper.createBackupFileContent(recoveryKey)
                            stream.write(content.toByteArray(Charsets.UTF_8))
                        }
                    }
                    Toast.makeText(context, "Recovery key saved to file successfully!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isSaving = false
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Key Icon Badge
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🔑", fontSize = 28.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Vault Recovery Key",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Keep this key safe and offline. If you forget your master PIN, this recovery key is the ONLY way to recover your vault files.",
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Recovery Key Monospace Display Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38383A)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = recoveryKey,
                            color = Color(0xFF30D158),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            letterSpacing = 1.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons Row: Copy and Save to File
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Copy to Clipboard Button
                    Button(
                        onClick = {
                            try {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Vault Recovery Key", recoveryKey).apply {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        description.extras = android.os.PersistableBundle().apply {
                                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                                        }
                                    }
                                }
                                cm.setPrimaryClip(clip)
                            } catch (_: Exception) {
                                clipboardManager.setText(AnnotatedString(recoveryKey))
                            }
                            Toast.makeText(context, "Recovery key copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("📋 Copy Key", color = Color.White, fontSize = 13.sp)
                    }

                    // Save to File using Android Save Dialog (SAF)
                    Button(
                        onClick = {
                            createDocumentLauncher.launch("VaultSync_Recovery_Key.txt")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        enabled = !isSaving,
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("💾 Save File", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Done / Dismiss Button
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("I Have Saved My Key", fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

/**
 * Dialog allowing the user to recover a forgotten Vault PIN using either:
 * - Entering/pasting the Recovery Key string
 * - Selecting their saved recovery key file using Android's file picker
 * - Resetting to a new Master PIN upon verification
 */
@Composable
fun ForgotPinRecoveryDialog(
    isVerified: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onVerifyKey: (String, (Boolean, String?) -> Unit) -> Unit,
    onResetPin: (String, Boolean, (Boolean, String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var enteredKey by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmNewPin by remember { mutableStateOf("") }
    var enableBiometric by remember { mutableStateOf(true) }
    var isVerifying by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }

    // File picker launcher to import recovery key from saved backup file
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val fileText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().readText()
                        }
                    } ?: ""

                    val extractedKey = VaultRecoveryKeyHelper.extractKeyFromFileContent(fileText)
                    if (extractedKey != null) {
                        enteredKey = extractedKey
                        Toast.makeText(context, "Recovery key loaded from file!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Could not find a valid recovery key in selected file", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Icon Header
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (isVerified) "🛡️" else "🔑", fontSize = 28.sp)
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = if (isVerified) "Reset Master PIN" else "Recover Vault Access",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isVerified) {
                        "Recovery key verified! Create a new Master PIN to secure your vault and restore access."
                    } else {
                        "Enter your 32-character recovery key or choose your saved recovery backup file to reset your PIN."
                    },
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Error Message if present
                errorMessage?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1C1C)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = error,
                            color = Color(0xFFFF453A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                if (!isVerified) {
                    // STEP 1: Enter or Import Recovery Key
                    OutlinedTextField(
                        value = enteredKey,
                        onValueChange = { enteredKey = it },
                        label = { Text("Recovery Key", color = Color(0xFF8E8E93)) },
                        placeholder = { Text("VSREC-XXXX-XXXX-...", color = Color(0xFF48484A)) },
                        trailingIcon = {
                            IconButton(onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    val extracted = VaultRecoveryKeyHelper.extractKeyFromFileContent(clip) ?: clip
                                    enteredKey = extracted.trim()
                                    Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("📋", fontSize = 18.sp)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0A84FF),
                            unfocusedBorderColor = Color(0xFF38383A),
                            focusedContainerColor = Color.Black,
                            unfocusedContainerColor = Color.Black,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Import from File button
                    OutlinedButton(
                        onClick = {
                            openDocumentLauncher.launch(arrayOf("text/plain", "*/*"))
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("📁 Select Saved Key File (.txt)", color = Color.White, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Verify Key Button
                    Button(
                        onClick = {
                            isVerifying = true
                            onVerifyKey(enteredKey) { success, msg ->
                                isVerifying = false
                                if (!success && msg != null) {
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enteredKey.isNotBlank() && !isVerifying,
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Verify Recovery Key", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // STEP 2: Enter New PIN
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it },
                        label = { Text("New Master PIN", color = Color(0xFF8E8E93)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0A84FF),
                            unfocusedBorderColor = Color(0xFF38383A),
                            focusedContainerColor = Color.Black,
                            unfocusedContainerColor = Color.Black,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = confirmNewPin,
                        onValueChange = { confirmNewPin = it },
                        label = { Text("Confirm New PIN", color = Color(0xFF8E8E93)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0A84FF),
                            unfocusedBorderColor = Color(0xFF38383A),
                            focusedContainerColor = Color.Black,
                            unfocusedContainerColor = Color.Black,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { enableBiometric = !enableBiometric },
                    ) {
                        Checkbox(
                            checked = enableBiometric,
                            onCheckedChange = { enableBiometric = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0A84FF)),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Enable Biometric Unlock", color = Color.White, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            if (newPin.length < 4) {
                                Toast.makeText(context, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (newPin != confirmNewPin) {
                                Toast.makeText(context, "PINs do not match", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isResetting = true
                            onResetPin(newPin, enableBiometric) { success, msg ->
                                isResetting = false
                                if (success) {
                                    Toast.makeText(context, msg ?: "PIN reset successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, msg ?: "Failed to reset PIN", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = newPin.isNotBlank() && confirmNewPin.isNotBlank() && !isResetting,
                    ) {
                        if (isResetting) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Reset PIN & Unlock Vault", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF8E8E93))
                }
            }
        }
    }
}
