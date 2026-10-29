package com.vaultsync.core.stealth

import kotlin.math.abs

data class PointerSample(
    val id: Long,
    val startX: Float,
    val startY: Float,
    val currentX: Float,
    val currentY: Float,
)

/**
 * Pure evaluation engine for multi-touch security gestures.
 * Decouples mathematics and thresholds from the Compose pointer event loop,
 * ensuring robust unit-testability and stable tracking across Android devices.
 */
object TwoFingerGestureEvaluator {

    /**
     * Evaluates whether two tracked pointers constitute a valid two-finger swipe
     * (Swipe Down or Swipe Up) while ignoring single-finger scrolls and diagonal drifts.
     */
    fun evaluateSwipe(
        sample1: PointerSample,
        sample2: PointerSample,
        gestureType: TwoFingerGestureType,
        swipeThresholdPx: Float,
        minFingerMovementPx: Float = swipeThresholdPx * 0.25f,
    ): Boolean {
        val dy1 = sample1.currentY - sample1.startY
        val dy2 = sample2.currentY - sample2.startY
        val dx1 = sample1.currentX - sample1.startX
        val dx2 = sample2.currentX - sample2.startX

        val avgDy = (dy1 + dy2) / 2f
        val avgDx = (dx1 + dx2) / 2f

        // Allow natural human hand angle (up to ~55 degrees from vertical)
        val isPredominantlyVertical = abs(avgDy) > abs(avgDx) * 0.6f

        return when (gestureType) {
            TwoFingerGestureType.SWIPE_DOWN -> {
                val bothMovingDown = dy1 >= minFingerMovementPx && dy2 >= minFingerMovementPx
                val avgDownEnough = avgDy >= swipeThresholdPx
                bothMovingDown && avgDownEnough && isPredominantlyVertical
            }
            TwoFingerGestureType.SWIPE_UP -> {
                val bothMovingUp = dy1 <= -minFingerMovementPx && dy2 <= -minFingerMovementPx
                val avgUpEnough = avgDy <= -swipeThresholdPx
                bothMovingUp && avgUpEnough && isPredominantlyVertical
            }
            TwoFingerGestureType.DOUBLE_TAP -> false
        }
    }

    /**
     * Evaluates whether two consecutive two-finger taps constitute a secret double-tap.
     */
    fun evaluateDoubleTap(
        tap1ReleaseTime: Long,
        tap2ReleaseTime: Long,
        tap1DurationMs: Long,
        tap2DurationMs: Long,
        tap1MaxMovementPx: Float,
        tap2MaxMovementPx: Float,
        tapSlopPx: Float,
        maxDurationPerTapMs: Long = 450L,
        maxInterTapDelayMs: Long = 500L,
    ): Boolean {
        if (tap1DurationMs > maxDurationPerTapMs || tap2DurationMs > maxDurationPerTapMs) return false
        if (tap1MaxMovementPx > tapSlopPx || tap2MaxMovementPx > tapSlopPx) return false
        val delay = tap2ReleaseTime - tap1ReleaseTime
        return delay in 40L..maxInterTapDelayMs
    }
}
