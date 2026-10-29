package com.vaultsync.feature.stealth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RecoveryGuideDialog(
    isConfirmationForHiding: Boolean = false,
    onConfirmHide: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    fun openDialer(code: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(code)}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to launch dialer", Toast.LENGTH_SHORT).show()
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
                        .background(if (isConfirmationForHiding) Color(0xFFFF453A) else Color(0xFF0A84FF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (isConfirmationForHiding) "⚠️" else "🔑", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isConfirmationForHiding) "Confirm Hidden Mode" else "Stealth & Recovery Access",
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (isConfirmationForHiding) {
                    Text(
                        text = "The VaultSync launcher icon will be completely hidden from your home screen and app drawer. Before proceeding, review the recovery methods below so you are never locked out.",
                        color = Color(0xFFFF9F0A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Text(
                        text = "VaultSync provides multiple verified recovery routes so you can reliably access your vault even if the app icon is hidden or disguised.",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                }

                // Route 1: Deep Link
                RecoveryCard(
                    icon = "🔗",
                    title = "Route 1: Browser Deep Link",
                    subtitle = "Type or tap this link from any web browser, note, or messenger to launch VaultSync immediately:",
                    code = "vaultsync://open",
                    onCopy = { copyToClipboard("VaultSync Recovery Link", "vaultsync://open") },
                    actionLabel = "Copy Link",
                )

                // Route 2: Secret Dialer Code
                RecoveryCard(
                    icon = "📞",
                    title = "Route 2: Secret Phone Dialer Code",
                    subtitle = "Open your phone dialer app and dial this code to trigger secret launch:",
                    code = "*#*#82858#*#*",
                    onCopy = { copyToClipboard("VaultSync Dialer Code", "*#*#82858#*#*") },
                    actionLabel = "Copy Code",
                    secondaryActionLabel = "Open Dialer",
                    onSecondaryAction = { openDialer("*#*#82858#*#*") },
                )

                // Route 3: Android System Settings
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚙️", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Route 3: Android App Settings",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Settings → Apps → VaultSync → tap \"Open\". You can also unhide the icon from Settings at any time.",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                        )
                    }
                }

                // Security Invariant Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2A22)),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("🛡️", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Security Always Enforced",
                                color = Color(0xFF30D158),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Hiding or disguising the icon never replaces PIN, biometric, or AES-256-GCM encryption protection. The vault is never accessible without your credentials.",
                                color = Color(0xFF9EF2E3),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isConfirmationForHiding) {
                Button(
                    onClick = onConfirmHide,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                ) {
                    Text("I Understand • Hide Icon")
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                ) {
                    Text("Got It")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isConfirmationForHiding) "Cancel" else "Close", color = Color.White)
            }
        },
        containerColor = Color(0xFF1C1C1E),
    )
}

@Composable
private fun RecoveryCard(
    icon: String,
    title: String,
    subtitle: String,
    code: String,
    onCopy: () -> Unit,
    actionLabel: String,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = Color(0xFF8E8E93),
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Code display box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF3A3A3C), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = code,
                    color = Color(0xFF64D2FF),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text(secondaryActionLabel, fontSize = 11.sp, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Button(
                    onClick = onCopy,
                    modifier = Modifier.height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
                ) {
                    Text(actionLabel, fontSize = 11.sp, color = Color.White)
                }
            }
        }
    }
}
