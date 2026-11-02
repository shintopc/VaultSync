package com.vaultsync.feature.stealth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import java.io.File

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
            Toast.makeText(context, "When dialer opens, delete and re-type the final * to trigger", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to launch dialer", Toast.LENGTH_SHORT).show()
        }
    }

    fun testDeepLink(link: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to launch link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveOfflineHtmlGateway() {
        try {
            val htmlContent = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>VaultSync Recovery Gateway</title>
    <style>
        body { background:#000; color:#fff; font-family:sans-serif; text-align:center; padding:40px 20px; }
        .card { background:#1C1C1E; border:1px solid #2C2C2E; border-radius:20px; padding:32px 24px; max-width:400px; margin:auto; }
        .btn { display:block; width:100%; background:#0A84FF; color:#fff; font-weight:bold; font-size:16px; padding:16px; border-radius:12px; border:none; cursor:pointer; margin-top:20px; text-decoration:none; }
    </style>
    <script>
        function launch() {
            window.location.href = "intent://open#Intent;scheme=vaultsync;package=com.vaultsync.app;end";
            setTimeout(function() { window.location.href = "vaultsync://open"; }, 500);
        }
        window.onload = launch;
    </script>
</head>
<body>
    <div class="card">
        <div style="font-size:48px;">🔓</div>
        <h2 style="margin-top:10px;">VaultSync Recovery</h2>
        <p style="color:#8E8E93;margin-top:8px;">Launching your private vault...</p>
        <button class="btn" onclick="launch()">Open VaultSync App</button>
        <a class="btn" style="background:#2C2C2E;margin-top:10px;" href="vaultsync://open">Direct Scheme Link</a>
    </div>
</body>
</html>
            """.trimIndent()

            val docsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "VaultSync")
            if (!docsDir.exists()) docsDir.mkdirs()
            File(docsDir, "VaultSync_Recovery.html").writeText(htmlContent)

            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dlDir != null && dlDir.exists()) {
                File(dlDir, "VaultSync_Recovery.html").writeText(htmlContent)
            }
            Toast.makeText(context, "Saved VaultSync_Recovery.html to Downloads & Documents!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to save file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun testSecretCodeBroadcast(code: String) {
        try {
            val intentModern = Intent("android.telephony.action.SECRET_CODE").apply {
                data = Uri.parse("android_secret_code://$code")
            }
            context.sendBroadcast(intentModern)
            val intentLegacy = Intent("android.provider.Telephony.SECRET_CODE").apply {
                data = Uri.parse("android_secret_code://$code")
            }
            context.sendBroadcast(intentLegacy)
            Toast.makeText(context, "Recovery code broadcast sent! Check notification banner.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Broadcast failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Icon(
                        imageVector = if (isConfirmationForHiding) Icons.Rounded.Warning else Icons.Rounded.Key,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                tint = Color(0xFF64D2FF),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Route 1: Deep Link & Browser Gateway",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap or send this recovery link from any note, chat (WhatsApp/Telegram), or browser to launch VaultSync immediately:",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // App Scheme box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1C1C1E), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF3A3A3C), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Column {
                                Text(
                                    text = "vaultsync://open",
                                    color = Color(0xFF64D2FF),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Online: https://shintopc.github.io/VaultSync/open.html",
                                    color = Color(0xFF98989D),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "💡 Note: In Chrome's URL bar, typing 'vaultsync://' triggers a Google Search. Use 'Test Launch' below to open via Android intent, or save the offline gateway HTML file to Downloads for one-tap browser launching.",
                            color = Color(0xFFFFD60A),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(
                                onClick = { saveOfflineHtmlGateway() },
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("Save HTML Gateway", fontSize = 11.sp, color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { testDeepLink("vaultsync://open") },
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("Test Launch", fontSize = 11.sp, color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { copyToClipboard("VaultSync Recovery Link", "vaultsync://open") },
                                modifier = Modifier.height(34.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
                            ) {
                                Text("Copy", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }

                // Route 2: Secret Dialer Code
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Phone,
                                contentDescription = null,
                                tint = Color(0xFF30D158),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Route 2: Secret Phone Dialer Code",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Type this code directly into your phone's dialpad to trigger secret launch:",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Code box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1C1C1E), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF3A3A3C), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "*#*#82858#*#*",
                                color = Color(0xFF30D158),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "⚠️ Do NOT press the green Call button! Secret codes trigger automatically on typing the last '*'. (If using 'Open Dialer', delete and re-type the final '*' on the dialpad to trigger). On modern Android, a heads-up notification banner will pop down — tap it to open.",
                            color = Color(0xFFFF9F0A),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(
                                onClick = { testSecretCodeBroadcast("82858") },
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("Test Code", fontSize = 11.sp, color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { openDialer("*#*#82858#*#*") },
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("Open Dialer", fontSize = 11.sp, color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { copyToClipboard("VaultSync Dialer Code", "*#*#82858#*#*") },
                                modifier = Modifier.height(34.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
                            ) {
                                Text("Copy", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }

                // Route 3: Android System Settings
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                tint = Color(0xFFFF9F0A),
                                modifier = Modifier.size(18.dp),
                            )
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
                            text = "Settings → Apps → VaultSync → tap \"Open\". You can also unhide or change the icon from Settings at any time.",
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
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(18.dp),
                        )
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
