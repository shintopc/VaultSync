package com.vaultsync.feature.vault

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import com.vaultsync.core.model.VaultFolder
import com.vaultsync.core.model.VaultSortOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultMainScreen(
    viewModel: VaultViewModel,
    onNavigateBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val allFolders by viewModel.allFolders.collectAsState()
    val folders by viewModel.filteredFolders.collectAsState()
    val entries by viewModel.filteredEntries.collectAsState()
    val vaultAlbums by viewModel.vaultAlbums.collectAsState()
    val photoEntries by viewModel.photoEntries.collectAsState()
    val audioEntries by viewModel.audioEntries.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    var isSortMenuOpen by remember { mutableStateOf(false) }
    var isMoreMenuOpen by remember { mutableStateOf(false) }
    var isFabMenuOpen by remember { mutableStateOf(false) }
    var isMediaPickerOpen by remember { mutableStateOf(false) }

    var showBatchMoveDialog by remember { mutableStateOf(false) }
    var showBatchCopyDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchMoveOutDialog by remember { mutableStateOf(false) }

    // Intercept back button when inside a folder or during selection
    val isInsideFolder = uiState.currentFolderId != null || uiState.viewTab == VaultViewTab.PHOTOS
    BackHandler(enabled = isInsideFolder || uiState.hasSelection) {
        if (uiState.hasSelection) {
            viewModel.clearSelection()
        } else if (uiState.currentFolderId != null) {
            viewModel.navigateUp()
        } else {
            viewModel.setViewTab(VaultViewTab.ALBUMS)
        }
    }

    // File picker launcher for imports
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris, context.contentResolver)
        }
    }

    // Export launcher
    var entryToExport by remember { mutableStateOf<VaultEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        if (uri != null && entryToExport != null) {
            viewModel.exportEntry(entryToExport!!, uri, context.contentResolver)
            entryToExport = null
        }
    }

    // Feedback messages
    LaunchedEffect(uiState.errorMessage, uiState.infoMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearMessages()
        }
        uiState.infoMessage?.let { info ->
            snackbarHostState.showSnackbar(info)
            viewModel.clearMessages()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black),
            ) {
                if (uiState.hasSelection) {
                    // Contextual Selection Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Text("✕", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "${uiState.selectedEntryIds.size} Selected",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))

                        val currentItems = when {
                            uiState.currentFolderId != null -> entries
                            uiState.viewTab == VaultViewTab.PHOTOS -> photoEntries
                            uiState.viewTab == VaultViewTab.AUDIO -> audioEntries
                            else -> emptyList()
                        }
                        val allSelected = currentItems.isNotEmpty() && currentItems.all { uiState.selectedEntryIds.contains(it.id) }
                        TextButton(onClick = {
                            if (allSelected) {
                                viewModel.clearSelection()
                            } else {
                                viewModel.selectAllEntries(currentItems.map { it.id })
                            }
                        }) {
                            Text(
                                if (allSelected) "Deselect" else "Select All",
                                color = Color.White,
                                fontSize = 13.sp,
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Move out of Vault to Gallery
                        IconButton(onClick = { showBatchMoveOutDialog = true }) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = "Export to Gallery", tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        // Move to folder
                        IconButton(onClick = { showBatchMoveDialog = true }) {
                            Icon(Icons.Default.Folder, contentDescription = "Move to folder", tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        // Copy to folder
                        IconButton(onClick = { showBatchCopyDialog = true }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy to folder", tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        // Delete permanently
                        IconButton(onClick = { showBatchDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                } else if (!isInsideFolder) {
                    // Top Header Row: Albums | Audio | Lock | 3 dots
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Back Button to exit Vault to Gallery
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Top Tabs: Albums | Audio
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Albums Tab
                            TopNavTab(
                                title = "Albums",
                                isSelected = uiState.viewTab == VaultViewTab.ALBUMS,
                                onClick = { viewModel.setViewTab(VaultViewTab.ALBUMS) },
                            )

                            // Audio Tab
                            TopNavTab(
                                title = "Audio",
                                isSelected = uiState.viewTab == VaultViewTab.AUDIO,
                                onClick = { viewModel.setViewTab(VaultViewTab.AUDIO) },
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Quick Lock
                        IconButton(onClick = { viewModel.lockVault() }) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock Vault", tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        // Overflow Menu (3 dots)
                        Box {
                            IconButton(onClick = { isMoreMenuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                            DropdownMenu(
                                expanded = isMoreMenuOpen,
                                onDismissRequest = { isMoreMenuOpen = false },
                                containerColor = Color(0xFF1C1C1E),
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiState.isBiometricEnabled) "Disable Biometrics 👆"
                                            else "Enable Biometrics 👆",
                                            color = Color.White,
                                        )
                                    },
                                    onClick = {
                                        isMoreMenuOpen = false
                                        viewModel.toggleBiometric(!uiState.isBiometricEnabled)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Vault Storage & Info 📊", color = Color.White) },
                                    onClick = {
                                        isMoreMenuOpen = false
                                        viewModel.openStorageDialog()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Vault Recovery Key 🔑", color = Color.White) },
                                    onClick = {
                                        isMoreMenuOpen = false
                                        viewModel.loadOrGenerateRecoveryKey()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                if (uiState.isSharedStorageActive) "Storage: Shared Vault 📂" else "Migrate to Shared Storage 📦",
                                                color = Color.White,
                                            )
                                            Text(
                                                if (uiState.isSharedStorageActive) "Documents/VaultSync (Survives Uninstall)" else "Move to Documents (Survives Uninstall)",
                                                fontSize = 11.sp,
                                                color = if (uiState.isSharedStorageActive) Color(0xFF38BDF8) else Color(0xFFF59E0B),
                                            )
                                        }
                                    },
                                    onClick = {
                                        isMoreMenuOpen = false
                                        viewModel.openMigrationDialog()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Lock Vault 🔒", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        isMoreMenuOpen = false
                                        viewModel.lockVault()
                                    },
                                )
                            }
                        }
                    }
                } else {
                    // Inside Folder / Main Vault Top Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            if (uiState.currentFolderId != null) {
                                viewModel.navigateUp()
                            } else {
                                viewModel.setViewTab(VaultViewTab.ALBUMS)
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.currentFolder?.name ?: "Main Vault",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val currentCount = if (uiState.currentFolderId != null) entries.size else photoEntries.size
                            Text(
                                text = "$currentCount items",
                                color = Color(0xFF8E8E93),
                                fontSize = 12.sp,
                            )
                        }

                        // Grid / List toggle
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(
                                imageVector = if (uiState.isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = "Toggle view",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        // Quick Lock
                        IconButton(onClick = { viewModel.lockVault() }) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock Vault", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!uiState.hasSelection) {
                Column(horizontalAlignment = Alignment.End) {
                if (isFabMenuOpen) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        isFabMenuOpen = false
                                        isMediaPickerOpen = true
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Add Photos & Videos", fontWeight = FontWeight.Medium, color = Color.White)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        isFabMenuOpen = false
                                        viewModel.openCreateFolderDialog()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("New Folder", fontWeight = FontWeight.Medium, color = Color.White)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        isFabMenuOpen = false
                                        importLauncher.launch(arrayOf("*/*"))
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Browse System Files", fontWeight = FontWeight.Medium, color = Color.White)
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick = { isFabMenuOpen = !isFabMenuOpen },
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ) {
                    Icon(
                        imageVector = if (isFabMenuOpen) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (isFabMenuOpen) "Close" else "Add",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp),
                    )
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
            when {
                // Folder detail view (Inside an album)
                // Folder detail view (Inside a subfolder)
                uiState.currentFolderId != null -> {
                    if (entries.isEmpty()) {
                        EmptyVaultSection(
                            icon = "📭",
                            title = "Folder is Empty",
                            description = "Add photos, videos, or documents to this folder.",
                            actionLabel = "Import Media",
                            onAction = { isMediaPickerOpen = true },
                        )
                    } else if (uiState.isGridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(entries, key = { _, it -> it.id }) { index, entry ->
                                VaultPhotoGridItem(
                                    entry = entry,
                                    isSelected = uiState.selectedEntryIds.contains(entry.id),
                                    hasSelection = uiState.hasSelection,
                                    viewModel = viewModel,
                                    onClick = {
                                        if (entry.category == VaultCategory.Images || entry.category == VaultCategory.Videos) {
                                            viewModel.openViewer(entries, index)
                                        } else {
                                            viewModel.openPreview(entry)
                                        }
                                    },
                                    onToggleSelect = { viewModel.toggleSelectEntry(entry.id) },
                                    onMoveOutOfVault = { viewModel.moveOutOfVault(entry) },
                                    onMoreClick = { viewModel.showEntryDetails(entry) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(entries, key = { "entry_list_${it.id}" }) { entry ->
                                EntryListItem(
                                    entry = entry,
                                    isSelected = uiState.selectedEntryIds.contains(entry.id),
                                    hasSelection = uiState.hasSelection,
                                    showFolderBadge = false,
                                    allFolders = allFolders,
                                    onClick = {
                                        if (uiState.hasSelection) {
                                            viewModel.toggleSelectEntry(entry.id)
                                        } else {
                                            viewModel.openPreview(entry)
                                        }
                                    },
                                    onToggleSelect = { viewModel.toggleSelectEntry(entry.id) },
                                    onMoveOutOfVault = { viewModel.moveOutOfVault(entry) },
                                    onRename = { viewModel.openRenameEntryDialog(entry) },
                                    onMove = { viewModel.openMoveEntryDialog(entry) },
                                    onCopy = { viewModel.openCopyEntryDialog(entry) },
                                    onDelete = { viewModel.openDeleteEntryDialog(entry) },
                                    onDetails = { viewModel.showEntryDetails(entry) },
                                    onFloat = { viewModel.openInFloatingWindow(entry) },
                                )
                            }
                        }
                    }
                }

                // PHOTOS Tab (Opened from Main Vault album or directly)
                uiState.viewTab == VaultViewTab.PHOTOS -> {
                    if (photoEntries.isEmpty()) {
                        EmptyVaultSection(
                            icon = "🖼️",
                            title = "No Photos or Videos in Vault",
                            description = "Tap below to import photos and videos from your gallery.",
                            actionLabel = "Import Photos & Videos",
                            onAction = { isMediaPickerOpen = true },
                        )
                    } else if (uiState.isGridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(photoEntries, key = { _, it -> it.id }) { index, entry ->
                                VaultPhotoGridItem(
                                    entry = entry,
                                    isSelected = uiState.selectedEntryIds.contains(entry.id),
                                    hasSelection = uiState.hasSelection,
                                    viewModel = viewModel,
                                    onClick = { viewModel.openViewer(photoEntries, index) },
                                    onToggleSelect = { viewModel.toggleSelectEntry(entry.id) },
                                    onMoveOutOfVault = { viewModel.moveOutOfVault(entry) },
                                    onMoreClick = { viewModel.showEntryDetails(entry) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(photoEntries, key = { "photo_list_${it.id}" }) { entry ->
                                EntryListItem(
                                    entry = entry,
                                    isSelected = uiState.selectedEntryIds.contains(entry.id),
                                    hasSelection = uiState.hasSelection,
                                    showFolderBadge = false,
                                    allFolders = allFolders,
                                    onClick = {
                                        if (uiState.hasSelection) {
                                            viewModel.toggleSelectEntry(entry.id)
                                        } else {
                                            viewModel.openPreview(entry)
                                        }
                                    },
                                    onToggleSelect = { viewModel.toggleSelectEntry(entry.id) },
                                    onMoveOutOfVault = { viewModel.moveOutOfVault(entry) },
                                    onRename = { viewModel.openRenameEntryDialog(entry) },
                                    onMove = { viewModel.openMoveEntryDialog(entry) },
                                    onCopy = { viewModel.openCopyEntryDialog(entry) },
                                    onDelete = { viewModel.openDeleteEntryDialog(entry) },
                                    onDetails = { viewModel.showEntryDetails(entry) },
                                    onFloat = { viewModel.openInFloatingWindow(entry) },
                                )
                            }
                        }
                    }
                }

                // AUDIO Tab -> Dedicated Audio Section
                uiState.viewTab == VaultViewTab.AUDIO -> {
                    if (audioEntries.isEmpty()) {
                        EmptyVaultSection(
                            icon = "🎵",
                            title = "No Audio Recordings in Vault",
                            description = "Private audio recordings, songs, and voice notes stay in this dedicated section.",
                            actionLabel = "Import Audio",
                            onAction = { importLauncher.launch(arrayOf("audio/*")) },
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item {
                                Text(
                                    text = "Audio Files (${audioEntries.size})",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            items(audioEntries, key = { it.id }) { audioEntry ->
                                VaultAudioItemCard(
                                    entry = audioEntry,
                                    onPlay = { viewModel.openPreview(audioEntry) },
                                    onRename = { viewModel.openRenameEntryDialog(audioEntry) },
                                    onMove = { viewModel.openMoveEntryDialog(audioEntry) },
                                    onCopy = { viewModel.openCopyEntryDialog(audioEntry) },
                                    onDetails = { viewModel.showEntryDetails(audioEntry) },
                                    onFloat = { viewModel.openInFloatingWindow(audioEntry) },
                                    onExport = {
                                        entryToExport = audioEntry
                                        exportLauncher.launch(audioEntry.originalFileName)
                                    },
                                    onDelete = { viewModel.openDeleteEntryDialog(audioEntry) },
                                    onMoveOutOfVault = { viewModel.moveOutOfVault(audioEntry) },
                                )
                            }
                        }
                    }
                }

                // ALBUMS Tab -> 1Gallery 3-Thumbnail Preview Layout (Default)
                else -> {
                    if (vaultAlbums.isEmpty()) {
                        EmptyVaultSection(
                            icon = "📁",
                            title = "No Albums Yet",
                            description = "Organize your private media into secure folders.",
                            actionLabel = "Create New Album",
                            onAction = { viewModel.openCreateFolderDialog() },
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            items(vaultAlbums, key = { it.folder.id }) { album ->
                                VaultAlbumPreviewRow(
                                    album = album,
                                    viewModel = viewModel,
                                    onAlbumClick = {
                                        if (album.folder.id != "__root__") {
                                            viewModel.navigateToFolder(album.folder.id)
                                        } else {
                                            viewModel.setViewTab(VaultViewTab.PHOTOS)
                                        }
                                    },
                                    onItemClick = { index ->
                                        if (album.folder.id == "__root__") {
                                            viewModel.openViewer(photoEntries, index)
                                        } else if (album.previewEntries.isNotEmpty()) {
                                            viewModel.openViewer(album.previewEntries, index)
                                        } else {
                                            viewModel.navigateToFolder(album.folder.id)
                                        }
                                    },
                                    onRename = { viewModel.openRenameFolderDialog(album.folder) },
                                    onMove = { viewModel.openMoveFolderDialog(album.folder) },
                                    onDelete = { viewModel.openDeleteFolderDialog(album.folder) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Fullscreen interactive media viewer with horizontal swiping between photos/videos
    if (uiState.activeViewerIndex != null && uiState.activeViewerItems.isNotEmpty()) {
        VaultFullscreenMediaViewer(
            items = uiState.activeViewerItems,
            initialIndex = uiState.activeViewerIndex!!,
            viewModel = viewModel,
            onDismiss = { viewModel.closeViewer() },
            onExportClick = { entry ->
                entryToExport = entry
                exportLauncher.launch(entry.originalFileName)
            },
            onDeleteClick = { entry ->
                viewModel.openDeleteEntryDialog(entry)
            },
            onDetailsClick = { entry ->
                viewModel.showEntryDetails(entry)
            },
            onMoveOutOfVaultClick = { entry ->
                viewModel.moveOutOfVault(entry)
            },
        )
    }

    // Single item fallback preview viewer (for documents and audio)
    uiState.activePreviewEntry?.let { entry ->
        VaultPreviewViewer(
            entry = entry,
            previewFile = uiState.previewFile,
            isLoading = uiState.isDecryptingPreview,
            errorMessage = uiState.previewError,
            onClose = { viewModel.closePreview() },
            onExportClick = {
                entryToExport = it
                exportLauncher.launch(it.originalFileName)
            },
            isTextDocument = viewModel.previewManager.isTextDocument(entry),
            isPdfDocument = viewModel.previewManager.isPdfDocument(entry),
            onFloatClick = { viewModel.openInFloatingWindow(entry) },
        )
    }

    // Dialogs
    if (uiState.isCreateFolderOpen) {
        CreateFolderDialog(
            onDismiss = { viewModel.closeCreateFolderDialog() },
            onConfirm = { name -> viewModel.createFolder(name) },
        )
    }

    uiState.folderToRename?.let { folder ->
        RenameItemDialog(
            initialName = folder.name,
            itemType = "Folder",
            onDismiss = { viewModel.closeRenameFolderDialog() },
            onConfirm = { viewModel.renameFolder(folder.id, it) },
        )
    }

    uiState.folderToMove?.let { folder ->
        MoveItemDialog(
            itemTitle = "Folder '${folder.name}'",
            allFolders = allFolders,
            currentFolderId = folder.parentId,
            onDismiss = { viewModel.closeMoveFolderDialog() },
            onConfirm = { viewModel.moveFolder(folder.id, it) },
        )
    }

    uiState.folderToDelete?.let { folder ->
        DeleteConfirmationDialog(
            title = "Delete Folder",
            message = "Permanently delete '${folder.name}' and its contents from the vault? This cannot be undone.",
            onDismiss = { viewModel.closeDeleteFolderDialog() },
            onConfirm = { viewModel.deleteFolder(folder.id) },
        )
    }

    uiState.entryToRename?.let { entry ->
        RenameItemDialog(
            initialName = entry.originalFileName,
            itemType = "File",
            onDismiss = { viewModel.closeRenameEntryDialog() },
            onConfirm = { viewModel.renameEntry(entry.id, it) },
        )
    }

    uiState.entryToMove?.let { entry ->
        MoveItemDialog(
            itemTitle = "File '${entry.originalFileName}'",
            allFolders = allFolders,
            currentFolderId = entry.folderId,
            onDismiss = { viewModel.closeMoveEntryDialog() },
            onConfirm = { viewModel.moveEntry(entry.id, it) },
        )
    }

    uiState.entryToCopy?.let { entry ->
        CopyEntryDialog(
            entry = entry,
            allFolders = allFolders,
            currentFolderId = entry.folderId,
            onDismiss = { viewModel.closeCopyEntryDialog() },
            onConfirm = { viewModel.copyEntry(entry.id, it) },
        )
    }

    uiState.entryToDelete?.let { entry ->
        DeleteConfirmationDialog(
            title = "Delete File",
            message = "Permanently delete '${entry.originalFileName}' from the vault? This cannot be undone.",
            onDismiss = { viewModel.closeDeleteEntryDialog() },
            onConfirm = { viewModel.deleteEntry(entry.id) },
        )
    }

    uiState.entryToShowDetails?.let { entry ->
        FileDetailsDialog(
            entry = entry,
            onDismiss = { viewModel.closeEntryDetails() },
            onExportClick = {
                viewModel.closeEntryDetails()
                entryToExport = entry
                exportLauncher.launch(entry.originalFileName)
            },
        )
    }

    // Storage Info Dialog
    if (uiState.isStorageDialogOpen) {
        StorageInfoDialog(
            totalFiles = uiState.totalFilesCount,
            totalVaultSizeBytes = uiState.totalVaultSizeBytes,
            availableStorageBytes = uiState.availableStorageBytes,
            onDismiss = { viewModel.closeStorageDialog() },
        )
    }

    // In-App Media Picker
    if (isMediaPickerOpen) {
        VaultMediaPicker(
            onDismiss = { isMediaPickerOpen = false },
            onImportSelected = { selectedItems ->
                viewModel.importMediaItems(selectedItems, context.contentResolver) { success, _ ->
                    if (success) {
                        isMediaPickerOpen = false
                    }
                }
            },
            onSystemPickerFallback = {
                isMediaPickerOpen = false
                importLauncher.launch(arrayOf("*/*"))
            },
            isImporting = uiState.isImporting,
            importProgressText = uiState.importProgressText,
        )
    }

    // Import Progress
    if (uiState.isImporting && !isMediaPickerOpen) {
        ImportProgressOverlay(progressText = uiState.importProgressText)
    }

    // Batch Dialogs
    if (showBatchMoveDialog) {
        MoveItemDialog(
            itemTitle = "${uiState.selectedEntryIds.size} items",
            allFolders = allFolders,
            currentFolderId = uiState.currentFolderId,
            onDismiss = { showBatchMoveDialog = false },
            onConfirm = { targetFolderId ->
                showBatchMoveDialog = false
                viewModel.moveSelectedEntries(targetFolderId)
            },
            isCopy = false,
        )
    }

    if (showBatchCopyDialog) {
        MoveItemDialog(
            itemTitle = "${uiState.selectedEntryIds.size} items",
            allFolders = allFolders,
            currentFolderId = uiState.currentFolderId,
            onDismiss = { showBatchCopyDialog = false },
            onConfirm = { targetFolderId ->
                showBatchCopyDialog = false
                viewModel.copySelectedEntries(targetFolderId)
            },
            isCopy = true,
        )
    }

    if (showBatchDeleteDialog) {
        DeleteConfirmationDialog(
            title = "Delete ${uiState.selectedEntryIds.size} Items",
            message = "Permanently delete ${uiState.selectedEntryIds.size} selected items from the vault? This cannot be undone.",
            onDismiss = { showBatchDeleteDialog = false },
            onConfirm = {
                showBatchDeleteDialog = false
                viewModel.deleteSelectedEntries()
            },
        )
    }

    if (showBatchMoveOutDialog) {
        MoveOutOfVaultConfirmationDialog(
            count = uiState.selectedEntryIds.size,
            onDismiss = { showBatchMoveOutDialog = false },
            onConfirm = {
                showBatchMoveOutDialog = false
                viewModel.moveSelectedOutOfVault()
            },
        )
    }
    }
}

/**
 * Top navigation tab matching 1Gallery design with underline indicator
 */
@Composable
private fun TopNavTab(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color.White else Color(0xFF8E8E93),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .height(2.5.dp)
                .width(36.dp)
                .background(
                    if (isSelected) Color.White else Color.Transparent,
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

/**
 * Grid cell for photos/videos in Vault with decrypted thumbnail, selection indicator, and duration badge
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultPhotoGridItem(
    entry: VaultEntry,
    isSelected: Boolean,
    hasSelection: Boolean,
    viewModel: VaultViewModel,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onMoveOutOfVault: () -> Unit,
    onMoreClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1C1C1E))
            .then(
                if (isSelected) {
                    Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = {
                    if (hasSelection) {
                        onToggleSelect()
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    onToggleSelect()
                },
            ),
    ) {
        VaultEntryThumbnail(
            entry = entry,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // Dim overlay when selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
        }

        // Selection Checkmark Badge
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        } else if (hasSelection) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(1.5.dp, Color.White, CircleShape),
            )
        }

        if (entry.category == VaultCategory.Videos) {
            VideoDurationBadge(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            )
        }

        if (!hasSelection) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { menuExpanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⋮", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = Color(0xFF1C1C1E),
                ) {
                    DropdownMenuItem(
                        text = { Text("Move out to Gallery 📤", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onMoveOutOfVault()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("🗗 Open in Floating Window", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            viewModel.openInFloatingWindow(entry)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("ℹ️ Details", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onMoreClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("📁 Move", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            viewModel.openMoveEntryDialog(entry)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("📋 Copy", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            viewModel.openCopyEntryDialog(entry)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Permanently", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            viewModel.openDeleteEntryDialog(entry)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Album section row matching 1Gallery reference screenshot:
 * - Album title & item count
 * - 3 large rounded preview thumbnails in a row
 * - Video duration badge
 * - "+N" count overlay on the 3rd thumbnail when remaining items exist
 */
@Composable
private fun VaultAlbumPreviewRow(
    album: VaultAlbumDisplay,
    viewModel: VaultViewModel,
    onAlbumClick: () -> Unit,
    onItemClick: (Int) -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAlbumClick),
    ) {
        // Album Title & Count Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = album.folder.name,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${album.totalCount} items",
                    color = Color(0xFF8E8E93),
                    fontSize = 12.sp,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Text("⋮", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = Color(0xFF1C1C1E),
                ) {
                    if (album.previewEntries.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("🗗 Open in Floating Windows", color = Color.White) },
                            onClick = {
                                menuExpanded = false
                                viewModel.openMultipleInFloatingWindow(album.previewEntries)
                            },
                        )
                    }
                    if (album.folder.id != "__root__") {
                        DropdownMenuItem(
                            text = { Text("Rename", color = Color.White) },
                            onClick = { menuExpanded = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move", color = Color.White) },
                            onClick = { menuExpanded = false; onMove() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Open Folder", color = Color.White) },
                            onClick = { menuExpanded = false; onAlbumClick() },
                        )
                    }
                }
            }
        }

        // Row of up to 3 thumbnails side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val previewItems = album.previewEntries
            val totalCount = album.totalCount

            if (previewItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable(onClick = onAlbumClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("📁", fontSize = 32.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
            } else {
                // Slot 1
                val item0 = previewItems.getOrNull(0)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable { if (item0 != null) onItemClick(0) else onAlbumClick() },
                ) {
                    if (item0 != null) {
                        VaultEntryThumbnail(
                            entry = item0,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item0.category == VaultCategory.Videos) {
                            VideoDurationBadge(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp),
                            )
                        }
                    }
                }

                // Slot 2
                val item1 = previewItems.getOrNull(1)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable { if (item1 != null) onItemClick(1) else onAlbumClick() },
                ) {
                    if (item1 != null) {
                        VaultEntryThumbnail(
                            entry = item1,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item1.category == VaultCategory.Videos) {
                            VideoDurationBadge(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp),
                            )
                        }
                    }
                }

                // Slot 3
                val item2 = previewItems.getOrNull(2)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable { if (item2 != null) onItemClick(2) else onAlbumClick() },
                ) {
                    if (item2 != null) {
                        VaultEntryThumbnail(
                            entry = item2,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item2.category == VaultCategory.Videos) {
                            VideoDurationBadge(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp),
                            )
                        }
                        if (totalCount > 3) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+${totalCount - 2}",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Audio Track Item Card with clean dark styling and quick playback
 */
@Composable
private fun VaultAudioItemCard(
    entry: VaultEntry,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDetails: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onFloat: () -> Unit = {},
    onMoveOutOfVault: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onPlay)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0xFF2C2C2E)),
            contentAlignment = Alignment.Center,
        ) {
            Text("🎵", fontSize = 22.sp)
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.originalFileName,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${formatFileSize(entry.originalSizeBytes)} • ${dateFormatter.format(Date(entry.dateModifiedMillis))}",
                color = Color(0xFF8E8E93),
                fontSize = 12.sp,
            )
        }

        IconButton(onClick = onPlay) {
            Text("▶", color = Color.White, fontSize = 18.sp)
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Text("⋮", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = Color(0xFF1C1C1E),
            ) {
                DropdownMenuItem(text = { Text("Move out to Gallery 📤", color = Color.White) }, onClick = { menuExpanded = false; onMoveOutOfVault() })
                DropdownMenuItem(text = { Text("Open in Floating Window 🗗", color = Color.White) }, onClick = { menuExpanded = false; onFloat() })
                DropdownMenuItem(text = { Text("Play / Preview 🎵", color = Color.White) }, onClick = { menuExpanded = false; onPlay() })
                DropdownMenuItem(text = { Text("Rename ✏️", color = Color.White) }, onClick = { menuExpanded = false; onRename() })
                DropdownMenuItem(text = { Text("Move 📁", color = Color.White) }, onClick = { menuExpanded = false; onMove() })
                DropdownMenuItem(text = { Text("Copy 📋", color = Color.White) }, onClick = { menuExpanded = false; onCopy() })
                DropdownMenuItem(text = { Text("Details ℹ️", color = Color.White) }, onClick = { menuExpanded = false; onDetails() })
                DropdownMenuItem(text = { Text("Export Decrypted ⬇️", color = Color.White) }, onClick = { menuExpanded = false; onExport() })
                DropdownMenuItem(text = { Text("Delete Permanently", color = MaterialTheme.colorScheme.error) }, onClick = { menuExpanded = false; onDelete() })
            }
        }
    }
}

/**
 * Reusable video duration / video badge
 */
@Composable
private fun VideoDurationBadge(
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.Black.copy(alpha = 0.65f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🎬", fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryListItem(
    entry: VaultEntry,
    showFolderBadge: Boolean,
    allFolders: List<VaultFolder>,
    isSelected: Boolean = false,
    hasSelection: Boolean = false,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onMoveOutOfVault: () -> Unit = {},
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onFloat: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF2C2C2E) else Color(0xFF1C1C1E),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = {
                    if (hasSelection) {
                        onToggleSelect()
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    onToggleSelect()
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasSelection) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f)
                        )
                        .then(
                            if (!isSelected) Modifier.border(1.5.dp, Color.White, CircleShape) else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Text(getCategoryIcon(entry.category), fontSize = 24.sp)
            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.originalFileName,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatFileSize(entry.originalSizeBytes)} • ${dateFormatter.format(Date(entry.dateModifiedMillis))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E8E93),
                    )
                    if (showFolderBadge) {
                        val folderName = allFolders.firstOrNull { it.id == entry.folderId }?.name ?: "Root"
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF2C2C2E),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        ) {
                            Text(
                                text = "📁 $folderName",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            if (!hasSelection) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Text("⋮", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = Color(0xFF1C1C1E),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Move out to Gallery 📤", color = Color.White) },
                            onClick = {
                                menuExpanded = false
                                onMoveOutOfVault()
                            },
                        )
                        DropdownMenuItem(text = { Text("🗗 Open in Floating Window", color = Color.White) }, onClick = { menuExpanded = false; onFloat() })
                        DropdownMenuItem(text = { Text("Preview", color = Color.White) }, onClick = { menuExpanded = false; onClick() })
                        DropdownMenuItem(text = { Text("Rename", color = Color.White) }, onClick = { menuExpanded = false; onRename() })
                        DropdownMenuItem(text = { Text("Move", color = Color.White) }, onClick = { menuExpanded = false; onMove() })
                        DropdownMenuItem(text = { Text("Copy", color = Color.White) }, onClick = { menuExpanded = false; onCopy() })
                        DropdownMenuItem(text = { Text("Details", color = Color.White) }, onClick = { menuExpanded = false; onDetails() })
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVaultSection(
    icon: String,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon, fontSize = 52.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF8E8E93),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(24.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
        ) {
            Text(actionLabel, fontWeight = FontWeight.SemiBold)
        }
    }
}

fun getCategoryIcon(category: VaultCategory): String {
    return when (category) {
        VaultCategory.Images -> "🖼️"
        VaultCategory.Videos -> "🎬"
        VaultCategory.Audio -> "🎵"
        VaultCategory.Documents -> "📄"
        VaultCategory.Other -> "📦"
        VaultCategory.All -> "📁"
    }
}
