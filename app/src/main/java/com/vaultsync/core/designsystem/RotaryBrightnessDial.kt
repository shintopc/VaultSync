package com.vaultsync.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Premium circular rotary dial for adjusting screen brightness.
 * Features:
 * - 270° interactive arc with rotational drag gesture tracking
 * - Smooth gradient track + illuminated thumb indicator
 * - Dynamic brightness icon (Nightlight -> Low -> Medium -> High)
 * - Quick "Auto / System" reset button
 * - Haptic feedback on value changes
 */
@Composable
fun RotaryBrightnessDial(
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    dialSize: Dp = 180.dp,
    onResetToAuto: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    showCloseButton: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Internal tracker for haptic ticks every ~5%
    var lastHapticBucket by remember { mutableFloatStateOf((brightness * 20).roundToInt().toFloat()) }

    fun processAngleToBrightness(touchX: Float, touchY: Float, centerX: Float, centerY: Float) {
        val dx = touchX - centerX
        val dy = touchY - centerY
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f

        // Arc starts at 135° and sweeps 270° clockwise to 405° (45°)
        // The bottom 90° (from 45° to 135°) is the deadzone gap
        val newBrightness = when {
            angle in 135f..360f -> (angle - 135f) / 270f
            angle in 0f..45f -> (angle + 360f - 135f) / 270f
            angle < 90f -> 1.0f // Closer to max
            else -> 0.01f       // Closer to min
        }.coerceIn(0.01f, 1.0f)

        onBrightnessChange(newBrightness)

        val bucket = (newBrightness * 20).roundToInt().toFloat()
        if (bucket != lastHapticBucket) {
            lastHapticBucket = bucket
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Surface(
        modifier = modifier
            .shadow(16.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp)),
        color = Color(0xFF141416).copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Header with title and optional close button
            Row(
                modifier = Modifier.width(dialSize),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "BRIGHTNESS",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )

                if (showCloseButton && onDismiss != null) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Rotary Dial Canvas & Center HUD
            Box(
                modifier = Modifier.size(dialSize),
                contentAlignment = Alignment.Center,
            ) {
                val strokeWidthDp = 12.dp
                val strokeWidthPx = with(density) { strokeWidthDp.toPx() }

                Canvas(
                    modifier = Modifier
                        .size(dialSize)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                processAngleToBrightness(offset.x, offset.y, cx, cy)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                processAngleToBrightness(change.position.x, change.position.y, cx, cy)
                            }
                        },
                ) {
                    val w = size.width
                    val h = size.height
                    val cx = w / 2f
                    val cy = h / 2f
                    val padding = strokeWidthPx / 2f + 4.dp.toPx()
                    val arcSize = Size(w - padding * 2, h - padding * 2)
                    val arcTopLeft = Offset(padding, padding)
                    val radius = (w - padding * 2) / 2f

                    val startAngle = 135f
                    val totalSweep = 270f
                    val activeSweep = (brightness * totalSweep).coerceIn(1f, totalSweep)

                    // 1. Inactive background track
                    drawArc(
                        color = Color.White.copy(alpha = 0.12f),
                        startAngle = startAngle,
                        sweepAngle = totalSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                    )

                    // 2. Active illuminated gradient arc
                    val activeBrush = Brush.sweepGradient(
                        0.0f to Color(0xFFFFD54F),
                        0.5f to Color(0xFFFF9800),
                        1.0f to Color(0xFFFF5722),
                        center = Offset(cx, cy),
                    )

                    drawArc(
                        brush = activeBrush,
                        startAngle = startAngle,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                    )

                    // 3. Current Thumb position (knob indicator)
                    val thumbAngle = startAngle + activeSweep
                    val thumbRad = Math.toRadians(thumbAngle.toDouble())
                    val thumbX = cx + radius * cos(thumbRad).toFloat()
                    val thumbY = cy + radius * sin(thumbRad).toFloat()

                    // Glow circle behind thumb
                    drawCircle(
                        color = Color(0xFFFFB300).copy(alpha = 0.35f),
                        radius = strokeWidthPx * 1.1f,
                        center = Offset(thumbX, thumbY),
                    )

                    // Solid thumb knob
                    drawCircle(
                        color = Color.White,
                        radius = strokeWidthPx * 0.65f,
                        center = Offset(thumbX, thumbY),
                    )

                    // Inner accent dot
                    drawCircle(
                        color = Color(0xFFFF8F00),
                        radius = strokeWidthPx * 0.28f,
                        center = Offset(thumbX, thumbY),
                    )
                }

                // Center Display: Icon + Percentage Value
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val icon = when {
                        brightness < 0.20f -> Icons.Rounded.Nightlight
                        brightness < 0.50f -> Icons.Rounded.BrightnessLow
                        brightness < 0.80f -> Icons.Rounded.BrightnessMedium
                        else -> Icons.Rounded.BrightnessHigh
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = "Brightness level",
                        tint = when {
                            brightness < 0.20f -> Color(0xFF90CAF9)
                            brightness < 0.50f -> Color(0xFFFFE082)
                            else -> Color(0xFFFFB300)
                        },
                        modifier = Modifier.size(28.dp),
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "${(brightness * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )

                    Text(
                        text = when {
                            brightness < 0.20f -> "Night Mode"
                            brightness < 0.50f -> "Comfort"
                            brightness < 0.80f -> "Daylight"
                            else -> "Max Boost"
                        },
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Auto Reset Button Pill
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.1f),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onResetToAuto()
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "System Auto",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

