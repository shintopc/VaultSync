package com.vaultsync.feature.trash

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.vaultsync.core.trash.TrashItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: TrashViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showEmptyConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (uiState.hasSelection) "${uiState.selectedIds.size} Selected" else "Recycle Bin",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 20.sp,
                        )
                        if (!uiState.hasSelection && uiState.totalCount > 0) {
                            Text(
                                text = "${uiState.totalCount} items • ${uiState.formattedTotalSize}",
                                color = Color(0xFF8E8E93),
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.hasSelection) {
                                viewModel.clearSelection()
                            } else {
                                onNavigateBack()
                            }
                        },
                    ) {
                        Text(
                            text = if (uiState.hasSelection) "✕" else "←",
                            fontSize = 22.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    if (uiState.hasSelection) {
                        TextButton(
                            onClick = {
                                if (uiState.isAllSelected) viewModel.clearSelection() else viewModel.selectAll()
                            },
                        ) {
                            Text(
                                text = if (uiState.isAllSelected) "Deselect" else "Select All",
                                color = Color(0xFF0A84FF),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else if (uiState.items.isNotEmpty()) {
                        TextButton(onClick = { showEmptyConfirmDialog = true }) {
                            Text(
                                text = "Empty Trash",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = uiState.hasSelection,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    color = Color(0xFF1C1C1E),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Restore Button
                        Button(
                            onClick = {
                                viewModel.restoreSelected { count ->
                                    Toast.makeText(context, "Restored $count file(s)", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("🔄 Restore (${uiState.selectedIds.size})", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }

                        // Delete Permanently Button
                        Button(
                            onClick = { showDeleteConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("❌ Delete Permanently", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else if (uiState.items.isEmpty()) {
                // Empty State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1C1C1E)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("♻️", fontSize = 38.sp)
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Recycle Bin is Empty",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Deleted gallery photos and videos go to Recycle Bin and are automatically removed after 15 days.",
                        color = Color(0xFF8E8E93),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Header Banner explaining 15-day auto-delete
                    item(span = { GridItemSpan(3) }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("⏳", fontSize = 22.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "15-Day Automatic Deletion",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Items in Recycle Bin are permanently erased 15 days after being deleted.",
                                        color = Color(0xFF8E8E93),
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }

                    items(uiState.items, key = { it.id }) { item ->
                        val isSelected = item.id in uiState.selectedIds

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1C1C1E))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.dp, Color(0xFF0A84FF), RoundedCornerShape(6.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (uiState.hasSelection) {
                                            viewModel.toggleSelection(item.id)
                                        } else {
                                            viewModel.setPreviewItem(item)
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.toggleSelection(item.id)
                                    },
                                ),
                        ) {
                            AsyncImage(
                                model = item.file,
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )

                            // Days remaining badge (bottom)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color.Black.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp),
                            ) {
                                Text(
                                    text = item.remainingStatusText,
                                    color = if (item.daysRemaining <= 2) Color(0xFFFF453A) else Color(0xFFFF9F0A),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }

                            // Video icon badge
                            if (item.isVideo) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .size(20.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("▶", color = Color.White, fontSize = 9.sp)
                                    }
                                }
                            }

                            // Selection indicator badge
                            if (uiState.hasSelection) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color(0xFF0A84FF) else Color.Black.copy(alpha = 0.6f))
                                        .border(1.5.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Operation overlay
            if (uiState.isOperationInProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = uiState.operationMessage ?: "Processing...",
                                color = Color.White,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    // Single Item Preview Dialog
    uiState.previewItem?.let { item ->
        Dialog(onDismissRequest = { viewModel.setPreviewItem(null) }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = item.file,
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = item.displayName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${item.formattedSize} • ${item.remainingStatusText}",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Restore Button
                        Button(
                            onClick = {
                                viewModel.restoreSingle(item) { success ->
                                    Toast.makeText(
                                        context,
                                        if (success) "File restored to device" else "Could not restore file",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("🔄 Restore", color = Color.White)
                        }

                        // Delete Permanently Button
                        Button(
                            onClick = {
                                viewModel.deletePermanentlySingle(item) { success ->
                                    Toast.makeText(
                                        context,
                                        if (success) "Permanently deleted" else "Could not delete",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("❌ Delete Permanently", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Empty Trash Confirmation Dialog
    if (showEmptyConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirmDialog = false },
            title = { Text("Empty Trash", color = Color.White) },
            text = {
                Text(
                    "Permanently delete all ${uiState.totalCount} items in Trash? This cannot be undone.",
                    color = Color(0xFF8E8E93),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.emptyTrash { count ->
                            Toast.makeText(context, "Trash emptied ($count items deleted)", Toast.LENGTH_SHORT).show()
                        }
                        showEmptyConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Empty Trash")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirmDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // Batch Delete Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Permanently", color = Color.White) },
            text = {
                Text(
                    "Permanently delete ${uiState.selectedIds.size} selected item(s) from Trash? This cannot be undone.",
                    color = Color(0xFF8E8E93),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePermanentlySelected { count ->
                            Toast.makeText(context, "Permanently deleted $count item(s)", Toast.LENGTH_SHORT).show()
                        }
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }
}
