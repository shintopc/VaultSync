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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
                Text(
                    text = "All files are stored with AES-256-GCM encryption in private storage. Decrypted files are held temporarily in secure memory/cache during previews and cleaned up upon lock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
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
