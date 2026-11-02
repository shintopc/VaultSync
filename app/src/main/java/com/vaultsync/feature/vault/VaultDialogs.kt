package com.vaultsync.feature.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.model.VaultFolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Folder") },
        text = {
            Column {
                Text(
                    text = "Enter a name for the new folder:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (folderName.isNotBlank()) {
                        onConfirm(folderName)
                    }
                },
                enabled = folderName.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun RenameItemDialog(
    initialName: String,
    itemType: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newName by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename $itemType") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank() && newName != initialName) {
                        onConfirm(newName)
                    }
                },
                enabled = newName.isNotBlank() && newName != initialName,
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

fun getDescendantFolderIds(folderId: String, allFolders: List<VaultFolder>): Set<String> {
    val descendants = mutableSetOf(folderId)
    var changed = true
    while (changed) {
        val next = allFolders.filter { it.parentId in descendants && it.id !in descendants }.map { it.id }
        if (next.isEmpty()) {
            changed = false
        } else {
            descendants.addAll(next)
        }
    }
    return descendants
}

@Composable
fun MoveItemDialog(
    itemTitle: String,
    allFolders: List<VaultFolder>,
    currentFolderId: String?,
    excludeFolderId: String? = null,
    isCopy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    // Exclude the folder itself and its descendants if moving a folder
    val excludedIds = remember(excludeFolderId, allFolders) {
        if (excludeFolderId != null) getDescendantFolderIds(excludeFolderId, allFolders)
        else emptySet()
    }
    val availableFolders = allFolders.filter { it.id !in excludedIds }
    var selectedTargetId by remember { mutableStateOf<String?>(currentFolderId) }
    val actionWord = if (isCopy) "Copy" else "Move"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$actionWord $itemTitle") },
        text = {
            Column {
                Text(
                    text = "Select destination folder:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTargetId = null }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(
                                selected = selectedTargetId == null,
                                onClick = { selectedTargetId = null },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Vault Root (No folder)", fontWeight = FontWeight.Medium)
                        }
                    }

                    items(availableFolders) { folder ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTargetId = folder.id }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(
                                selected = selectedTargetId == folder.id,
                                onClick = { selectedTargetId = folder.id },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(folder.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedTargetId) },
                enabled = isCopy || selectedTargetId != currentFolderId,
            ) {
                Text(if (isCopy) "Copy Here" else "Move Here")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun CopyEntryDialog(
    entry: VaultEntry,
    allFolders: List<VaultFolder>,
    currentFolderId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var selectedTargetId by remember { mutableStateOf<String?>(currentFolderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy File") },
        text = {
            Column {
                Text(
                    text = "Select destination for copy of '${entry.originalFileName}':",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTargetId = null }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(
                                selected = selectedTargetId == null,
                                onClick = { selectedTargetId = null },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Vault Root (No folder)", fontWeight = FontWeight.Medium)
                        }
                    }

                    items(allFolders) { folder ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTargetId = folder.id }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(
                                selected = selectedTargetId == folder.id,
                                onClick = { selectedTargetId = folder.id },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(folder.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedTargetId) },
            ) {
                Text("Copy Here")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun DeleteConfirmationDialog(
    title: String,
    message: String,
    confirmButtonText: String = "Delete Permanently",
    isDestructive: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun MoveOutOfVaultConfirmationDialog(
    count: Int = 1,
    singleFileName: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val message = if (singleFileName != null) {
        "Move '$singleFileName' out of the vault back to your device's normal gallery?"
    } else {
        "Move $count selected items out of the secret vault back to normal gallery albums?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move Out to Gallery") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Move to Gallery")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun FileDetailsDialog(
    entry: VaultEntry,
    onDismiss: () -> Unit,
    onExportClick: () -> Unit,
) {
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(label = "Filename", value = entry.originalFileName)
                DetailRow(label = "Category", value = entry.category.name)
                DetailRow(label = "MIME Type", value = entry.mimeType ?: "Unknown")
                DetailRow(label = "Plaintext Size", value = formatFileSize(entry.originalSizeBytes))
                DetailRow(label = "Encrypted Size", value = formatFileSize(entry.encryptedSizeBytes))
                DetailRow(label = "Encrypted Blob ID", value = "${entry.id}.vsf")
                DetailRow(label = "Added", value = dateFormatter.format(Date(entry.createdAtMillis)))
                DetailRow(label = "Modified", value = dateFormatter.format(Date(entry.dateModifiedMillis)))
            }
        },
        confirmButton = {
            Button(onClick = onExportClick) {
                Text("Export Decrypted File")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun ImportProgressOverlay(progressText: String?) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = progressText ?: "Encrypting and importing files...",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun StorageInfoDialog(
    totalFiles: Int,
    totalVaultSizeBytes: Long,
    availableStorageBytes: Long,
    onDismiss: () -> Unit,
    onOptimizeStorage: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onRestoreBackup: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vault Storage & Capacity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow(label = "Encrypted Files in Vault", value = "$totalFiles items")
                DetailRow(label = "Total Vault Encrypted Size", value = formatFileSize(totalVaultSizeBytes))
                DetailRow(label = "Available Device Space", value = formatFileSize(availableStorageBytes))
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Storage & Backup Tools",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOptimizeStorage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clean Duplicates & Optimize")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onExportBackup()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export .vsbackup", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onRestoreBackup()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restore Backup", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
fun DeleteOrTrashEntryDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onMoveToTrash: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "What would you like to do with '$fileName'?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• Move to Trash: Kept in Vault Recycle Bin for 30 days before shredding.\n• Shred Permanently: Immediately destroyed from encrypted storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onMoveToTrash,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Move to Trash")
                }
                Button(
                    onClick = onPermanentDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Shred")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun StorageOptimizerDialog(
    report: com.vaultsync.core.storage.StorageOptimizationReport?,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onCleanDuplicates: (moveToTrash: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CleaningServices, contentDescription = null, tint = Color(0xFF38BDF8))
                Text("Storage Optimizer")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isScanning) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Analyzing file hashes and duplicate chunks...", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (report == null || report.totalDuplicateCount == 0) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Zero Duplicates Found!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "All files in your vault are unique and space is 100% optimized.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Found ${report.totalDuplicateCount} duplicate file(s)",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "${formatFileSize(report.totalWastedBytes)} wasted storage can be reclaimed",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Text("Duplicate Sets:", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(report.duplicateGroups) { group ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                tonalElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = group.entries.first().originalFileName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "${group.entries.size} copies • ${formatFileSize(group.fileSize)} each (${formatFileSize(group.wastedBytes)} extra)",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (report != null && report.totalDuplicateCount > 0 && !isScanning) {
                Button(
                    onClick = { onCleanDuplicates(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Clean Duplicates")
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            if (report != null && report.totalDuplicateCount > 0 && !isScanning) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
fun DecoyPinDialog(
    isDecoyPinSet: Boolean,
    onDismiss: () -> Unit,
    onSetDecoyPin: (String) -> Unit,
    onClearDecoyPin: () -> Unit,
) {
    var enteredPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color(0xFFFF9F0A))
                Text("Duress / Decoy PIN")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "A Decoy PIN protects you under coercion or peer pressure. Entering this PIN on the lock screen opens an innocent dummy vault with zero secret files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (isDecoyPinSet) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("Decoy PIN is Active", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), fontSize = 13.sp)
                                Text("Safe dummy vault will unlock with decoy PIN", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            }
                            Button(
                                onClick = onClearDecoyPin,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("Disable", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Change Decoy PIN:", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = { enteredPin = it; errorMessage = null },
                    label = { Text("New Decoy PIN / Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it; errorMessage = null },
                    label = { Text("Confirm Decoy PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (enteredPin.length < 4) {
                        errorMessage = "PIN must be at least 4 digits"
                    } else if (enteredPin != confirmPin) {
                        errorMessage = "PINs do not match"
                    } else {
                        onSetDecoyPin(enteredPin)
                    }
                },
            ) {
                Text(if (isDecoyPinSet) "Update Decoy PIN" else "Set Decoy PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun IntruderLogsDialog(
    logs: List<com.vaultsync.core.security.IntruderLogEntry>,
    isIntruderCaptureEnabled: Boolean,
    onToggleCapture: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = Color(0xFFFF453A))
                Text("Break-In Logs")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Capture photo on 3 failed attempts", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Silently snaps front camera photo", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isIntruderCaptureEnabled,
                        onCheckedChange = onToggleCapture,
                    )
                }

                HorizontalDivider()

                if (logs.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No break-in attempts recorded", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text("Recorded Intruder Events (${logs.size}):", style = MaterialTheme.typography.labelSmall)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(logs) { log ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                tonalElevation = 3.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (log.photoFile != null) {
                                        AsyncImage(
                                            model = log.photoFile,
                                            contentDescription = "Intruder photo",
                                            modifier = Modifier.size(52.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        )
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            modifier = Modifier.size(52.dp),
                                        ) {
                                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(dateFormatter.format(Date(log.timestampMillis)), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("${log.failureCount} consecutive failed attempts", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (logs.isNotEmpty()) {
                Button(
                    onClick = onClearLogs,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Clear Logs")
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (logs.isNotEmpty()) "Cancel" else "Done")
            }
        },
    )
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(Locale.US, value, units[minOf(digitGroups, units.lastIndex)])
}
