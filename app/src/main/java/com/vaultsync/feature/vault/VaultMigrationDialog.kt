package com.vaultsync.feature.vault

import android.os.Environment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vaultsync.core.migration.MigrationProgress
import com.vaultsync.core.migration.MigrationStatus
import com.vaultsync.core.storage.VaultStoragePreferences
import java.io.File

@Composable
fun VaultMigrationDialog(
    isOpen: Boolean,
    isMigrationInProgress: Boolean,
    progress: MigrationProgress?,
    onDismiss: () -> Unit,
    onStartMigration: (File) -> Unit,
) {
    if (!isOpen) return

    val defaultDest = remember {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        File(docs, VaultStoragePreferences.DEFAULT_FOLDER_NAME)
    }

    var selectedDir by remember { mutableStateOf(defaultDest) }

    Dialog(
        onDismissRequest = { if (!isMigrationInProgress) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isMigrationInProgress, dismissOnClickOutside = !isMigrationInProgress),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📦", fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Migrate to Shared Storage",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = "Survive App Uninstalls & Recover Anywhere",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!isMigrationInProgress) {
                    Text(
                        text = "Encrypted media is currently in app-private storage. If the app is uninstalled, Android permanently deletes that folder.",
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Destination:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Text(
                                text = selectedDir.absolutePath,
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "✓ Zero re-encryption: files remain byte-for-byte identical.\n" +
                            "✓ Crash-safe: resumable file-by-file journal.\n" +
                            "✓ Generates vault_manifest.vsm for instant reinstall recovery.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = Color(0xFF94A3B8))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onStartMigration(selectedDir) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        ) {
                            Text("Begin Migration", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // In-progress view
                    Text(
                        text = progress?.message ?: "Migrating encrypted files...",
                        color = Color(0xFF38BDF8),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val fraction = if (progress != null && progress.totalFiles > 0) {
                        progress.currentFileIndex.toFloat() / progress.totalFiles.toFloat()
                    } else 0f

                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color(0xFF38BDF8),
                        trackColor = Color(0xFF334155),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (progress != null) "File ${progress.currentFileIndex} of ${progress.totalFiles}" else "Preparing...",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "${(fraction * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Do not close the application. Each file is verified with SHA-256 before the original is removed.",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
