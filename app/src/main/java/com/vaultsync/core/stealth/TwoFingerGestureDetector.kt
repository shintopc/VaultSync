package com.vaultsync.core.stealth

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * Reusable Compose modifier that detects two-finger gestures (Swipe Down, Swipe Up, Double Tap)
 * to trigger secret VaultSync main app access without disturbing single-finger interactions.
 */
fun Modifier.detectTwoFingerVaultGesture(
    enabled: Boolean = true,
    gestureType: TwoFingerGestureType = TwoFingerGestureType.SWIPE_DOWN,
    onGestureTriggered: () -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this

    val currentOnTriggered by rememberUpdatedState(onGestureTriggered)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // 32.dp threshold ensures effortless, natural recognition on modern phones & small test areas
    val swipeThresholdPx = with(density) { 32.dp.toPx() }
    val minFingerMovementPx = with(density) { 8.dp.toPx() }
    val tapSlopPx = with(density) { 24.dp.toPx() }

    this.pointerInput(enabled, gestureType) {
        var lastTwoFingerReleaseTime = 0L
        var lastTwoFingerDuration = 0L
        var lastTwoFingerMaxMovement = 0f

        awaitEachGesture {
            val startPositions = mutableMapOf<PointerId, Offset>()
            val currentPositions = mutableMapOf<PointerId, Offset>()
            var maxSimultaneousPointers = 0
            var gestureFired = false
            var gestureStartTime = 0L

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressedChanges = event.changes.filter { it.pressed }
                val pressedCount = pressedChanges.size

                if (gestureStartTime == 0L && pressedCount > 0) {
                    gestureStartTime = System.currentTimeMillis()
                }

                if (pressedCount > maxSimultaneousPointers) {
                    maxSimultaneousPointers = pressedCount
                }

                // Track pointer positions by unique PointerId to prevent array index swapping bugs
                for (change in event.changes) {
                    if (change.pressed) {
                        if (!startPositions.containsKey(change.id)) {
                            startPositions[change.id] = change.position
                            // When a second finger joins, calibrate existing pointer start
                            // so we measure synchronized movement from the moment both fingers are down
                            if (startPositions.size == 2) {
                                for (existingId in startPositions.keys) {
                                    if (existingId != change.id) {
                                        currentPositions[existingId]?.let { pos ->
                                            startPositions[existingId] = pos
                                        }
                                    }
                                }
                            }
                        }
                        currentPositions[change.id] = change.position
                    } else {
                        // Keep last recorded position upon release
                        currentPositions[change.id] = change.position
                    }
                }

                // Determine active candidates for multi-touch evaluation
                val candidates = if (pressedChanges.size >= 2) {
                    pressedChanges.map { it.id }.take(2)
                } else if (startPositions.size >= 2) {
                    startPositions.keys.toList().takeLast(2)
                } else {
                    emptyList()
                }

                if (!gestureFired && candidates.size == 2) {
                    val id1 = candidates[0]
                    val id2 = candidates[1]
                    val start1 = startPositions[id1]
                    val start2 = startPositions[id2]
                    val curr1 = currentPositions[id1]
                    val curr2 = currentPositions[id2]

                    if (start1 != null && start2 != null && curr1 != null && curr2 != null) {
                        val sample1 = PointerSample(id1.value, start1.x, start1.y, curr1.x, curr1.y)
                        val sample2 = PointerSample(id2.value, start2.x, start2.y, curr2.x, curr2.y)

                        if (gestureType != TwoFingerGestureType.DOUBLE_TAP) {
                            val triggered = TwoFingerGestureEvaluator.evaluateSwipe(
                                sample1 = sample1,
                                sample2 = sample2,
                                gestureType = gestureType,
                                swipeThresholdPx = swipeThresholdPx,
                                minFingerMovementPx = minFingerMovementPx,
                            )

                            if (triggered) {
                                gestureFired = true
                                event.changes.forEach { it.consume() }
                                try {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                } catch (_: Exception) {}
                                currentOnTriggered()
                            }
                        }
                    }
                }

                if (gestureFired) {
                    event.changes.forEach { it.consume() }
                }

                // All fingers released: check double tap or end gesture sequence
                if (pressedChanges.isEmpty()) {
                    if (gestureType == TwoFingerGestureType.DOUBLE_TAP && !gestureFired && maxSimultaneousPointers >= 2) {
                        val duration = System.currentTimeMillis() - gestureStartTime
                        var maxMovement = 0f
                        for ((id, start) in startPositions) {
                            val curr = currentPositions[id] ?: start
                            val dx = curr.x - start.x
                            val dy = curr.y - start.y
                            val dist = sqrt(dx * dx + dy * dy)
                            if (dist > maxMovement) maxMovement = dist
                        }

                        val now = System.currentTimeMillis()
                        if (lastTwoFingerReleaseTime > 0L) {
                            val isDoubleTap = TwoFingerGestureEvaluator.evaluateDoubleTap(
                                tap1ReleaseTime = lastTwoFingerReleaseTime,
                                tap2ReleaseTime = now,
                                tap1DurationMs = lastTwoFingerDuration,
                                tap2DurationMs = duration,
                                tap1MaxMovementPx = lastTwoFingerMaxMovement,
                                tap2MaxMovementPx = maxMovement,
                                tapSlopPx = tapSlopPx,
                            )

                            if (isDoubleTap) {
                                gestureFired = true
                                lastTwoFingerReleaseTime = 0L
                                try {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                } catch (_: Exception) {}
                                currentOnTriggered()
                            } else {
                                lastTwoFingerReleaseTime = now
                                lastTwoFingerDuration = duration
                                lastTwoFingerMaxMovement = maxMovement
                            }
                        } else {
                            lastTwoFingerReleaseTime = now
                            lastTwoFingerDuration = duration
                            lastTwoFingerMaxMovement = maxMovement
                        }
                    }
                    break
                }
            }
        }
    }
}
