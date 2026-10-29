package com.vaultsync.core.floating

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.view.LayoutInflater
import androidx.compose.ui.draw.clipToBounds
import com.vaultsync.R
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Modern vector-rendered Picture-in-Picture / Floating Window icon that displays
 * consistently and crisply across all Android versions.
 */
@Composable
fun FloatingWindowIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Canvas(modifier = modifier.size(19.dp)) {
        val w = size.width
        val h = size.height

        // Outer larger window frame (top-left)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.08f),
            size = Size(w * 0.62f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Stroke(width = w * 0.11f),
        )

        // Inner overlapping floating window (bottom-right)
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(w * 0.38f, h * 0.38f),
            size = Size(w * 0.54f, h * 0.54f),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Fill,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.38f, h * 0.38f),
            size = Size(w * 0.54f, h * 0.54f),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Stroke(width = w * 0.11f),
        )
    }
}

/**
 * Modern vector-rendered rotate icon (circular arrow) that displays
 * consistently and crisply across all Android versions.
 */
@Composable
fun RotateMediaIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Canvas(modifier = modifier.size(17.dp)) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.12f
        val r = w * 0.35f
        val cx = w * 0.5f
        val cy = h * 0.5f

        // Circular arc sweeping clockwise
        drawArc(
            color = tint,
            startAngle = 80f,
            sweepAngle = 275f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )

        // Arrowhead pointing downwards at the end of the clockwise arc
        val arrowPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx + r, cy + h * 0.16f)
            lineTo(cx + r - w * 0.18f, cy - h * 0.04f)
            lineTo(cx + r + w * 0.18f, cy - h * 0.04f)
            close()
        }
        drawPath(path = arrowPath, color = tint)
    }
}

/**
 * Overlay composable placed at the root of the app that renders all active floating windows.
 * Non-interfering: touches outside of floating windows pass directly to the app underneath.
 */
@Composable
fun FloatingWindowOverlay(
    manager: FloatingWindowManager,
    modifier: Modifier = Modifier,
) {
    val windows by manager.windows.collectAsState()
    if (windows.isEmpty()) return

    val sortedWindows = remember(windows) {
        windows.sortedBy { it.zIndex }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Render each floating window
        sortedWindows.forEach { item ->
            FloatingWindowItemView(
                item = item,
                manager = manager,
            )
        }

        // Global Mini Control Pill when floating windows are present
        if (windows.size > 1) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 24.dp)
                    .shadow(8.dp, RoundedCornerShape(20.dp)),
                color = Color(0xFF1C1C1E).copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FloatingWindowIcon(modifier = Modifier.size(15.dp))
                    Text(
                        text = "${windows.size} Floating",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2C2C2E))
                            .clickable { manager.closeAll() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "Close All",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingWindowItemView(
    item: FloatingMediaItem,
    manager: FloatingWindowManager,
) {
    val density = LocalDensity.current

    if (item.isMinimized) {
        // Minimized floating bubble
        Box(
            modifier = Modifier
                .offset { IntOffset(item.x.roundToInt(), item.y.roundToInt()) }
                .pointerInput(item.id) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        manager.updatePosition(item.id, dragAmount.x, dragAmount.y)
                    }
                }
                .clickable {
                    manager.bringToFront(item.id)
                    manager.toggleMinimize(item.id)
                }
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF1E1E1E))
                .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                .size(54.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (item.isVideo) "🎬" else "🖼️",
                fontSize = 24.sp,
            )
            if (item.source == FloatingMediaSource.VAULT) {
                Text(
                    text = "🔒",
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                )
            }
        }
        return
    }

    val widthDp = with(density) { item.width.toDp() }
    val heightDp = with(density) { item.height.toDp() }

    Surface(
        modifier = Modifier
            .offset { IntOffset(item.x.roundToInt(), item.y.roundToInt()) }
            .size(widthDp, heightDp)
            .shadow(16.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF121212))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable { manager.bringToFront(item.id) },
        color = Color(0xFF121212),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Title / Header Drag Handle Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(Color(0xFF1C1C1E))
                    .pointerInput(item.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            manager.updatePosition(item.id, dragAmount.x, dragAmount.y)
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (item.isVideo) "🎬" else "🖼️",
                    fontSize = 14.sp,
                )
                if (item.source == FloatingMediaSource.VAULT) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("🔒", fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // Rotate Button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { manager.rotateMedia(item.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    RotateMediaIcon()
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Minimize Button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { manager.toggleMinimize(item.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(11.dp)
                            .height(2.dp)
                            .background(Color.White, RoundedCornerShape(1.dp))
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Close Button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { manager.closeWindow(item.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Window Content (Image or Video)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
            ) {
                if (item.isVideo) {
                    FloatingVideoContent(item = item, manager = manager)
                } else {
                    FloatingImageContent(item = item)
                }

                // Bottom-Right Corner Resize Grip Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .pointerInput(item.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                manager.updateSize(item.id, dragAmount.x, dragAmount.y)
                            }
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Text(
                        text = "↘",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingImageContent(item: FloatingMediaItem) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { _, zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        if (scale > 1f) {
            offset += offsetChange
        } else {
            offset = Offset.Zero
        }
    }

    val isTransposed = (item.rotationDegrees % 180f != 0f)
    val fitScale = if (isTransposed && item.width > 0f && item.height > 0f) {
        minOf(item.height / item.width, item.width / item.height)
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .transformable(state = transformState)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val model = item.uri ?: item.file
        AsyncImage(
            model = model,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    rotationZ = item.rotationDegrees,
                    scaleX = scale * fitScale,
                    scaleY = scale * fitScale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

@Composable
private fun FloatingVideoContent(
    item: FloatingMediaItem,
    manager: FloatingWindowManager,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { _, zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 4f)
        scale = newScale
        if (newScale > 1f) {
            val maxOffsetX = ((newScale - 1f) * item.width * 0.5f).coerceAtLeast(0f)
            val maxOffsetY = ((newScale - 1f) * item.height * 0.5f).coerceAtLeast(0f)
            val newX = (offset.x + offsetChange.x).coerceIn(-maxOffsetX, maxOffsetX)
            val newY = (offset.y + offsetChange.y).coerceIn(-maxOffsetY, maxOffsetY)
            offset = Offset(newX, newY)
        } else {
            offset = Offset.Zero
        }
    }

    LaunchedEffect(item.rotationDegrees) {
        offset = Offset.Zero
    }

    val mediaUri = remember(item.uri, item.file) {
        item.uri ?: item.file?.let { Uri.fromFile(it) }
    }

    val exoPlayer = remember(mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            mediaUri?.let { setMediaItem(MediaItem.fromUri(it)) }
            prepare()
            volume = if (item.isMuted) 0f else 1f
            playWhenReady = true
        }
    }

    LaunchedEffect(item.isMuted) {
        exoPlayer.volume = if (item.isMuted) 0f else 1f
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // Auto-hide controls after 3 seconds of inactivity
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    val isTransposed = (item.rotationDegrees % 180f != 0f)
    val fitScale = if (isTransposed && item.width > 0f && item.height > 0f) {
        minOf(item.height / item.width, item.width / item.height)
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        // Video Player View: Backed by TextureView to support rotation and scaling transformations
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    rotationZ = item.rotationDegrees,
                    scaleX = scale * fitScale,
                    scaleY = scale * fitScale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
            factory = { viewContext ->
                val playerView = LayoutInflater.from(viewContext)
                    .inflate(R.layout.floating_player_view, null, false) as PlayerView
                playerView.apply {
                    this.player = exoPlayer
                    useController = false
                    isClickable = false
                    isFocusable = false
                    setOnTouchListener { _, _ -> false }
                }
            },
            update = { playerView ->
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
        )

        // Gesture handling layer for pinch-to-zoom, pan, double-tap zoom, and single-tap to toggle controls
        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformState)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = {
                            scale = if (scale > 1.05f) 1f else 2.5f
                            offset = Offset.Zero
                        }
                    )
                }
        )

        // Overlay controls on tap
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { showControls = false },
                contentAlignment = Alignment.Center,
            ) {
                // Play / Pause Toggle Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isPlaying) "⏸" else "▶",
                        color = Color.White,
                        fontSize = 20.sp,
                    )
                }
            }
        }

        // Bottom Controls Row: Mute & Rotate (always accessible)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mute / Unmute Button
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { manager.toggleMute(item.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (item.isMuted) "🔇" else "🔊",
                    fontSize = 14.sp,
                )
            }

            // Quick Rotate Button for Video
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { manager.rotateMedia(item.id) },
                contentAlignment = Alignment.Center,
            ) {
                RotateMediaIcon(modifier = Modifier.size(15.dp))
            }
        }
    }
}
