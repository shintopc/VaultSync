package com.vaultsync.core.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.vaultsync.core.designsystem.RotaryBrightnessDial
import com.vaultsync.core.designsystem.ZoomResetChip
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
    val haptic = LocalHapticFeedback.current

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
    var showSpeedSelector by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }

    // Repeat / Loop Playback State
    var isRepeatEnabled by remember { mutableStateOf(false) }

    // Aspect Ratio Fit Mode
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Quick-tap feedback badges & status messages
    var showRewindBadge by remember { mutableStateOf(false) }
    var showForwardBadge by remember { mutableStateOf(false) }
    var modeFeedbackText by remember { mutableStateOf<String?>(null) }

    // Brightness States & Window Controls
    val initialSysBrightness = remember(context, activity) {
        val windowBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        if (windowBrightness in 0.01f..1.0f) {
            windowBrightness
        } else {
            try {
                val resolver = context.contentResolver
                android.provider.Settings.System.getInt(
                    resolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    128
                ) / 255f
            } catch (_: Exception) {
                0.7f
            }.coerceIn(0.1f, 1.0f)
        }
    }
    var brightness by remember { mutableFloatStateOf(initialSysBrightness) }
    var isBrightnessDragging by remember { mutableStateOf(false) }
    var showBrightnessBadge by remember { mutableStateOf(false) }
    var showBrightnessDial by remember { mutableStateOf(false) }

    fun updateBrightness(newVal: Float) {
        val clamped = newVal.coerceIn(0.01f, 1.0f)
        brightness = clamped
        activity?.let { act ->
            val lp = act.window.attributes
            lp.screenBrightness = clamped
            act.window.attributes = lp
        }
        showBrightnessBadge = true
    }

    fun resetToSystemBrightness() {
        activity?.let { act ->
            val lp = act.window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            act.window.attributes = lp
        }
        val sysVal = try {
            val resolver = context.contentResolver
            android.provider.Settings.System.getInt(
                resolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                128
            ) / 255f
        } catch (_: Exception) {
            0.7f
        }.coerceIn(0.1f, 1.0f)
        brightness = sysVal
        modeFeedbackText = "Brightness: System Default"
    }

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
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val lp = act.window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                act.window.attributes = lp
            }
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
    LaunchedEffect(showControls, isPlaying, isScrubbing, showSpeedSelector, showBrightnessDial) {
        if (showControls && isPlaying && !isScrubbing && !showSpeedSelector && !showBrightnessDial) {
            delay(3500)
            onToggleControls()
        }
    }

    // Clear feedback badges
    LaunchedEffect(showBrightnessBadge, isBrightnessDragging) {
        if (showBrightnessBadge && !isBrightnessDragging) {
            delay(1200)
            showBrightnessBadge = false
        }
    }
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

        // 1.5 Soft dark scrim overlay for ultra-night viewing when brightness is very low (< 0.2f)
        if (brightness < 0.2f) {
            val nightScrimAlpha = ((0.2f - brightness) / 0.2f * 0.6f).coerceIn(0f, 0.6f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = nightScrimAlpha))
            )
        }

        // 2. Gesture Detection Layer (Pinch-to-zoom, Pan, Double-tap seek/zoom, Left vertical swipe brightness, Single-tap controls toggle)
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
                        var isBrightnessGesture = false
                        var wasBrightnessDrag = false
                        var hasMovedPastSlop = false
                        val touchSlop = viewConfiguration.touchSlop

                        var prevDistance = 0f
                        var prevCentroid = Offset.Zero
                        var brightnessStartVal = brightness

                        do {
                            val event = awaitPointerEvent()
                            val activePointers = event.changes.filter { it.pressed }
                            val count = activePointers.size

                            if (count >= 2) {
                                if (isBrightnessGesture) {
                                    isBrightnessGesture = false
                                    isBrightnessDragging = false
                                }
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
                                val dx = currentPos.x - downPos.x
                                val dy = currentPos.y - downPos.y
                                val dragDistance = distanceBetween(currentPos, downPos)

                                if (!isBrightnessGesture && !hasMovedPastSlop && dragDistance > touchSlop) {
                                    val isLeftHalf = downPos.x < containerWidth * 0.45f
                                    val isVertical = kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.3f
                                    if (isLeftHalf && isVertical) {
                                        isBrightnessGesture = true
                                        wasBrightnessDrag = true
                                        isBrightnessDragging = true
                                        showBrightnessBadge = true
                                        brightnessStartVal = brightness
                                    } else {
                                        hasMovedPastSlop = true
                                    }
                                }

                                if (isBrightnessGesture) {
                                    event.changes.forEach { it.consume() }
                                    val deltaY = (downPos.y - currentPos.y) // Upwards increases brightness
                                    val brightnessDelta = deltaY / (containerHeight * 0.65f)
                                    val newBrightness = (brightnessStartVal + brightnessDelta).coerceIn(0.01f, 1.0f)
                                    updateBrightness(newBrightness)
                                }

                                prevCentroid = currentPos
                                prevDistance = 0f
                            }
                        } while (event.changes.any { it.pressed })

                        // Gesture ended (all fingers lifted)
                        if (isBrightnessGesture) {
                            isBrightnessGesture = false
                            isBrightnessDragging = false
                        }

                        val durationMs = System.currentTimeMillis() - downTime
                        if (!isMultiTouch && !isPanGesture && !hasMovedPastSlop && !wasBrightnessDrag && durationMs < 400L) {
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

        // 2.5 Vertical Brightness Level Indicator Badge (Left Side)
        AnimatedVisibility(
            visible = showBrightnessBadge,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 28.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.85f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val icon = when {
                        brightness < 0.20f -> Icons.Rounded.Nightlight
                        brightness < 0.50f -> Icons.Rounded.BrightnessLow
                        brightness < 0.80f -> Icons.Rounded.BrightnessMedium
                        else -> Icons.Rounded.BrightnessHigh
                    }
                    val iconTint = when {
                        brightness < 0.20f -> Color(0xFF90CAF9)
                        brightness < 0.50f -> Color(0xFFFFE082)
                        else -> Color(0xFFFFB300)
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Brightness level",
                        tint = iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                    // Vertical Progress Bar
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(100.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp * brightness)
                                .clip(RoundedCornerShape(3.dp))
                                .background(iconTint),
                        )
                    }
                    Text(
                        text = "${(brightness * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

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
                    .background(Color.Black.copy(alpha = 0.82f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay10,
                        contentDescription = "Rewind 10 seconds",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "-10s",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
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
                    .background(Color.Black.copy(alpha = 0.82f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "+10s",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        imageVector = Icons.Rounded.Forward10,
                        contentDescription = "Forward 10 seconds",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
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
        ZoomResetChip(
            scale = scale,
            onReset = {
                scale = 1f
                offset = Offset.Zero
                onZoomChanged(false)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 80.dp, end = 16.dp),
        )

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
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Rewind 10s Button
                    Surface(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val newPos = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                            },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.65f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    // Main Play / Pause Circle Button
                    Surface(
                        modifier = Modifier
                            .size(68.dp)
                            .shadow(12.dp, CircleShape)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                    exoPlayer.seekTo(0L)
                                    exoPlayer.play()
                                } else if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                            },
                        shape = CircleShape,
                        color = Color.White,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }

                    // Forward 10s Button
                    Surface(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val newPos = (exoPlayer.currentPosition + 10000L).coerceAtMost(duration)
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                            },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.65f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Forward10,
                                contentDescription = "Forward 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                // Bottom Controls Card: Scrubber Bar, Speed Carousel, Action Chips
                Surface(
                    color = Color(0xFF141414).copy(alpha = 0.94f),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomPadding)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
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
                                fontWeight = FontWeight.SemiBold,
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
                                    .padding(horizontal = 10.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                                ),
                            )

                            Text(
                                text = formatDuration(duration),
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // Inline Speed Selector Carousel
                        AnimatedVisibility(
                            visible = showSpeedSelector,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AVAILABLE_SPEEDS.forEach { speed ->
                                    val isSelected = playbackSpeed == speed
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2E),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                playbackSpeed = speed
                                                modeFeedbackText = "Speed: ${speed}x"
                                            },
                                    ) {
                                        Text(
                                            text = "${speed}x",
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Feature Action Buttons Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 1. Audio Mute / Unmute Button
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isMuted) Color(0xFFE53935).copy(alpha = 0.2f) else Color(0xFF2C2C2E),
                                border = if (isMuted) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE53935)) else null,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        isMuted = !isMuted
                                        modeFeedbackText = if (isMuted) "Audio Muted" else "Audio Enabled"
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                        contentDescription = if (isMuted) "Unmute" else "Mute",
                                        tint = if (isMuted) Color(0xFFEF5350) else Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = if (isMuted) "Muted" else "Audio",
                                        color = if (isMuted) Color(0xFFEF5350) else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            // 2. Rotary Brightness Dial Toggle Button
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (showBrightnessDial) Color(0xFFFFB300) else Color(0xFF2C2C2E),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showBrightnessDial = !showBrightnessDial
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.BrightnessMedium,
                                        contentDescription = "Brightness Dial",
                                        tint = if (showBrightnessDial) Color.Black else Color(0xFFFFD54F),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "${(brightness * 100).toInt()}%",
                                        color = if (showBrightnessDial) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            // 3. Playback Speed Button (e.g. "1x", "1.5x")
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (playbackSpeed != 1.0f || showSpeedSelector) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2E),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showSpeedSelector = !showSpeedSelector
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Speed,
                                        contentDescription = "Speed selector",
                                        tint = if (playbackSpeed != 1.0f || showSpeedSelector) MaterialTheme.colorScheme.onPrimary else Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "${if (playbackSpeed % 1f == 0f) playbackSpeed.toInt() else playbackSpeed}x",
                                        color = if (playbackSpeed != 1.0f || showSpeedSelector) MaterialTheme.colorScheme.onPrimary else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }

                            // 4. Manual 90-degree Rotation Button
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF2C2C2E),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        rotationDegrees = (rotationDegrees + 90f) % 360f
                                        scale = 1f
                                        offset = Offset.Zero
                                        onZoomChanged(false)
                                        modeFeedbackText = "Rotated ${rotationDegrees.toInt()}°"
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    RotateMediaIcon(modifier = Modifier.size(15.dp))
                                    Text(
                                        text = "Rotate",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            // 5. Aspect Ratio Toggle (Fit vs Fill/Crop)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF2C2C2E),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            modeFeedbackText = "Aspect: Fill / Crop"
                                        } else {
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            modeFeedbackText = "Aspect: Fit"
                                        }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) Icons.Rounded.CropFree else Icons.Rounded.FitScreen,
                                        contentDescription = "Aspect Ratio",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) "Fit" else "Fill",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            // 6. Screen Orientation Toggle (Fullscreen Landscape <-> Portrait)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF2C2C2E),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        val act = activity ?: return@clickable
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (isLandscape) {
                                            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                            modeFeedbackText = "Orientation: Portrait"
                                        } else {
                                            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                            modeFeedbackText = "Orientation: Landscape"
                                        }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isLandscape) Icons.Rounded.StayCurrentPortrait else Icons.Rounded.StayCurrentLandscape,
                                        contentDescription = "Orientation",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = if (isLandscape) "Portrait" else "Landscape",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            // 7. Repeat / Loop Playback Button
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isRepeatEnabled) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2E),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isRepeatEnabled) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                        contentDescription = "Loop playback",
                                        tint = if (isRepeatEnabled) MaterialTheme.colorScheme.onPrimary else Color.White,
                                        modifier = Modifier.size(16.dp),
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

                // Interactive Center Rotary Brightness Dial
                AnimatedVisibility(
                    visible = showBrightnessDial,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    RotaryBrightnessDial(
                        brightness = brightness,
                        onBrightnessChange = { newVal -> updateBrightness(newVal) },
                        dialSize = 190.dp,
                        onResetToAuto = { resetToSystemBrightness() },
                        onDismiss = { showBrightnessDial = false },
                    )
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
