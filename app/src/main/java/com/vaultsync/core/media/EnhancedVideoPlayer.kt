package com.vaultsync.core.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vaultsync.R
import com.vaultsync.core.floating.RotateMediaIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

private val AVAILABLE_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Full-featured Normal Video Player supporting:
 * - Pinch-to-zoom (up to 5x) with smooth continuous zooming & bounded pan
 * - Repeat / Loop Playback toggle (loops the same video continuously)
 * - 90-degree manual video rotation (0°, 90°, 180°, 270°) with auto-aspect fitting
 * - Video playback speed adjustment (0.25x to 2.0x)
 * - Aspect ratio mode toggle (Fit vs Crop/Fill)
 * - Double-tap to seek (-10s / +10s) and double-tap center to zoom
 * - Audio mute / volume control
 * - High-precision scrubbing bar
 */
@OptIn(UnstableApi::class)
@Composable
fun EnhancedVideoPlayer(
    mediaUri: Uri,
    modifier: Modifier = Modifier,
    isPlayingActive: Boolean = true,
    showControls: Boolean = true,
    bottomPadding: Dp = 16.dp,
    onToggleControls: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val coroutineScope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }

    // Transformation & Zoom States
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }

    // Speed & Audio States
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }

    // Repeat / Loop Playback State
    var isRepeatEnabled by remember { mutableStateOf(false) }

    // Aspect Ratio Fit Mode
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Quick-tap feedback badges
    var showRewindBadge by remember { mutableStateOf(false) }
    var showForwardBadge by remember { mutableStateOf(false) }
    var modeFeedbackText by remember { mutableStateOf<String?>(null) }

    // Tap tracking for single vs double tap
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    val exoPlayer = remember(mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUri))
            prepare()
            playWhenReady = isPlayingActive
            repeatMode = if (isRepeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            setPlaybackSpeed(playbackSpeed)
            volume = if (isMuted) 0f else 1f
        }
    }

    LaunchedEffect(isPlayingActive) {
        if (!isPlayingActive) {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(playbackSpeed) {
        exoPlayer.setPlaybackSpeed(playbackSpeed)
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    LaunchedEffect(isRepeatEnabled) {
        exoPlayer.repeatMode = if (isRepeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                } else if (state == Player.STATE_ENDED) {
                    if (isRepeatEnabled) {
                        exoPlayer.seekTo(0L)
                        exoPlayer.play()
                    }
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Periodic time tracker
    LaunchedEffect(isPlaying, isScrubbing) {
        while (isPlaying && !isScrubbing) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(250)
        }
    }

    // Auto-hide controls after 3.5 seconds of inactivity when playing
    LaunchedEffect(showControls, isPlaying, isScrubbing, showSpeedDialog) {
        if (showControls && isPlaying && !isScrubbing && !showSpeedDialog) {
            delay(3500)
            onToggleControls()
        }
    }

    // Clear feedback badges
    LaunchedEffect(showRewindBadge) {
        if (showRewindBadge) {
            delay(650)
            showRewindBadge = false
        }
    }
    LaunchedEffect(showForwardBadge) {
        if (showForwardBadge) {
            delay(650)
            showForwardBadge = false
        }
    }
    LaunchedEffect(modeFeedbackText) {
        if (modeFeedbackText != null) {
            delay(1500)
            modeFeedbackText = null
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        val isLandscape = containerWidth > containerHeight
        var lastIsLandscape by remember { mutableStateOf(isLandscape) }
        LaunchedEffect(isLandscape) {
            if (isLandscape != lastIsLandscape) {
                lastIsLandscape = isLandscape
                rotationDegrees = 0f
                scale = 1f
                offset = Offset.Zero
                onZoomChanged(false)
            }
        }

        val isTransposed = (rotationDegrees % 180f != 0f)

        // When transposed (rotated 90° or 270°), the width and height of the view are swapped
        // so that the rotated view matches the container bounds with scale 1.0f,
        // avoiding any reduction in video size.
        val viewWidthPx = if (isTransposed && containerHeight > 0f) containerHeight else containerWidth
        val viewHeightPx = if (isTransposed && containerWidth > 0f) containerWidth else containerHeight

        val viewWidthDp = with(density) { viewWidthPx.toDp() }
        val viewHeightDp = with(density) { viewHeightPx.toDp() }

        // 1. TextureView-backed Android PlayerView supporting transformations
        AndroidView(
            modifier = if (containerWidth > 0f && containerHeight > 0f) {
                Modifier
                    .size(viewWidthDp, viewHeightDp)
                    .graphicsLayer {
                        this.rotationZ = rotationDegrees
                        this.scaleX = scale
                        this.scaleY = scale
                        this.translationX = offset.x
                        this.translationY = offset.y
                    }
            } else {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.rotationZ = rotationDegrees
                        this.scaleX = scale
                        this.scaleY = scale
                        this.translationX = offset.x
                        this.translationY = offset.y
                    }
            },
            factory = { ctx ->
                val frameLayout = object : FrameLayout(ctx) {
                    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
                    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
                }
                val playerView = LayoutInflater.from(ctx)
                    .inflate(R.layout.video_player_texture_view, frameLayout, false) as PlayerView
                playerView.apply {
                    this.player = exoPlayer
                    useController = false
                    isClickable = false
                    isFocusable = false
                    this.resizeMode = resizeMode
                }
                frameLayout.addView(playerView)
                frameLayout
            },
            update = { frameLayout ->
                val playerView = frameLayout.getChildAt(0) as? PlayerView ?: return@AndroidView
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
                if (playerView.resizeMode != resizeMode) {
                    playerView.resizeMode = resizeMode
                    playerView.requestLayout()
                    frameLayout.requestLayout()
                }
            },
        )

        // 2. Gesture Detection Layer (Pinch-to-zoom, Pan, Double-tap seek/zoom, Single-tap controls toggle)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(containerWidth, containerHeight) {
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
                                if (!isMultiTouch) {
                                    isMultiTouch = true
                                    // Auto-hide controls so user has unobstructed view while zooming
                                    if (showControls) {
                                        onToggleControls()
                                    }
                                }
                                val p0 = activePointers[0].position
                                val p1 = activePointers[1].position
                                val currentDistance = distanceBetween(p0, p1)
                                val currentCentroid = Offset((p0.x + p1.x) * 0.5f, (p0.y + p1.y) * 0.5f)

                                if (prevDistance > 0f) {
                                    val zoomRatio = currentDistance / prevDistance
                                    val panDelta = currentCentroid - prevCentroid

                                    val newScale = (scale * zoomRatio).coerceIn(1f, 5f)
                                    scale = newScale
                                    onZoomChanged(newScale > 1.05f)

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

                        // Gesture ended (all fingers lifted)
                        val durationMs = System.currentTimeMillis() - downTime
                        if (!isMultiTouch && !isPanGesture && !hasMovedPastSlop && durationMs < 400L) {
                            val now = System.currentTimeMillis()
                            val tapPos = downPos

                            if (now - lastTapTime < 320L && distanceBetween(tapPos, lastTapPosition) < 120f) {
                                // Double-tap detected
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = null
                                lastTapTime = 0L

                                val w = containerWidth
                                when {
                                    tapPos.x < w * 0.35f -> {
                                        // Rewind 10s
                                        val newPos = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                                        exoPlayer.seekTo(newPos)
                                        currentPosition = newPos
                                        showRewindBadge = true
                                    }
                                    tapPos.x > w * 0.65f -> {
                                        // Fast Forward 10s
                                        val newPos = (exoPlayer.currentPosition + 10000L).coerceAtMost(duration)
                                        exoPlayer.seekTo(newPos)
                                        currentPosition = newPos
                                        showForwardBadge = true
                                    }
                                    else -> {
                                        // Center double-tap: Toggle 2.5x Zoom / 1x
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                            onZoomChanged(false)
                                        } else {
                                            scale = 2.5f
                                            offset = Offset.Zero
                                            onZoomChanged(true)
                                        }
                                    }
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
                },
        )

        // 3. Double-tap Rewind Indicator Badge (-10s)
        AnimatedVisibility(
            visible = showRewindBadge,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⏪ -10s",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 4. Double-tap Fast-Forward Indicator Badge (+10s)
        AnimatedVisibility(
            visible = showForwardBadge,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+10s ⏩",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 5. Mode feedback notification badge (e.g. Fit vs Fill, Repeat ON/OFF, Speed)
        AnimatedVisibility(
            visible = modeFeedbackText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp),
        ) {
            modeFeedbackText?.let { text ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.8f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 6. Zoom Level Indicator & Quick Reset Chip (shows when zoomed)
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
                        onZoomChanged(false)
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

        // 7. Interactive On-Screen Playback Controls Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Dimmed backdrop (transparent to touch gestures so pinch/tap fall through to gesture layer)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                )

                // Center Transport Controls (Rewind 10s, Play/Pause, Forward 10s)
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Rewind 10s Button
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable {
                                val newPos = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⏪", fontSize = 18.sp)
                    }

                    // Main Play / Pause Circle Button
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                    exoPlayer.seekTo(0L)
                                    exoPlayer.play()
                                } else if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isPlaying) "⏸" else "▶",
                            color = Color.Black,
                            fontSize = 28.sp,
                        )
                    }

                    // Forward 10s Button
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable {
                                val newPos = (exoPlayer.currentPosition + 10000L).coerceAtMost(duration)
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⏩", fontSize = 18.sp)
                    }
                }

                // Bottom Controls Card: Scrubber Bar, Speed, Rotation, Fit Mode, Repeat, Volume
                Surface(
                    color = Color(0xFF141414).copy(alpha = 0.92f),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomPadding)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        // Time Scrubber Row
                        val displayPos = if (isScrubbing) scrubPosition else currentPosition
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatDuration(displayPos),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )

                            Slider(
                                value = if (duration > 0) displayPos.toFloat() / duration else 0f,
                                onValueChange = { frac ->
                                    isScrubbing = true
                                    scrubPosition = (frac * duration).toLong().coerceIn(0L, duration)
                                },
                                onValueChangeFinished = {
                                    exoPlayer.seekTo(scrubPosition)
                                    currentPosition = scrubPosition
                                    isScrubbing = false
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.DarkGray,
                                ),
                            )

                            Text(
                                text = formatDuration(duration),
                                color = Color(0xFFA0A0A0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // Feature Action Buttons Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 1. Mute / Unmute Button
                            IconButton(onClick = { isMuted = !isMuted }) {
                                Text(
                                    text = if (isMuted) "🔇" else "🔊",
                                    fontSize = 18.sp,
                                )
                            }

                            // 2. Playback Speed Button (e.g. "1x", "1.5x")
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (playbackSpeed != 1.0f) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2E),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showSpeedDialog = true },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "${if (playbackSpeed % 1f == 0f) playbackSpeed.toInt() else playbackSpeed}x",
                                        color = if (playbackSpeed != 1.0f) MaterialTheme.colorScheme.onPrimary else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }

                            // 3. Manual 90-degree Rotation Button
                            IconButton(
                                onClick = {
                                    rotationDegrees = (rotationDegrees + 90f) % 360f
                                    scale = 1f
                                    offset = Offset.Zero
                                    onZoomChanged(false)
                                    modeFeedbackText = "Rotated ${rotationDegrees.toInt()}°"
                                }
                            ) {
                                RotateMediaIcon(modifier = Modifier.size(19.dp))
                            }

                            // 4. Aspect Ratio Toggle (Fit vs Fill/Crop)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF2C2C2E),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            modeFeedbackText = "Aspect: Fill / Crop"
                                        } else {
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            modeFeedbackText = "Aspect: Fit"
                                        }
                                    },
                            ) {
                                Text(
                                    text = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) "Fit" else "Fill",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }

                            // 5. Screen Orientation Toggle (Fullscreen Landscape <-> Portrait)
                            IconButton(
                                onClick = {
                                    val act = activity ?: return@IconButton
                                    if (isLandscape) {
                                        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        modeFeedbackText = "Orientation: Portrait"
                                    } else {
                                        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        modeFeedbackText = "Orientation: Landscape"
                                    }
                                }
                            ) {
                                Text(
                                    text = if (isLandscape) "📱" else "⛶",
                                    fontSize = 18.sp,
                                )
                            }

                            // 6. Repeat / Loop Playback Button
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isRepeatEnabled) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2E),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        isRepeatEnabled = !isRepeatEnabled
                                        exoPlayer.repeatMode = if (isRepeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                                        modeFeedbackText = if (isRepeatEnabled) "Repeat: ON (Looping)" else "Repeat: OFF"
                                        if (isRepeatEnabled && exoPlayer.playbackState == Player.STATE_ENDED) {
                                            exoPlayer.seekTo(0L)
                                            exoPlayer.play()
                                        }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = if (isRepeatEnabled) "🔂" else "🔁",
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        text = if (isRepeatEnabled) "Loop" else "Once",
                                        color = if (isRepeatEnabled) MaterialTheme.colorScheme.onPrimary else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 8. Playback Speed Selector Modal Dialog
        if (showSpeedDialog) {
            Dialog(onDismissRequest = { showSpeedDialog = false }) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Playback Speed",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Grid of speed choices
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AVAILABLE_SPEEDS.chunked(4).forEach { rowSpeeds ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    rowSpeeds.forEach { speed ->
                                        val isSelected = playbackSpeed == speed
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2E)
                                                )
                                                .clickable {
                                                    playbackSpeed = speed
                                                    showSpeedDialog = false
                                                    modeFeedbackText = "Speed: ${speed}x"
                                                }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "${speed}x",
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Close",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { showSpeedDialog = false }
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun distanceBetween(p1: Offset, p2: Offset): Float {
    return hypot(p1.x - p2.x, p1.y - p2.y)
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes % 60, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
