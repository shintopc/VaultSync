package com.vaultsync.feature.vault

import android.net.Uri
import com.vaultsync.core.media.EnhancedVideoPlayer
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.vaultsync.core.floating.FloatingWindowIcon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.vaultsync.core.model.VaultCategory
import com.vaultsync.core.model.VaultEntry
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun VaultPreviewViewer(
    entry: VaultEntry,
    previewFile: File?,
    isLoading: Boolean,
    errorMessage: String?,
    onClose: () -> Unit,
    onExportClick: (VaultEntry) -> Unit,
    isTextDocument: Boolean,
    isPdfDocument: Boolean = false,
    onFloatClick: (() -> Unit)? = null,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.98f)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.originalFileName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        text = "${entry.category.name} • ${formatFileSize(entry.originalSizeBytes)}",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onFloatClick != null) {
                        IconButton(onClick = {
                            onFloatClick()
                            android.widget.Toast.makeText(context, "Opened in floating window", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            FloatingWindowIcon()
                        }
                    }
                    IconButton(onClick = { onExportClick(entry) }) {
                        Icon(Icons.Default.Download, contentDescription = "Export", tint = Color.White)
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }

                // Content View
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isLoading -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Decrypting in secure memory...",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        errorMessage != null -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier.padding(24.dp),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(36.dp),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Integrity Verification Failed",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = errorMessage,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }

                        previewFile != null -> {
                            when (entry.category) {
                                VaultCategory.Images -> {
                                    ProtectedImageViewer(previewFile = previewFile)
                                }
                                VaultCategory.Videos -> {
                                    var isSingleControlsVisible by remember { mutableStateOf(true) }
                                    ProtectedVideoPlayer(
                                        previewFile = previewFile,
                                        showControls = isSingleControlsVisible,
                                        onToggleControls = { isSingleControlsVisible = !isSingleControlsVisible },
                                    )
                                }
                                VaultCategory.Audio -> {
                                    ProtectedAudioPlayer(previewFile = previewFile, fileName = entry.originalFileName)
                                }
                                VaultCategory.Documents -> {
                                    if (isTextDocument) {
                                        ProtectedTextViewer(previewFile = previewFile)
                                    } else if (isPdfDocument) {
                                        ProtectedPdfViewer(
                                            previewFile = previewFile,
                                            onFallback = {
                                                UnsupportedFormatCard(entry = entry, onExport = { onExportClick(entry) })
                                            },
                                        )
                                    } else {
                                        UnsupportedFormatCard(entry = entry, onExport = { onExportClick(entry) })
                                    }
                                }
                                else -> {
                                    UnsupportedFormatCard(entry = entry, onExport = { onExportClick(entry) })
                                }
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

@Composable
fun ProtectedImageViewer(
    previewFile: File,
    onToggleControls: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
) {
    var scale by remember(previewFile.path) { mutableFloatStateOf(1f) }
    var offset by remember(previewFile.path) { mutableStateOf(Offset.Zero) }

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
            model = previewFile,
            contentDescription = "Decrypted Image Preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.scaleX = scale
                    this.scaleY = scale
                    this.translationX = offset.x
                    this.translationY = offset.y
                },
        )

        // Gesture Detection Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(previewFile.path, containerWidth, containerHeight) {
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

                                // Consume all pointer events so parent HorizontalPager does NOT swipe!
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

@OptIn(UnstableApi::class)
@Composable
fun ProtectedVideoPlayer(
    previewFile: File,
    isPlayingActive: Boolean = true,
    showControls: Boolean = true,
    onToggleControls: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
) {
    EnhancedVideoPlayer(
        mediaUri = Uri.fromFile(previewFile),
        isPlayingActive = isPlayingActive,
        showControls = showControls,
        bottomPadding = 20.dp,
        onToggleControls = onToggleControls,
        onZoomChanged = onZoomChanged,
    )
}

@Composable
fun ProtectedAudioPlayer(previewFile: File, fileName: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }

    val exoPlayer = remember(previewFile) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(previewFile))
            setMediaItem(mediaItem)
            prepare()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        totalDuration = duration.coerceAtLeast(0L)
                    }
                }
            })
            playWhenReady = true
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            totalDuration = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("🎵", fontSize = 36.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Seekbar
            val maxPos = if (totalDuration > 0) totalDuration.toFloat() else 1f
            Slider(
                value = currentPosition.toFloat().coerceIn(0f, maxPos),
                onValueChange = { pos ->
                    currentPosition = pos.toLong()
                    exoPlayer.seekTo(currentPosition)
                },
                valueRange = 0f..maxPos,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(currentPosition), style = MaterialTheme.typography.bodySmall)
                Text(formatTime(totalDuration), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Play / Pause button
            IconButton(
                onClick = {
                    if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    text = if (isPlaying) "⏸" else "▶",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 24.sp,
                )
            }
        }
    }
}

@Composable
fun ProtectedTextViewer(previewFile: File) {
    val textContent = remember(previewFile) {
        try {
            previewFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            "Unable to read text encoding: ${e.message}"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        val scrollState = rememberScrollState()
        Text(
            text = textContent,
            color = Color(0xFFD4D4D4),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.verticalScroll(scrollState),
        )
    }
}

@Composable
fun ProtectedPdfViewer(
    previewFile: File,
    onFallback: @Composable () -> Unit,
) {
    var currentPage by remember { mutableStateOf(0) }
    var totalPages by remember { mutableStateOf(1) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(previewFile, currentPage) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = ParcelFileDescriptor.open(previewFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                totalPages = renderer.pageCount.coerceAtLeast(1)
                val validPage = currentPage.coerceIn(0, totalPages - 1)
                val page = renderer.openPage(validPage)
                val bmp = Bitmap.createBitmap(
                    (page.width * 1.5f).toInt(),
                    (page.height * 1.5f).toInt(),
                    Bitmap.Config.ARGB_8888,
                )
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                pfd.close()
                pageBitmap = bmp
            } catch (e: Exception) {
                renderError = e.message
            }
        }
    }

    if (renderError != null) {
        onFallback()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                pageBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "PDF Page ${currentPage + 1}",
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                    )
                } ?: CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconButton(
                    onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0,
                ) {
                    Text("◀", color = Color.White, fontSize = 20.sp)
                }

                Text(
                    text = "Page ${currentPage + 1} of $totalPages",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )

                IconButton(
                    onClick = { if (currentPage < totalPages - 1) currentPage++ },
                    enabled = currentPage < totalPages - 1,
                ) {
                    Text("▶", color = Color.White, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
fun UnsupportedFormatCard(entry: VaultEntry, onExport: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📄", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = entry.originalFileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "In-app preview is not supported for this file type (${entry.mimeType ?: "binary"}).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onExport,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Export Decrypted File")
            }
        }
    }
}

fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Renders thumbnail for an encrypted vault entry with reactive loading from VaultThumbnailManager
 */
@Composable
fun VaultEntryThumbnail(
    entry: VaultEntry,
    viewModel: VaultViewModel,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val thumbnails by viewModel.thumbnailCache.collectAsState()
    val thumbFile = thumbnails[entry.id]

    LaunchedEffect(entry.id) {
        viewModel.loadThumbnail(entry)
    }

    if (thumbFile != null && thumbFile.exists()) {
        AsyncImage(
            model = thumbFile,
            contentDescription = entry.originalFileName,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFF1C1C1E)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = getCategoryIcon(entry.category),
                fontSize = 24.sp,
            )
        }
    }
}

/**
 * Fullscreen Interactive Media Viewer with horizontal swipe, zoom/pan, video playback,
 * and decrypted caching across adjacent pages.
 */
@Composable
fun VaultFullscreenMediaViewer(
    items: List<VaultEntry>,
    initialIndex: Int,
    viewModel: VaultViewModel,
    onDismiss: () -> Unit,
    onExportClick: (VaultEntry) -> Unit,
    onMoveOutOfVaultClick: (VaultEntry) -> Unit = {},
    onDeleteClick: (VaultEntry) -> Unit,
    onDetailsClick: (VaultEntry) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val clampedIndex = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(
        initialPage = clampedIndex,
        pageCount = { items.size },
    )
    var showControls by remember { mutableStateOf(true) }
    var isCurrentZoomed by remember { mutableStateOf(false) }

    val currentItem = items.getOrNull(pagerState.currentPage) ?: items.firstOrNull()

    // Decrypted files cache for adjacent items in memory
    var decryptedFiles by remember { mutableStateOf<Map<String, File>>(emptyMap()) }
    var decryptingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var decryptError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pagerState.currentPage, items) {
        val currentPage = pagerState.currentPage
        val targetIndices = listOf(currentPage, currentPage + 1, currentPage - 1).filter { it in items.indices }

        for (idx in targetIndices) {
            val entry = items[idx]
            if (!decryptedFiles.containsKey(entry.id) && !decryptingIds.contains(entry.id)) {
                decryptingIds = decryptingIds + entry.id
                try {
                    val file = viewModel.decryptEntryForPreview(entry)
                    decryptedFiles = decryptedFiles + (entry.id to file)
                } catch (e: Exception) {
                    if (idx == currentPage) {
                        decryptError = e.message ?: "Failed to decrypt preview"
                    }
                } finally {
                    decryptingIds = decryptingIds - entry.id
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            decryptedFiles.values.forEach { file ->
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }

    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (currentItem != null) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !isCurrentZoomed,
                        key = { page -> items.getOrNull(page)?.id ?: page.toString() },
                    ) { page ->
                        val entry = items[page]
                        val file = decryptedFiles[entry.id]

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (file != null) {
                                when (entry.category) {
                                    VaultCategory.Images -> {
                                        ProtectedImageViewer(
                                            previewFile = file,
                                            onToggleControls = { showControls = !showControls },
                                            onZoomChanged = { zoomed ->
                                                if (pagerState.currentPage == page) {
                                                    isCurrentZoomed = zoomed
                                                }
                                            },
                                        )
                                    }
                                    VaultCategory.Videos -> {
                                        ProtectedVideoPlayer(
                                            previewFile = file,
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
                                    VaultCategory.Audio -> {
                                        ProtectedAudioPlayer(
                                            previewFile = file,
                                            fileName = entry.originalFileName,
                                        )
                                    }
                                    else -> {
                                        UnsupportedFormatCard(entry = entry, onExport = { onExportClick(entry) })
                                    }
                                }
                            } else {
                                // While decrypting in background: display smooth thumbnail or error
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    VaultEntryThumbnail(
                                        entry = entry,
                                        viewModel = viewModel,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                    )
                                    if (decryptError != null && pagerState.currentPage == page) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                            modifier = Modifier.padding(24.dp),
                                            shape = RoundedCornerShape(16.dp),
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(20.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                Text("⚠️", fontSize = 36.sp)
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = "Decryption Failed",
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = decryptError ?: "File not found or cannot be decrypted",
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    textAlign = TextAlign.Center,
                                                )
                                            }
                                        }
                                    } else {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            modifier = Modifier.size(36.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Top Controls Overlay
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
                                Text("←", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentItem.originalFileName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${pagerState.currentPage + 1} / ${items.size} • ${formatFileSize(currentItem.originalSizeBytes)}",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 12.sp,
                                )
                            }
                            IconButton(onClick = {
                                viewModel.openInFloatingWindow(currentItem)
                                android.widget.Toast.makeText(context, "Opened in floating window", android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                FloatingWindowIcon()
                            }
                            IconButton(onClick = { onDetailsClick(currentItem) }) {
                                Text("ℹ️", fontSize = 18.sp)
                            }
                            IconButton(onClick = { onExportClick(currentItem) }) {
                                Text("⬇️", fontSize = 18.sp)
                            }
                            IconButton(onClick = { onMoveOutOfVaultClick(currentItem) }) {
                                Text("📤", fontSize = 18.sp)
                            }
                            IconButton(onClick = { onDeleteClick(currentItem) }) {
                                Text("🗑️", fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }

