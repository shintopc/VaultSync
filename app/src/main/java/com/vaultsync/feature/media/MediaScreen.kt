package com.vaultsync.feature.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.vaultsync.core.media.EnhancedVideoPlayer
import com.vaultsync.core.stealth.StealthPreferences
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.vaultsync.core.media.ExifDetails
import com.vaultsync.core.media.ExifMetadataInspector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.vaultsync.core.floating.FloatingWindowIcon
import com.vaultsync.core.floating.LocalFloatingWindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.vaultsync.core.media.DeviceMediaItem
import com.vaultsync.core.media.MediaCategory
import com.vaultsync.core.media.MediaFolder
import com.vaultsync.core.media.MediaPermissionStatus
import com.vaultsync.core.media.MediaSort
import com.vaultsync.core.media.mediaPermissionStatusFromGrantResults
import com.vaultsync.core.media.requiredMediaPermissions
import com.vaultsync.core.model.VaultFolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import java.text.DateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

@Composable
fun MediaScreen(
    viewModel: MediaViewModel = viewModel(),
    onNavigateToVault: () -> Unit = {},
    onNavigateToPcAccess: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    onLockAndDisguise: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val vaultFolders by viewModel.vaultFolders.collectAsState(initial = emptyList())
    val isVaultUnlocked by viewModel.isVaultUnlocked.collectAsState()
    val context = LocalContext.current
    val floatingWindowManager = LocalFloatingWindowManager.current
    val requiredPermissions = remember { requiredMediaPermissions() }
    val stealthPrefs = remember { StealthPreferences(context) }
    val haptic = LocalHapticFeedback.current

    var itemToDelete by remember { mutableStateOf<DeviceMediaItem?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Fullscreen interactive viewer state
    var viewerInitialIndex by remember { mutableStateOf<Int?>(null) }
    var viewerItems by remember { mutableStateOf<List<DeviceMediaItem>>(emptyList()) }

    // Scoped Storage Delete IntentSender Launcher
    val deleteIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pending = state.pendingDeleteRequest
        if (pending != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.onSystemDeleteApproved(pending) { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            } else {
                viewModel.onSystemDeleteCancelled(pending)
                Toast.makeText(context, "Cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(state.pendingDeleteRequest) {
        state.pendingDeleteRequest?.let { pending ->
            try {
                val request = IntentSenderRequest.Builder(pending.intentSender).build()
                deleteIntentSenderLauncher.launch(request)
            } catch (e: Exception) {
                viewModel.onSystemDeleteCancelled(pending)
                Toast.makeText(context, "Could not request deletion permission: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.setPermissionStatus(
            mediaPermissionStatusFromGrantResults(
                requiredPermissions = requiredPermissions,
                grantResults = grants,
                canAskAgain = true,
            ),
        )
    }

    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.loadSafFolder(uri)
        }
    }

    LaunchedEffect(requiredPermissions) {
        val granted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        viewModel.setPermissionStatus(
            if (granted) {
                MediaPermissionStatus.Granted
            } else {
                MediaPermissionStatus.Denied(canAskAgain = true)
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val permission = state.permissionStatus) {
            MediaPermissionStatus.Unknown -> LoadingState()
            MediaPermissionStatus.Granted -> MediaGalleryContent(
                state = state,
                onOpenSafFolder = { safLauncher.launch(null) },
                onRefresh = viewModel::refreshMediaStore,
                onCategorySelected = viewModel::setCategory,
                onSearchChanged = viewModel::setSearchText,
                onSortSelected = viewModel::setSort,
                onItemSelected = viewModel::toggleSelection,
                onOpenViewer = { items, index ->
                    viewerItems = items
                    viewerInitialIndex = index
                },
                onClearSelection = viewModel::clearSelection,
                onSelectAll = viewModel::selectAll,
                onViewModeChanged = viewModel::setViewMode,
                onOpenFolder = viewModel::openFolder,
                onExitFolder = viewModel::exitFolder,
                onShareSelected = {
                    val selected = state.allItems.filter { it.id in state.selectedItemIds }
                    shareMediaItems(context, selected)
                },
                onFloatSelected = {
                    val selected = state.allItems.filter { it.id in state.selectedItemIds }
                    if (selected.isNotEmpty()) {
                        floatingWindowManager.openGalleryMediaMultiple(selected)
                        viewModel.clearSelection()
                        Toast.makeText(context, "Opened ${selected.size} in floating window", Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteSelected = { showDeleteConfirmDialog = true },
                onMoveToVaultSelected = {
                    if (!isVaultUnlocked) {
                        Toast.makeText(context, "Please unlock your vault in the Vault tab first", Toast.LENGTH_LONG).show()
                    } else {
                        viewModel.setVaultPickerOpen(true)
                    }
                },
                onNavigateToVault = onNavigateToVault,
                onNavigateToPcAccess = onNavigateToPcAccess,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToTrash = onNavigateToTrash,
                onLockAndDisguise = onLockAndDisguise,
                onToggleFavorites = viewModel::toggleFavoritesFilter,
                onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                onToggleFavoriteSelected = viewModel::toggleFavoriteSelected,
                onColumnCountChanged = viewModel::setGridColumnCount,
            )
            is MediaPermissionStatus.Denied -> PermissionDeniedState(
                canAskAgain = permission.canAskAgain,
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                onOpenSafFolder = { safLauncher.launch(null) },
            )
        }

        // Interactive Full-Screen Media Viewer
        if (viewerInitialIndex != null && viewerItems.isNotEmpty()) {
            androidx.compose.runtime.key(viewerInitialIndex) {
                FullscreenMediaViewer(
                    items = viewerItems,
                    initialIndex = viewerInitialIndex ?: 0,
                    onDismiss = {
                        viewerInitialIndex = null
                        viewerItems = emptyList()
                    },
                    onFloat = { item ->
                        floatingWindowManager.openGalleryMedia(item)
                        Toast.makeText(context, "Opened in floating window", Toast.LENGTH_SHORT).show()
                    },
                    onShare = { item -> shareMediaItems(context, listOf(item)) },
                    onInfo = { item -> viewModel.setInfoItem(item) },
                    onRename = { item -> viewModel.setRenameItem(item) },
                    onMoveToVault = { item ->
                        if (!isVaultUnlocked) {
                            Toast.makeText(context, "Please unlock your vault in the Vault tab first", Toast.LENGTH_LONG).show()
                        } else {
                            viewModel.openPreview(item)
                            viewModel.setVaultPickerOpen(true)
                        }
                    },
                    onDelete = { item ->
                        itemToDelete = item
                    },
                    isTrashEnabled = state.isTrashEnabled,
                    favoritedIds = state.favoritedIds,
                    onToggleFavorite = { item -> viewModel.toggleFavorite(item) },
                )
            }
        }

        // Details / Info Dialog
        state.infoItem?.let { item ->
            MediaInfoDialog(item = item, onDismiss = { viewModel.setInfoItem(null) })
        }

        // Rename Dialog
        state.renameItem?.let { item ->
            RenameMediaDialog(
                item = item,
                onDismiss = { viewModel.setRenameItem(null) },
                onConfirm = { newName ->
                    viewModel.renameMediaItem(item, newName) { success ->
                        Toast.makeText(
                            context,
                            if (success) "Renamed successfully" else "Rename failed or not permitted",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        // Move to Vault Destination Folder Picker Dialog
        if (state.isVaultPickerOpen) {
            SelectVaultFolderDialog(
                vaultFolders = vaultFolders,
                onDismiss = { viewModel.setVaultPickerOpen(false) },
                onConfirm = { targetFolderId, deleteOriginals ->
                    viewModel.importSelectedToVault(targetFolderId, deleteOriginals) { success, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }

        // Single Item Delete Confirmation Dialog
        itemToDelete?.let { item ->
            val isTrash = state.isTrashEnabled
            AlertDialog(
                onDismissRequest = { itemToDelete = null },
                title = { Text(if (isTrash) "Move to Trash" else "Delete File", color = Color.White) },
                text = {
                    Text(
                        if (isTrash) {
                            "Move '${item.displayName}' to Trash? Files in Trash are automatically deleted after 15 days."
                        } else {
                            "Permanently delete '${item.displayName}' from this device? This cannot be undone."
                        },
                        color = Color(0xFF8E8E93),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteSingleItem(item) { success, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            itemToDelete = null
                            viewerInitialIndex = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTrash) Color(0xFFFF9500) else MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(if (isTrash) "Move to Trash 🗑️" else "Delete Permanently")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { itemToDelete = null }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF1C1C1E),
            )
        }

        // Batch Delete Confirmation Dialog
        if (showDeleteConfirmDialog) {
            val isTrash = state.isTrashEnabled
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text(if (isTrash) "Move to Trash" else "Delete Files", color = Color.White) },
                text = {
                    Text(
                        if (isTrash) {
                            "Move ${state.selectedItemIds.size} selected file(s) to Trash? Files in Trash are automatically deleted after 15 days."
                        } else {
                            "Are you sure you want to delete ${state.selectedItemIds.size} selected file(s) from your device? This cannot be undone."
                        },
                        color = Color(0xFF8E8E93),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteSelected { count, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            showDeleteConfirmDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTrash) Color(0xFFFF9500) else MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(if (isTrash) "Move to Trash 🗑️" else "Delete Permanently")
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

        // Operation in Progress Overlay
        if (state.isOperationInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .padding(32.dp)
                        .fillMaxWidth(0.85f),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        val progress = state.operationProgress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF007AFF),
                                trackColor = Color(0xFF2C2C2E),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            if (state.operationTotal > 0) {
                                Text(
                                    text = "${state.operationCurrent} / ${state.operationTotal} (${(progress * 100).toInt()}%)",
                                    color = Color(0xFF007AFF),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        } else {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        Text(
                            text = state.operationMessage ?: "Processing...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaGalleryContent(
    state: MediaBrowserState,
    onOpenSafFolder: () -> Unit,
    onRefresh: () -> Unit,
    onCategorySelected: (MediaCategory) -> Unit,
    onSearchChanged: (String) -> Unit,
    onSortSelected: (MediaSort) -> Unit,
    onItemSelected: (DeviceMediaItem) -> Unit,
    onOpenViewer: (List<DeviceMediaItem>, Int) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onViewModeChanged: (MediaViewMode) -> Unit,
    onOpenFolder: (MediaFolder) -> Unit,
    onExitFolder: () -> Unit,
    onShareSelected: () -> Unit,
    onFloatSelected: () -> Unit = {},
    onDeleteSelected: () -> Unit,
    onMoveToVaultSelected: () -> Unit,
    onNavigateToVault: () -> Unit,
    onNavigateToPcAccess: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    onLockAndDisguise: () -> Unit = {},
    onToggleFavorites: () -> Unit = {},
    onToggleFavorite: (DeviceMediaItem) -> Unit = {},
    onToggleFavoriteSelected: () -> Unit = {},
    onColumnCountChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val stealthPrefs = remember { StealthPreferences(context) }
    val haptic = LocalHapticFeedback.current
    var isOverflowOpen by remember { mutableStateOf(false) }

    val isAlbumsTab = state.viewMode == MediaViewMode.FOLDERS || state.activeFolderName != null
    val isInsideFolder = state.activeFolderName != null


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 1Gallery Top Bar with "Photos" and "Albums" Tabs + Search, PC Sync, Settings & Overflow
        if (!isInsideFolder) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Top Tabs: Photos | Albums
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Photos Tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable {
                                onViewModeChanged(MediaViewMode.TIMELINE)
                                onCategorySelected(MediaCategory.Images)
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = "Photos",
                            fontSize = 20.sp,
                            fontWeight = if (!isAlbumsTab) FontWeight.Bold else FontWeight.Normal,
                            color = if (!isAlbumsTab) Color.White else Color(0xFF8E8E93),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .height(2.5.dp)
                                .width(36.dp)
                                .background(
                                    if (!isAlbumsTab) Color.White else Color.Transparent,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }

                    // Albums Tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable {
                                onViewModeChanged(MediaViewMode.FOLDERS)
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = "Albums",
                            fontSize = 20.sp,
                            fontWeight = if (isAlbumsTab) FontWeight.Bold else FontWeight.Normal,
                            color = if (isAlbumsTab) Color.White else Color(0xFF8E8E93),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .height(2.5.dp)
                                .width(36.dp)
                                .background(
                                    if (isAlbumsTab) Color.White else Color.Transparent,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Quick Lock & Disguise Button (Only shown if stealth long-press is disabled)
                if (!stealthPrefs.isLongPressSettingsForVaultEnabled && (stealthPrefs.iconMode != com.vaultsync.core.stealth.IconMode.NORMAL || stealthPrefs.isAppLockEnabled)) {
                    IconButton(onClick = onLockAndDisguise) {
                        Icon(
                            imageVector = if (stealthPrefs.iconMode == com.vaultsync.core.stealth.IconMode.CALCULATOR) Icons.Default.Calculate else Icons.Default.Lock,
                            contentDescription = "Lock & Disguise",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Settings Icon Button: tap opens Settings, long-press opens Secret Vault!
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = onNavigateToSettings,
                            onLongClick = {
                                if (stealthPrefs.isLongPressSettingsForVaultEnabled) {
                                    try {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } catch (_: Exception) {}
                                    onNavigateToVault()
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                // Overflow Menu Icon
                Box {
                    IconButton(onClick = { isOverflowOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = isOverflowOpen,
                        onDismissRequest = { isOverflowOpen = false },
                        containerColor = Color(0xFF1C1C1E),
                    ) {
                        DropdownMenuItem(
                            text = { Text("PC Sync & Browser Access", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Devices, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                isOverflowOpen = false
                                onNavigateToPcAccess()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                isOverflowOpen = false
                                onNavigateToSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Recycle Bin (${state.trashCount})", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                isOverflowOpen = false
                                onNavigateToTrash()
                            },
                        )
                        if (!stealthPrefs.isLongPressSettingsForVaultEnabled) {
                            DropdownMenuItem(
                                text = { Text("Secure Vault", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    isOverflowOpen = false
                                    onNavigateToVault()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Refresh Gallery", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                isOverflowOpen = false
                                onRefresh()
                            },
                        )
                        if (!stealthPrefs.isLongPressSettingsForVaultEnabled && (stealthPrefs.iconMode != com.vaultsync.core.stealth.IconMode.NORMAL || stealthPrefs.isAppLockEnabled)) {
                            DropdownMenuItem(
                                text = { Text("Lock & Disguise", color = Color(0xFFFF9F0A)) },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    isOverflowOpen = false
                                    onLockAndDisguise()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Browse Audio Files", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                isOverflowOpen = false
                                onCategorySelected(MediaCategory.Audio)
                                onViewModeChanged(MediaViewMode.TIMELINE)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open Custom Folder (SAF)", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                isOverflowOpen = false
                                onOpenSafFolder()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Sort: ${state.sort.label}", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                isOverflowOpen = false
                                val nextSort = when (state.sort) {
                                    MediaSort.Newest -> MediaSort.Oldest
                                    MediaSort.Oldest -> MediaSort.NameAsc
                                    MediaSort.NameAsc -> MediaSort.SizeLargest
                                    else -> MediaSort.Newest
                                }
                                onSortSelected(nextSort)
                            },
                        )
                    }
                }
            }

            // 1Gallery Circular Album Shortcut Icons Row (Directly matching reference screenshot!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Shortcut 1: Videos (Movie icon)
                CircularShortcutButton(
                    imageVector = Icons.Default.Movie,
                    contentDescription = "Videos",
                    isSelected = state.category == MediaCategory.Videos,
                    onClick = {
                        onCategorySelected(if (state.category == MediaCategory.Videos) MediaCategory.Images else MediaCategory.Videos)
                        onViewModeChanged(MediaViewMode.TIMELINE)
                    },
                )

                // Shortcut 2: Star / Favorites (working toggle filter!)
                CircularShortcutButton(
                    imageVector = if (state.isFavoritesOnly) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorites",
                    isSelected = state.isFavoritesOnly,
                    iconColor = if (state.isFavoritesOnly) Color.Black else Color(0xFFFFD700),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleFavorites()
                    },
                )

                // Shortcut 3: Secure Vault (Lock) - hidden when long-press settings is active
                if (!stealthPrefs.isLongPressSettingsForVaultEnabled) {
                    CircularShortcutButton(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Secure Vault",
                        isSelected = false,
                        onClick = onNavigateToVault,
                    )
                }

                // Shortcut 4: PC Sync (Devices)
                CircularShortcutButton(
                    imageVector = Icons.Default.Devices,
                    contentDescription = "PC Sync",
                    isSelected = false,
                    onClick = onNavigateToPcAccess,
                )

                // Shortcut 5: Recycle Bin (placed beside PC access, visible on Photos & Albums tabs)
                CircularShortcutButton(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Recycle Bin",
                    isSelected = false,
                    onClick = onNavigateToTrash,
                    badgeCount = state.trashCount,
                )
            }
        } else {
            // Folder Detail View Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExitFolder) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.activeFolderName ?: "Album",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${state.visibleItems.size} items",
                        color = Color(0xFF8E8E93),
                        fontSize = 12.sp,
                    )
                }

                TextButton(onClick = onSelectAll) {
                    Text("Select All", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        // Selection Action Bar
        if (state.hasSelection) {
            Surface(
                color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${state.selectedItemIds.size} selected",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearSelection) {
                        Text("Clear", color = Color(0xFF8E8E93))
                    }
                    val allSelectedFavorited = state.selectedItemIds.isNotEmpty() && state.selectedItemIds.all { it in state.favoritedIds }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleFavoriteSelected()
                    }) {
                        Icon(
                            imageVector = if (allSelectedFavorited) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite selected",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onFloatSelected) {
                        FloatingWindowIcon()
                    }
                    IconButton(onClick = onMoveToVaultSelected) {
                        Icon(Icons.Default.Lock, contentDescription = "Move to Vault", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onShareSelected) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDeleteSelected) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Main Body View
        when {
            state.isLoading -> LoadingState()
            state.errorMessage != null -> ErrorState(state.errorMessage, onRefresh)
            isInsideFolder -> {
                // Inside an Album -> Full media grid of this album
                if (state.visibleItems.isEmpty()) {
                    if (state.isFavoritesOnly) {
                        FavoritesEmptyState()
                    } else {
                        EmptyState(state.isSafMode, state.category)
                    }
                } else {
                    PhotosGrid(
                        items = state.visibleItems,
                        selectedIds = state.selectedItemIds,
                        favoritedIds = state.favoritedIds,
                        columnCount = state.gridColumnCount,
                        onColumnCountChanged = onColumnCountChanged,
                        onItemClick = { index -> onOpenViewer(state.visibleItems, index) },
                        onItemToggleSelection = onItemSelected,
                        onToggleFavorite = onToggleFavorite,
                        hasSelection = state.hasSelection,
                    )
                }
            }
            state.viewMode == MediaViewMode.FOLDERS -> {
                // "Albums" Tab -> 1Gallery Sectioned Previews Layout
                if (state.folders.isEmpty()) {
                    if (state.isFavoritesOnly) {
                        FavoritesEmptyState()
                    } else {
                        EmptyState(state.isSafMode, state.category)
                    }
                } else {
                    AlbumsListView(
                        folders = state.folders,
                        onOpenFolder = onOpenFolder,
                        onOpenViewer = onOpenViewer,
                    )
                }
            }
            state.category == MediaCategory.Audio -> {
                // Audio dedicated view
                if (state.visibleItems.isEmpty()) {
                    if (state.isFavoritesOnly) {
                        FavoritesEmptyState()
                    } else {
                        EmptyState(state.isSafMode, state.category)
                    }
                } else {
                    AudioListView(
                        items = state.visibleItems,
                        selectedIds = state.selectedItemIds,
                        onItemClick = { index -> onOpenViewer(state.visibleItems, index) },
                        onToggleSelection = onItemSelected,
                    )
                }
            }
            else -> {
                // "Photos" Tab -> Chronological Grid of Photos & Videos
                if (state.visibleItems.isEmpty()) {
                    if (state.isFavoritesOnly) {
                        FavoritesEmptyState()
                    } else {
                        EmptyState(state.isSafMode, state.category)
                    }
                } else {
                    PhotosGrid(
                        items = state.visibleItems,
                        selectedIds = state.selectedItemIds,
                        favoritedIds = state.favoritedIds,
                        columnCount = state.gridColumnCount,
                        onColumnCountChanged = onColumnCountChanged,
                        onItemClick = { index -> onOpenViewer(state.visibleItems, index) },
                        onItemToggleSelection = onItemSelected,
                        onToggleFavorite = onToggleFavorite,
                        hasSelection = state.hasSelection,
                    )
                }
            }
        }
    }
}

/**
 * Circular shortcut button matching the 3 top circular icons in the 1Gallery screenshot
 */
@Composable
private fun CircularShortcutButton(
    imageVector: ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0,
    iconColor: Color? = null,
) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(if (isSelected) Color.White else Color(0xFF1C1C1E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = iconColor ?: if (isSelected) Color.Black else Color.White,
            modifier = Modifier.size(24.dp),
        )
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .background(Color(0xFFFF3B30), CircleShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Albums List matching 1Gallery reference screenshot:
 * - Album title ("Camera", "Download", "Screenshots", "Movies")
 * - 3 large rounded preview thumbnails in a horizontal row
 * - Video duration badge
 * - "+2195" count overlay on the 3rd thumbnail when remaining items exist
 */
@Composable
private fun AlbumsListView(
    folders: List<MediaFolder>,
    onOpenFolder: (MediaFolder) -> Unit,
    onOpenViewer: (List<DeviceMediaItem>, Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(folders, key = { it.key }) { folder ->
            AlbumPreviewRow(
                folder = folder,
                onAlbumClick = { onOpenFolder(folder) },
                onItemClick = { index ->
                    val targetItems = if (folder.allItems.isNotEmpty()) folder.allItems else folder.previewItems
                    if (targetItems.isNotEmpty()) {
                        onOpenViewer(targetItems, index)
                    } else {
                        onOpenFolder(folder)
                    }
                },
            )
        }
    }
}

@Composable
private fun AlbumPreviewRow(
    folder: MediaFolder,
    onAlbumClick: () -> Unit,
    onItemClick: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAlbumClick),
    ) {
        // Album Title
        Text(
            text = folder.name,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Row of up to 3 thumbnails side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val previewItems = folder.previewItems
            val totalCount = folder.itemCount

            // Preview 1
            val item0 = previewItems.getOrNull(0)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1C1C1E))
                    .clickable {
                        if (item0 != null) onItemClick(0) else onAlbumClick()
                    },
            ) {
                if (item0?.uri != null) {
                    AsyncImage(
                        model = item0.uri,
                        contentDescription = folder.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (item0.category == MediaCategory.Videos) {
                        VideoDurationBadge(
                            durationMillis = item0.durationMillis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                        )
                    }
                }
            }

            // Preview 2
            val item1 = previewItems.getOrNull(1)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1C1C1E))
                    .clickable {
                        if (item1 != null) onItemClick(1) else onAlbumClick()
                    },
            ) {
                if (item1?.uri != null) {
                    AsyncImage(
                        model = item1.uri,
                        contentDescription = folder.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (item1.category == MediaCategory.Videos) {
                        VideoDurationBadge(
                            durationMillis = item1.durationMillis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                        )
                    }
                }
            }

            // Preview 3 (with +N overlay if remaining count > 0)
            val item2 = previewItems.getOrNull(2)
            val remainingCount = totalCount - 2
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1C1C1E))
                    .clickable(onClick = onAlbumClick),
            ) {
                if (item2?.uri != null) {
                    AsyncImage(
                        model = item2.uri,
                        contentDescription = folder.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }

                // If album has more items than 2 shown, show +N count overlay!
                if (remainingCount > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+$remainingCount",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (item2?.category == MediaCategory.Videos) {
                    VideoDurationBadge(
                        durationMillis = item2.durationMillis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoDurationBadge(
    durationMillis: Long,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "▶ ${formatDuration(durationMillis)}",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Photos Grid: Chronological grid of images & videos with rounded corners
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotosGrid(
    items: List<DeviceMediaItem>,
    selectedIds: Set<String>,
    favoritedIds: Set<String> = emptySet(),
    columnCount: Int = 3,
    onColumnCountChanged: (Int) -> Unit = {},
    onItemClick: (Int) -> Unit,
    onItemToggleSelection: (DeviceMediaItem) -> Unit,
    onToggleFavorite: (DeviceMediaItem) -> Unit = {},
    hasSelection: Boolean,
) {
    val haptic = LocalHapticFeedback.current
    var zoomAccumulator by remember { mutableFloatStateOf(1f) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount.coerceIn(2, 5)),
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(columnCount) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val downPointers = event.changes.filter { it.pressed }
                        // Only intercept when 2 or more pointers are active (pinch-to-zoom)
                        if (downPointers.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                zoomAccumulator *= zoom
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                        change.consume()
                                    }
                                }
                                if (zoomAccumulator > 1.35f) {
                                    zoomAccumulator = 1f
                                    if (columnCount > 2) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onColumnCountChanged(columnCount - 1)
                                    }
                                } else if (zoomAccumulator < 0.72f) {
                                    zoomAccumulator = 1f
                                    if (columnCount < 5) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onColumnCountChanged(columnCount + 1)
                                    }
                                }
                            }
                        } else {
                            zoomAccumulator = 1f
                        }
                    } while (event.changes.any { it.pressed })
                    zoomAccumulator = 1f
                }
            },
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val isSelected = item.id in selectedIds
            val isFavorited = item.id in favoritedIds

            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1C1C1E))
                    .combinedClickable(
                        onClick = {
                            if (hasSelection) {
                                onItemToggleSelection(item)
                            } else {
                                onItemClick(index)
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onItemToggleSelection(item)
                        },
                    ),
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                // Selected highlight overlay
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.3f))
                            .border(2.dp, Color.White, RoundedCornerShape(6.dp)),
                    )
                }

                // Favorite star badge
                if (isFavorited) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleFavorite(item)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favorite",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                // Video badge
                if (item.category == MediaCategory.Videos) {
                    VideoDurationBadge(
                        durationMillis = item.durationMillis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp),
                    )
                }

                // Selection check indicator
                if (hasSelection) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Color.White else Color.Black.copy(alpha = 0.5f))
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Audio List View for audio browsing without cluttering visual gallery
 */
@Composable
private fun AudioListView(
    items: List<DeviceMediaItem>,
    selectedIds: Set<String>,
    onItemClick: (Int) -> Unit,
    onToggleSelection: (DeviceMediaItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val isSelected = item.id in selectedIds

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Color(0xFF2C2C2E) else Color(0xFF1C1C1E))
                    .clickable { onItemClick(index) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🎵", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.folderName} • ${formatFileSize(item.sizeBytes)}",
                        color = Color(0xFF8E8E93),
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { onToggleSelection(item) }) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Selected" else "Unselected",
                        tint = if (isSelected) Color.White else Color(0xFF8E8E93),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * Fullscreen Interactive Media Viewer with HorizontalPager (swipe), Pinch-to-zoom & pan,
 * ExoPlayer video controls, and immersive controls toggle on tap.
 */
@Composable
fun FullscreenMediaViewer(
    items: List<DeviceMediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onShare: (DeviceMediaItem) -> Unit,
    onInfo: (DeviceMediaItem) -> Unit,
    onRename: (DeviceMediaItem) -> Unit,
    onMoveToVault: (DeviceMediaItem) -> Unit,
    onDelete: (DeviceMediaItem) -> Unit,
    isTrashEnabled: Boolean = true,
    favoritedIds: Set<String> = emptySet(),
    onToggleFavorite: (DeviceMediaItem) -> Unit = {},
    onFloat: ((DeviceMediaItem) -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)
    val haptic = LocalHapticFeedback.current

    val clampedIndex = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = clampedIndex, pageCount = { items.size })
    var showControls by remember { mutableStateOf(true) }
    var isCurrentZoomed by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        showMenu = false
    }

    val currentItem = items.getOrNull(pagerState.currentPage) ?: items.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (currentItem != null) {
            // Swipeable Media Pager between all photos and videos
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !isCurrentZoomed,
                key = { page -> items.getOrNull(page)?.id ?: page.toString() },
            ) { page ->
                val item = items[page]
                when (item.category) {
                    MediaCategory.Images -> {
                        ZoomableImageViewer(
                            item = item,
                            onToggleControls = { showControls = !showControls },
                            onZoomChanged = { zoomed ->
                                if (pagerState.currentPage == page) {
                                    isCurrentZoomed = zoomed
                                }
                            },
                        )
                    }
                    MediaCategory.Videos -> {
                        FullscreenVideoPlayer(
                            item = item,
                            isPlayingActive = (pagerState.currentPage == page),
                            showControls = showControls,
                            onToggleControls = { showControls = !showControls },
                            onZoomChanged = { zoomed ->
                                if (pagerState.currentPage == page) {
                                    isCurrentZoomed = zoomed
                                }
                            },
                        )
                    }
                    MediaCategory.Audio -> {
                        FullscreenAudioPlayer(
                            item = item,
                            onToggleControls = { showControls = !showControls },
                        )
                    }
                }
            }

            // Top Controls Overlay with 3-dot overflow menu
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentItem.displayName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${currentItem.folderName} • ${pagerState.currentPage + 1}/${items.size}",
                            color = Color(0xFF8E8E93),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (onFloat != null) {
                        IconButton(onClick = { onFloat(currentItem) }) {
                            FloatingWindowIcon()
                        }
                    }
                    val isCurrentFav = currentItem.id in favoritedIds
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleFavorite(currentItem)
                    }) {
                        Icon(
                            imageVector = if (isCurrentFav) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (isCurrentFav) Color(0xFFFFD700) else Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { onShare(currentItem) }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = Color(0xFF1E1E1E),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Move to Vault", color = Color.White) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onMoveToVault(currentItem)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share", color = Color.White) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onShare(currentItem)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename", color = Color.White) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onRename(currentItem)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Details & EXIF", color = Color.White) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onInfo(currentItem)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isTrashEnabled) "Move to Trash" else "Delete Permanently",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete(currentItem)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun distanceBetween(p1: Offset, p2: Offset): Float {
    return hypot(p1.x - p2.x, p1.y - p2.y)
}

/**
 * Zoomable image viewer with smooth continuous pinch-to-zoom (up to 5x),
 * pan gesture tracking, double-tap to zoom, and integration with HorizontalPager
 */
@Composable
private fun ZoomableImageViewer(
    item: DeviceMediaItem,
    onToggleControls: () -> Unit,
    onZoomChanged: (Boolean) -> Unit = {},
) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }

    val coroutineScope = rememberCoroutineScope()
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.05f)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.scaleX = scale
                    this.scaleY = scale
                    this.translationX = offset.x
                    this.translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )

        // Multi-touch Gesture Detection Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.id, containerWidth, containerHeight) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        val downPos = firstDown.position
                        var isMultiTouch = false
                        var isPanGesture = false
                        var hasMovedPastSlop = false
                        val touchSlop = viewConfiguration.touchSlop

                        var prevDistance = 0f
                        var prevCentroid = Offset.Zero

                        do {
                            val event = awaitPointerEvent()
                            val activePointers = event.changes.filter { it.pressed }
                            val count = activePointers.size

                            if (count >= 2) {
                                isMultiTouch = true
                                val p0 = activePointers[0].position
                                val p1 = activePointers[1].position
                                val currentDistance = distanceBetween(p0, p1)
                                val currentCentroid = Offset((p0.x + p1.x) * 0.5f, (p0.y + p1.y) * 0.5f)

                                if (prevDistance > 0f) {
                                    val zoomRatio = currentDistance / prevDistance
                                    val panDelta = currentCentroid - prevCentroid

                                    val newScale = (scale * zoomRatio).coerceIn(1f, 5f)
                                    scale = newScale

                                    if (newScale > 1.02f) {
                                        val maxOffsetX = ((newScale - 1f) * containerWidth * 0.5f).coerceAtLeast(0f)
                                        val maxOffsetY = ((newScale - 1f) * containerHeight * 0.5f).coerceAtLeast(0f)
                                        val newX = (offset.x + panDelta.x).coerceIn(-maxOffsetX, maxOffsetX)
                                        val newY = (offset.y + panDelta.y).coerceIn(-maxOffsetY, maxOffsetY)
                                        offset = Offset(newX, newY)
                                    } else {
                                        offset = Offset.Zero
                                    }
                                }

                                prevDistance = currentDistance
                                prevCentroid = currentCentroid

                                // Consume so parent HorizontalPager does not swipe!
                                event.changes.forEach { it.consume() }

                            } else if (count == 1 && scale > 1.05f) {
                                // 1 finger and currently zoomed in: Pan around smoothly
                                val p = activePointers[0]
                                val currentPos = p.position

                                if (prevCentroid != Offset.Zero) {
                                    val panDelta = currentPos - prevCentroid
                                    if (hypot(panDelta.x, panDelta.y) > 1f) {
                                        isPanGesture = true
                                    }
                                    val maxOffsetX = ((scale - 1f) * containerWidth * 0.5f).coerceAtLeast(0f)
                                    val maxOffsetY = ((scale - 1f) * containerHeight * 0.5f).coerceAtLeast(0f)
                                    val newX = (offset.x + panDelta.x).coerceIn(-maxOffsetX, maxOffsetX)
                                    val newY = (offset.y + panDelta.y).coerceIn(-maxOffsetY, maxOffsetY)
                                    offset = Offset(newX, newY)
                                }

                                prevCentroid = currentPos
                                prevDistance = 0f

                                // Consume so parent HorizontalPager doesn't swipe while zoomed in
                                event.changes.forEach { it.consume() }

                            } else if (count == 1) {
                                // 1 finger and not zoomed (scale <= 1.05f)
                                val currentPos = activePointers[0].position
                                val dragDistance = distanceBetween(currentPos, downPos)
                                if (dragDistance > touchSlop) {
                                    hasMovedPastSlop = true
                                    // Do NOT consume! Let HorizontalPager handle page swipe!
                                }
                                prevCentroid = currentPos
                                prevDistance = 0f
                            }
                        } while (event.changes.any { it.pressed })

                        // Gesture ended
                        val durationMs = System.currentTimeMillis() - downTime
                        if (!isMultiTouch && !isPanGesture && !hasMovedPastSlop && durationMs < 400L) {
                            val now = System.currentTimeMillis()
                            val tapPos = downPos

                            if (now - lastTapTime < 320L && distanceBetween(tapPos, lastTapPosition) < 120f) {
                                // Double-tap detected: toggle zoom
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = null
                                lastTapTime = 0L

                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                    offset = Offset.Zero
                                }
                            } else {
                                // First tap: Wait briefly to see if second tap follows; if not, toggle controls
                                lastTapTime = now
                                lastTapPosition = tapPos
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = coroutineScope.launch {
                                    delay(300)
                                    onToggleControls()
                                }
                            }
                        }
                    }
                }
        )

        // Zoom Level Indicator & Quick Reset Chip
        if (scale > 1.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .clickable {
                        scale = 1f
                        offset = Offset.Zero
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "🔍 ${"%.1f".format(scale)}x  ✕ Reset",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Fullscreen video player with ExoPlayer, orientation control, rotation, pinch-to-zoom & speed control
 */
@Composable
private fun FullscreenVideoPlayer(
    item: DeviceMediaItem,
    isPlayingActive: Boolean,
    showControls: Boolean = true,
    onToggleControls: () -> Unit,
    onZoomChanged: (Boolean) -> Unit = {},
) {
    val uri = item.uri ?: return
    EnhancedVideoPlayer(
        mediaUri = uri,
        isPlayingActive = isPlayingActive,
        showControls = showControls,
        bottomPadding = 16.dp,
        onToggleControls = onToggleControls,
        onZoomChanged = onZoomChanged,
    )
}

@Composable
private fun FullscreenAudioPlayer(
    item: DeviceMediaItem,
    onToggleControls: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            item.uri?.let { setMediaItem(MediaItem.fromUri(it)) }
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTapGestures(onTap = { onToggleControls() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🎵", fontSize = 72.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = item.displayName,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = item.folderName,
                color = Color(0xFF8E8E93),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MediaInfoDialog(
    item: DeviceMediaItem,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isStripping by remember { mutableStateOf(false) }

    val exifDetails by produceState<ExifDetails?>(initialValue = null, item.uri) {
        val uri = item.uri
        if (uri != null && item.category == MediaCategory.Images) {
            value = ExifMetadataInspector.extractExif(context, uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Media Details & EXIF", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Basic Information", color = Color(0xFF0A84FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                item { InfoRow("File Name", item.displayName) }
                item { InfoRow("Folder", item.folderName) }
                item { InfoRow("Category", item.category.label) }
                item { InfoRow("MIME Type", item.mimeType ?: "Unknown") }
                item { InfoRow("File Size", formatFileSize(item.sizeBytes)) }
                item { InfoRow("Date Modified", formatDate(item.dateModifiedMillis)) }

                exifDetails?.let { exif ->
                    item {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = Color(0xFF2C2C2E),
                        )
                    }
                    item {
                        Text("Camera & Shot Details", color = Color(0xFF0A84FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    val camera = listOfNotNull(exif.cameraMake, exif.cameraModel).joinToString(" ").ifBlank { null }
                    if (camera != null) {
                        item { InfoRow("Camera Model", camera) }
                    }
                    val specs = listOfNotNull(
                        exif.aperture,
                        exif.exposureTime,
                        exif.focalLength,
                        exif.iso,
                    ).joinToString(" • ")
                    if (specs.isNotBlank()) {
                        item { InfoRow("Capture Specs", specs) }
                    }
                    if (exif.width > 0 && exif.height > 0) {
                        item {
                            InfoRow(
                                "Resolution",
                                "${exif.width} × ${exif.height} (${String.format(Locale.US, "%.1f MP", (exif.width.toLong() * exif.height) / 1000000.0)})",
                            )
                        }
                    }
                    if (exif.dateTaken != null) {
                        item { InfoRow("Original Capture Date", exif.dateTaken) }
                    }
                    if (exif.hasLocation && exif.latitude != null && exif.longitude != null) {
                        item {
                            InfoRow(
                                "GPS Coordinates",
                                String.format(Locale.US, "%.5f, %.5f (Embedded in EXIF)", exif.latitude, exif.longitude),
                            )
                        }
                    }

                    // Share Clean EXIF Button
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                val uri = item.uri ?: return@OutlinedButton
                                isStripping = true
                                coroutineScope.launch {
                                    val cleanFile = ExifMetadataInspector.stripExifAndCreateCleanCopy(context, uri)
                                    isStripping = false
                                    if (cleanFile != null) {
                                        try {
                                            val cleanUri = androidx.core.content.FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                cleanFile,
                                            )
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "image/jpeg"
                                                putExtra(Intent.EXTRA_STREAM, cleanUri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share Clean Photo"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Could not strip EXIF", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isStripping,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color(0xFF30D158),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isStripping) "Stripping EXIF..." else "Share Clean (Without EXIF)",
                                color = Color.White,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text("Close")
            }
        },
        containerColor = Color(0xFF1C1C1E),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF8E8E93))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

@Composable
private fun RenameMediaDialog(
    item: DeviceMediaItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newName by remember { mutableStateOf(item.displayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename File", color = Color.White) },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New file name", color = Color(0xFF8E8E93)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank() && newName != item.displayName) {
                        onConfirm(newName)
                    }
                },
                enabled = newName.isNotBlank() && newName != item.displayName,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                Text("Rename")
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

@Composable
private fun SelectVaultFolderDialog(
    vaultFolders: List<VaultFolder>,
    onDismiss: () -> Unit,
    onConfirm: (targetFolderId: String?, deleteOriginals: Boolean) -> Unit,
) {
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var deleteOriginals by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import to Private Vault", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select destination folder inside Vault:", color = Color(0xFF8E8E93))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolderId = null }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = selectedFolderId == null,
                                onClick = { selectedFolderId = null },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Vault Root (No folder)", color = Color.White)
                        }
                    }
                    items(vaultFolders) { folder ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolderId = folder.id }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = selectedFolderId == folder.id,
                                onClick = { selectedFolderId = folder.id },
                            )
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(folder.name, color = Color.White)
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = deleteOriginals,
                        onCheckedChange = { deleteOriginals = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Delete original file from device after encrypting",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E8E93),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedFolderId, deleteOriginals) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                Text("Encrypt & Move")
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

private fun shareMediaItems(context: Context, items: List<DeviceMediaItem>) {
    if (items.isEmpty()) return
    val uris = items.mapNotNull { it.uri }
    if (uris.isEmpty()) return

    try {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items.first().mimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(intent, "Share media via"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share files: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PermissionDeniedState(
    canAskAgain: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSafFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = Color(0xFF8E8E93),
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Media Permission Needed",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "VaultSync needs image and video access to display your gallery. Original files stay untouched on your device."
            } else {
                "VaultSync needs storage access to display your gallery. Original files stay untouched on your device."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF8E8E93),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canAskAgain) {
                Button(
                    onClick = onRequestPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text("Grant Permission", fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(
                onClick = onOpenSafFolder,
                shape = RoundedCornerShape(24.dp),
            ) {
                Text("Open Folder", color = Color.White)
            }
        }
    }
}

@Composable
private fun FavoritesEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Color(0xFFFFD700).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = Color(0xFFFFD700),
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "No favourites yet",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No favourites yet. Long-press a photo to star it, or tap the star icon while viewing media.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF8E8E93),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(
    isSafMode: Boolean,
    category: MediaCategory,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            tint = Color(0xFF8E8E93),
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No ${category.label.lowercase(Locale.US)} found",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isSafMode) {
                "The selected folder does not contain supported ${category.label.lowercase(Locale.US)} files."
            } else {
                "Take photos, record videos, or save images to see them here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF8E8E93),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFFF9F0A),
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Unable to load media", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF8E8E93),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Text("Retry")
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = millis / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun formatDate(dateMillis: Long): String {
    if (dateMillis <= 0L) return "Unknown date"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(dateMillis))
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${bytes} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }
}
