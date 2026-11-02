package com.vaultsync.feature.vault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.vaultsync.core.media.AndroidMediaRepository
import com.vaultsync.core.media.DeviceMediaItem
import com.vaultsync.core.media.MediaCategory
import com.vaultsync.core.media.MediaFolder
import com.vaultsync.core.media.foldersForCategory
import com.vaultsync.core.media.foldersForGallery
import com.vaultsync.core.media.mediaPermissionStatusFromGrantResults
import com.vaultsync.core.media.requiredMediaPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class VaultPickerCategory(val label: String) {
    ALL("All"),
    PHOTOS_VIDEOS("Photos & Videos"),
    AUDIO("Audio"),
}

@Composable
fun VaultMediaPicker(
    onDismiss: () -> Unit,
    onImportSelected: (List<DeviceMediaItem>) -> Unit,
    onSystemPickerFallback: () -> Unit,
    isImporting: Boolean = false,
    importProgressText: String? = null,
    initialCategory: MediaCategory? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requiredPermissions = remember { requiredMediaPermissions() }

    var hasPermission by remember {
        mutableStateOf(
            requiredPermissions.any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasPermission = grants.values.any { it }
    }

    val initialTab = when (initialCategory) {
        MediaCategory.Audio -> VaultPickerCategory.AUDIO
        MediaCategory.Images, MediaCategory.Videos -> VaultPickerCategory.PHOTOS_VIDEOS
        else -> VaultPickerCategory.ALL
    }
    var selectedCategoryTab by remember { mutableStateOf(initialTab) }

    var rawMediaItems by remember { mutableStateOf<List<DeviceMediaItem>>(emptyList()) }
    var selectedFolderKey by remember { mutableStateOf<String?>(null) } // null = All Media
    val selectedItems = remember { mutableStateOf<Set<DeviceMediaItem>>(emptySet()) }
    var isLoadingMedia by remember { mutableStateOf(false) }

    fun loadMedia() {
        if (!hasPermission) return
        isLoadingMedia = true
        scope.launch {
            val repo = AndroidMediaRepository(context)
            val items = withContext(Dispatchers.IO) {
                repo.loadMediaStoreItems()
            }
            rawMediaItems = items
            isLoadingMedia = false
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            loadMedia()
        }
    }

    // Filter items based on selected category tab
    val categoryFilteredItems = remember(rawMediaItems, selectedCategoryTab) {
        when (selectedCategoryTab) {
            VaultPickerCategory.ALL -> rawMediaItems
            VaultPickerCategory.PHOTOS_VIDEOS -> rawMediaItems.filter {
                it.category == MediaCategory.Images || it.category == MediaCategory.Videos
            }
            VaultPickerCategory.AUDIO -> rawMediaItems.filter {
                it.category == MediaCategory.Audio
            }
        }
    }

    val albums = remember(categoryFilteredItems, selectedCategoryTab) {
        when (selectedCategoryTab) {
            VaultPickerCategory.AUDIO -> foldersForCategory(categoryFilteredItems, MediaCategory.Audio)
            VaultPickerCategory.PHOTOS_VIDEOS -> foldersForGallery(categoryFilteredItems)
            VaultPickerCategory.ALL -> {
                val galleryFolders = foldersForGallery(categoryFilteredItems)
                val audioFolders = foldersForCategory(categoryFilteredItems, MediaCategory.Audio)
                galleryFolders + audioFolders
            }
        }
    }

    val displayedItems = remember(categoryFilteredItems, selectedFolderKey) {
        if (selectedFolderKey == null) {
            categoryFilteredItems
        } else {
            categoryFilteredItems.filter { it.folderKey == selectedFolderKey }
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isImporting) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top App Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { if (!isImporting) onDismiss() },
                        enabled = !isImporting,
                    ) {
                        Text("✕", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Add to Vault",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val folderTitle = if (selectedFolderKey == null) "All Media"
                        else albums.firstOrNull { it.key == selectedFolderKey }?.name ?: "Album"
                        Text(
                            text = "$folderTitle • ${displayedItems.size} items",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                        )
                    }

                    // System File Picker Action
                    IconButton(
                        onClick = {
                            onDismiss()
                            onSystemPickerFallback()
                        },
                        enabled = !isImporting,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = "System File Picker",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    // Select All / Deselect All
                    if (displayedItems.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                if (selectedItems.value.size >= displayedItems.size) {
                                    selectedItems.value = emptySet()
                                } else {
                                    selectedItems.value = displayedItems.toSet()
                                }
                            },
                            enabled = !isImporting,
                        ) {
                            Text(
                                text = if (selectedItems.value.size >= displayedItems.size) "Clear" else "Select All",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }

                if (!hasPermission) {
                    // Permission Request State
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2C2C2E)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Media Access Required",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "To select and encrypt photos and videos into your secure vault, VaultSync needs permission to read media on this device.",
                                color = Color(0xFF8E8E93),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                shape = RoundedCornerShape(24.dp),
                            ) {
                                Text("Grant Permission", fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
                                    onSystemPickerFallback()
                                },
                                shape = RoundedCornerShape(24.dp),
                            ) {
                                Text("Browse Files via System Picker", color = Color.White)
                            }
                        }
                    }
                } else {
                    // 1. Category Switcher (All | Photos & Videos | Audio)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VaultPickerCategory.values().forEach { tab ->
                            val isSelected = selectedCategoryTab == tab
                            val count = when (tab) {
                                VaultPickerCategory.ALL -> rawMediaItems.size
                                VaultPickerCategory.PHOTOS_VIDEOS -> rawMediaItems.count { it.category == MediaCategory.Images || it.category == MediaCategory.Videos }
                                VaultPickerCategory.AUDIO -> rawMediaItems.count { it.category == MediaCategory.Audio }
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedCategoryTab = tab
                                    selectedFolderKey = null
                                },
                                label = { Text("${tab.label} ($count)") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.White,
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color(0xFF1C1C1E),
                                    labelColor = Color.White,
                                ),
                                shape = RoundedCornerShape(16.dp),
                            )
                        }
                    }

                    // 2. Album / Folder Filter Strip for the current category
                    if (albums.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = selectedFolderKey == null,
                                onClick = { selectedFolderKey = null },
                                label = { Text("All Folders (${categoryFilteredItems.size})") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF3A3A3C),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color(0xFF141416),
                                    labelColor = Color(0xFF8E8E93),
                                ),
                                shape = RoundedCornerShape(16.dp),
                            )

                            albums.forEach { album ->
                                FilterChip(
                                    selected = selectedFolderKey == album.key,
                                    onClick = { selectedFolderKey = album.key },
                                    label = { Text("${album.name} (${album.itemCount})") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF3A3A3C),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF141416),
                                        labelColor = Color(0xFF8E8E93),
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                )
                            }
                        }
                    }

                    // Content Grid
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        when {
                            isLoadingMedia -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            }
                            displayedItems.isEmpty() -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF2C2C2E)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = if (selectedCategoryTab == VaultPickerCategory.AUDIO) Icons.Rounded.Audiotrack else Icons.Rounded.PhotoLibrary,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier.size(32.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            if (selectedCategoryTab == VaultPickerCategory.AUDIO) "No audio files found"
                                            else "No media found in this album",
                                            color = Color(0xFF8E8E93),
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                            else -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 105.dp),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    items(displayedItems, key = { "picker_${it.id}_${it.category.name}" }) { item ->
                                        val isSelected = item in selectedItems.value

                                        Box(
                                            modifier = Modifier
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1C1C1E))
                                                .clickable {
                                                    val next = selectedItems.value.toMutableSet()
                                                    if (!next.add(item)) {
                                                        next.remove(item)
                                                    }
                                                    selectedItems.value = next
                                                },
                                        ) {
                                            if (item.category == MediaCategory.Audio) {
                                                // Dedicated Audio Card Tile
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(8.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center,
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(38.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(0xFF2C2C2E)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Audiotrack,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = item.displayName,
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        textAlign = TextAlign.Center,
                                                    )
                                                    if (item.durationMillis > 0) {
                                                        Text(
                                                            text = formatDuration(item.durationMillis),
                                                            color = Color(0xFF8E8E93),
                                                            fontSize = 10.sp,
                                                            modifier = Modifier.padding(top = 2.dp),
                                                        )
                                                    }
                                                }
                                            } else {
                                                AsyncImage(
                                                    model = item.uri,
                                                    contentDescription = item.displayName,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop,
                                                )
                                            }

                                            // Dim overlay if selected
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.White.copy(alpha = 0.25f))
                                                        .border(2.dp, Color.White, RoundedCornerShape(8.dp)),
                                                )
                                            }

                                            // Video duration badge
                                            if (item.category == MediaCategory.Videos) {
                                                Row(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomStart)
                                                        .padding(6.dp)
                                                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                                                        .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.PlayArrow,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(11.dp),
                                                    )
                                                    Text(
                                                        text = formatDuration(item.durationMillis),
                                                        color = Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                }
                                            }

                                            // Selection indicator
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(6.dp)
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isSelected) Color.White else Color.Black.copy(alpha = 0.5f))
                                                    .border(1.5.dp, Color.White, CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = "Selected",
                                                        tint = Color.Black,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Progress Overlay during Import
                        if (isImporting) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(32.dp),
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = importProgressText ?: "Encrypting and storing in secure vault...",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Action Bar
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Black,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            val count = selectedItems.value.size
                            val totalBytes = selectedItems.value.sumOf { it.sizeBytes }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (count == 0) "No items selected" else "$count item(s) selected",
                                    color = if (count > 0) Color.White else Color(0xFF8E8E93),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (count > 0 && totalBytes > 0) {
                                    Text(
                                        text = formatPickerFileSize(totalBytes),
                                        color = Color(0xFF8E8E93),
                                        fontSize = 13.sp,
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (selectedItems.value.isNotEmpty()) {
                                        onImportSelected(selectedItems.value.toList())
                                    }
                                },
                                enabled = count > 0 && !isImporting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black,
                                    disabledContainerColor = Color(0xFF2C2C2E),
                                    disabledContentColor = Color(0xFF8E8E93),
                                ),
                                shape = RoundedCornerShape(25.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = if (count > 0) "Add to Vault ($count)" else "Add to Vault",
                                        fontSize = 16.sp,
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

private fun formatPickerFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

