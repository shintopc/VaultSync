package com.vaultsync.feature.stealth

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultsync.core.stealth.AppIconManager
import com.vaultsync.core.stealth.IconMode
import com.vaultsync.core.stealth.StealthPreferences
import com.vaultsync.core.stealth.TwoFingerGestureType
import com.vaultsync.core.stealth.detectTwoFingerVaultGesture

@Composable
fun StealthSettingsModal(
    onDismiss: () -> Unit,
    onOpenRecoveryGuide: () -> Unit,
) {
    val context = LocalContext.current
    val stealthPrefs = remember { StealthPreferences(context) }

    var selectedMode by remember { mutableStateOf(stealthPrefs.iconMode) }
    var isAppLockEnabled by remember { mutableStateOf(stealthPrefs.isAppLockEnabled) }
    var isGestureEnabled by remember { mutableStateOf(stealthPrefs.isTwoFingerGestureEnabled) }
    var selectedGestureType by remember { mutableStateOf(stealthPrefs.gestureType) }
    var isCalcPinTriggerEnabled by remember { mutableStateOf(stealthPrefs.isCalculatorPinTriggerEnabled) }
    var isLongPressVaultEnabled by remember { mutableStateOf(stealthPrefs.isLongPressSettingsForVaultEnabled) }

    var showConfirmHiddenDialog by remember { mutableStateOf(false) }
    var gestureTestFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gestureTestFeedback) {
        if (gestureTestFeedback != null) {
            kotlinx.coroutines.delay(3500)
            gestureTestFeedback = null
        }
    }

    val scrollState = rememberScrollState()

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
                Text(
                    text = "App Camouflage & Access Security",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Protect access to VaultSync with disguised icons, hidden launcher mode, secret PIN trigger, and two-finger gesture entry.",
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp,
                )

                // Section 1: Launcher Appearance Mode
                Text(
                    text = "LAUNCHER APPEARANCE MODE",
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeOptionTile(
                        icon = "🖼️",
                        title = "Normal Mode",
                        subtitle = "VaultSync gallery icon on home screen",
                        isSelected = selectedMode == IconMode.NORMAL,
                        onClick = {
                            selectedMode = IconMode.NORMAL
                        },
                    )
                    ModeOptionTile(
                        icon = "🧮",
                        title = "Disguised: Calculator",
                        subtitle = "Opens real calculator. Secret gesture unlocks gallery",
                        isSelected = selectedMode == IconMode.CALCULATOR,
                        onClick = {
                            selectedMode = IconMode.CALCULATOR
                            isGestureEnabled = true
                        },
                    )
                    ModeOptionTile(
                        icon = "📝",
                        title = "Disguised: Notes",
                        subtitle = "Opens notes memo cover. Secret gesture unlocks gallery",
                        isSelected = selectedMode == IconMode.NOTES,
                        onClick = {
                            selectedMode = IconMode.NOTES
                            isGestureEnabled = true
                        },
                    )
                    ModeOptionTile(
                        icon = "👻",
                        title = "Hidden Mode",
                        subtitle = "Launcher icon disabled. Access via secret code (*#*#82858#*#*) or link",
                        isSelected = selectedMode == IconMode.HIDDEN,
                        onClick = {
                            showConfirmHiddenDialog = true
                        },
                    )
                }

                // Normal Mode App Lock Option
                if (selectedMode == IconMode.NORMAL) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Require Secret Access on Launch",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Prompts for master PIN or biometric before opening gallery",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 12.sp,
                                )
                            }
                            Switch(
                                checked = isAppLockEnabled,
                                onCheckedChange = { isAppLockEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF32D74B),
                                ),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Section 2: Secret Two-Finger Gesture
                Text(
                    text = "SECRET TWO-FINGER GESTURE ACCESS",
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Two-Finger Gesture",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Opens secret entry screen to unlock the main app from disguise",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 12.sp,
                                )
                            }
                            Switch(
                                checked = isGestureEnabled,
                                onCheckedChange = { isGestureEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF32D74B),
                                ),
                            )
                        }

                        if (isGestureEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Select Gesture:",
                                color = Color(0xFF8E8E93),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            TwoFingerGestureType.entries.forEach { type ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedGestureType = type }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedGestureType == type,
                                        onClick = { selectedGestureType = type },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF32D74B)),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = type.displayName,
                                            color = Color.White,
                                            fontSize = 13.sp,
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

                            Spacer(modifier = Modifier.height(8.dp))

                            // Interactive Gesture Test Box
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp)
                                    .background(
                                        if (gestureTestFeedback != null) Color(0xFF1B382B) else Color(0xFF1C1C1E),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .border(
                                        1.dp,
                                        if (gestureTestFeedback != null) Color(0xFF32D74B) else Color(0xFF3A3A3C),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .detectTwoFingerVaultGesture(
                                        enabled = true,
                                        gestureType = selectedGestureType,
                                        onGestureTriggered = {
                                            gestureTestFeedback = "Gesture Triggered! Main App Entry Activated ✨"
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = gestureTestFeedback
                                        ?: "👆 Test Area: Try your ${selectedGestureType.displayName.lowercase()} here",
                                    color = if (gestureTestFeedback != null) Color(0xFF32D74B) else Color(0xFF8E8E93),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // Section 3: Disguise Security Note
                if (selectedMode == IconMode.CALCULATOR || selectedMode == IconMode.NOTES) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🛡️", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Pure Disguise Security",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "There are no direct buttons (like Edit) or calculator PIN triggers in disguise mode. Access to your gallery is exclusively granted via your secret two-finger gesture.",
                                color = Color(0xFF8E8E93),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                // Section 3b: Gallery Secret Vault Access
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Long-Press Settings for Secret Vault",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Hold ⚙️ in gallery to open Secret Vault • Hides obvious lock buttons",
                                color = Color(0xFF8E8E93),
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = isLongPressVaultEnabled,
                            onCheckedChange = { isLongPressVaultEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF32D74B),
                            ),
                        )
                    }
                }

                // Section 4: Lockout Recovery Guide Link
                Button(
                    onClick = onOpenRecoveryGuide,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("📖 View Lockout Recovery Guide & Secret Codes", color = Color(0xFF64D2FF), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    stealthPrefs.isAppLockEnabled = isAppLockEnabled
                    stealthPrefs.isTwoFingerGestureEnabled = isGestureEnabled
                    stealthPrefs.gestureType = selectedGestureType
                    stealthPrefs.isCalculatorPinTriggerEnabled = isCalcPinTriggerEnabled
                    stealthPrefs.isLongPressSettingsForVaultEnabled = isLongPressVaultEnabled

                    val success = AppIconManager.applyIconMode(context, selectedMode)
                    if (success) {
                        Toast.makeText(
                            context,
                            "Applied: ${selectedMode.displayName}. Android launcher updates icon shortly.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(context, "Failed to update launcher icon setting", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF32D74B)),
            ) {
                Text("Save Changes", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        },
        containerColor = Color(0xFF1C1C1E),
    )

    if (showConfirmHiddenDialog) {
        RecoveryGuideDialog(
            isConfirmationForHiding = true,
            onConfirmHide = {
                selectedMode = IconMode.HIDDEN
                stealthPrefs.hasAcknowledgedHiddenRisk = true
                showConfirmHiddenDialog = false
            },
            onDismiss = {
                showConfirmHiddenDialog = false
            },
        )
    }
}

@Composable
private fun ModeOptionTile(
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
                1.dp,
                if (isSelected) Color(0xFF32D74B) else Color.Transparent,
                RoundedCornerShape(12.dp),
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(icon, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                )
            }
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF32D74B)),
            )
        }
    }
}
