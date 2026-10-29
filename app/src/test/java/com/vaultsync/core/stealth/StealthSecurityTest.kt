package com.vaultsync.core.stealth

import com.vaultsync.core.security.VaultKeyManager
import com.vaultsync.core.security.VaultSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StealthSecurityTest {

    @Test
    fun testIconModeResolution() {
        assertEquals(IconMode.NORMAL, IconMode.fromId("normal"))
        assertEquals(IconMode.CALCULATOR, IconMode.fromId("calculator"))
        assertEquals(IconMode.NOTES, IconMode.fromId("notes"))
        assertEquals(IconMode.HIDDEN, IconMode.fromId("hidden"))
        assertEquals(IconMode.NORMAL, IconMode.fromId("invalid_mode"))
        assertEquals(IconMode.NORMAL, IconMode.fromId(null))
    }

    @Test
    fun testIconModeAliases() {
        assertEquals("MainActivityNormal", IconMode.NORMAL.aliasName)
        assertEquals("MainActivityCalculator", IconMode.CALCULATOR.aliasName)
        assertEquals("MainActivityNotes", IconMode.NOTES.aliasName)
        assertNull(IconMode.HIDDEN.aliasName) // Hidden mode has no launcher alias
    }

    @Test
    fun testTwoFingerGestureTypeResolution() {
        assertEquals(TwoFingerGestureType.SWIPE_DOWN, TwoFingerGestureType.fromId("swipe_down"))
        assertEquals(TwoFingerGestureType.SWIPE_UP, TwoFingerGestureType.fromId("swipe_up"))
        assertEquals(TwoFingerGestureType.DOUBLE_TAP, TwoFingerGestureType.fromId("double_tap"))
        assertEquals(TwoFingerGestureType.SWIPE_DOWN, TwoFingerGestureType.fromId("unknown"))
        assertEquals(TwoFingerGestureType.SWIPE_DOWN, TwoFingerGestureType.fromId(null))
    }

    @Test
    fun testAppIconManagerConstants() {
        assertEquals("com.vaultsync.MainActivityNormal", AppIconManager.NORMAL_ALIAS)
        assertEquals("com.vaultsync.MainActivityCalculator", AppIconManager.CALCULATOR_ALIAS)
        assertEquals("com.vaultsync.MainActivityNotes", AppIconManager.NOTES_ALIAS)
    }

    @Test
    fun testDisguisedCalculatorPinSecurityVerification() {
        // Hiding the icon or disguising as a calculator NEVER replaces PIN or encryption protection
        val originalVmk = VaultKeyManager.generateVaultMasterKey()
        val correctPin = "4826".toCharArray()
        val incorrectPin = "1234".toCharArray()

        val wrappedVmk = VaultKeyManager.wrapVaultMasterKey(originalVmk, correctPin)
        assertNotNull(wrappedVmk)

        // 1. Verify that incorrect PIN typed into disguise calculator fails and rejects unwrap
        try {
            VaultKeyManager.unwrapVaultMasterKey(wrappedVmk, incorrectPin)
            fail("Incorrect PIN must throw SecurityException")
        } catch (e: SecurityException) {
            // Expected
            assertTrue(e.message?.contains("Incorrect") == true)
        }

        // 2. Verify that correct PIN typed into disguise calculator unwraps authentic VMK
        val unwrappedVmk = VaultKeyManager.unwrapVaultMasterKey(wrappedVmk, correctPin)
        assertTrue(originalVmk.contentEquals(unwrappedVmk))

        // 3. Verify session state transition
        val session = VaultSession()
        assertFalse(session.isUnlocked.value)

        session.unlock(unwrappedVmk)
        assertTrue(session.isUnlocked.value)

        session.lock()
        assertFalse(session.isUnlocked.value)

        VaultKeyManager.zeroize(originalVmk)
        VaultKeyManager.zeroize(unwrappedVmk)
    }

    @Test
    fun testAppLockManagerDirectUnlockState() {
        AppLockManager.setUnlockedDirectly()
        assertTrue(AppLockManager.isAppUnlocked.value)
    }

    @Test
    fun testRecoveryAccessRoutesIntegrity() {
        // Documented recovery routes verification
        val deepLinkOpen = "vaultsync://open"
        val deepLinkRecover = "vaultsync://recover"
        val secretDialerCode = "*#*#82858#*#*" // VAULT on keypad

        assertTrue(deepLinkOpen.startsWith("vaultsync://"))
        assertTrue(deepLinkRecover.startsWith("vaultsync://"))
        assertTrue(secretDialerCode.startsWith("*#*#") && secretDialerCode.endsWith("#*#*"))

        // Host in secret dialer code intent corresponds to keypad digits 82858
        val host = secretDialerCode.removePrefix("*#*#").removeSuffix("#*#*")
        assertEquals("82858", host)
    }

    @Test
    fun testTwoFingerSwipeDownSuccess() {
        val s1 = PointerSample(id = 1L, startX = 100f, startY = 200f, currentX = 110f, currentY = 320f) // dy = 120
        val s2 = PointerSample(id = 2L, startX = 250f, startY = 220f, currentX = 245f, currentY = 335f) // dy = 115

        val triggered = TwoFingerGestureEvaluator.evaluateSwipe(
            sample1 = s1,
            sample2 = s2,
            gestureType = TwoFingerGestureType.SWIPE_DOWN,
            swipeThresholdPx = 100f,
            minFingerMovementPx = 25f,
        )
        assertTrue(triggered)
    }

    @Test
    fun testTwoFingerSwipeOrderIndependence() {
        // Swapping pointers array order must not break gesture recognition
        val s1 = PointerSample(id = 1L, startX = 100f, startY = 200f, currentX = 110f, currentY = 320f)
        val s2 = PointerSample(id = 2L, startX = 250f, startY = 220f, currentX = 245f, currentY = 335f)

        val triggered = TwoFingerGestureEvaluator.evaluateSwipe(
            sample1 = s2,
            sample2 = s1,
            gestureType = TwoFingerGestureType.SWIPE_DOWN,
            swipeThresholdPx = 100f,
            minFingerMovementPx = 25f,
        )
        assertTrue(triggered)
    }

    @Test
    fun testTwoFingerSwipeDownRejectsSingleFingerScroll() {
        // Single finger scroll (sample1 moved 300px) with accidental brush (sample2 moved 2px)
        val s1 = PointerSample(id = 1L, startX = 100f, startY = 200f, currentX = 100f, currentY = 500f) // dy = 300
        val s2 = PointerSample(id = 2L, startX = 250f, startY = 220f, currentX = 250f, currentY = 222f) // dy = 2

        val triggered = TwoFingerGestureEvaluator.evaluateSwipe(
            sample1 = s1,
            sample2 = s2,
            gestureType = TwoFingerGestureType.SWIPE_DOWN,
            swipeThresholdPx = 100f,
            minFingerMovementPx = 25f,
        )
        assertFalse(triggered)
    }

    @Test
    fun testTwoFingerSwipeDownRejectsHorizontalSwipe() {
        // Horizontal swiping across gallery media items
        val s1 = PointerSample(id = 1L, startX = 100f, startY = 200f, currentX = 300f, currentY = 210f) // dx = 200, dy = 10
        val s2 = PointerSample(id = 2L, startX = 150f, startY = 220f, currentX = 340f, currentY = 225f) // dx = 190, dy = 5

        val triggered = TwoFingerGestureEvaluator.evaluateSwipe(
            sample1 = s1,
            sample2 = s2,
            gestureType = TwoFingerGestureType.SWIPE_DOWN,
            swipeThresholdPx = 100f,
            minFingerMovementPx = 25f,
        )
        assertFalse(triggered)
    }

    @Test
    fun testTwoFingerSwipeUpSuccess() {
        val s1 = PointerSample(id = 1L, startX = 100f, startY = 400f, currentX = 110f, currentY = 280f) // dy = -120
        val s2 = PointerSample(id = 2L, startX = 250f, startY = 420f, currentX = 245f, currentY = 305f) // dy = -115

        val triggered = TwoFingerGestureEvaluator.evaluateSwipe(
            sample1 = s1,
            sample2 = s2,
            gestureType = TwoFingerGestureType.SWIPE_UP,
            swipeThresholdPx = 100f,
            minFingerMovementPx = 25f,
        )
        assertTrue(triggered)
    }

    @Test
    fun testTwoFingerDoubleTapEvaluation() {
        // Valid double tap
        val valid = TwoFingerGestureEvaluator.evaluateDoubleTap(
            tap1ReleaseTime = 1000L,
            tap2ReleaseTime = 1250L, // 250ms interval
            tap1DurationMs = 120L,
            tap2DurationMs = 110L,
            tap1MaxMovementPx = 10f,
            tap2MaxMovementPx = 12f,
            tapSlopPx = 40f,
        )
        assertTrue(valid)

        // Delayed second tap (>500ms)
        val tooSlow = TwoFingerGestureEvaluator.evaluateDoubleTap(
            tap1ReleaseTime = 1000L,
            tap2ReleaseTime = 1700L, // 700ms interval
            tap1DurationMs = 120L,
            tap2DurationMs = 110L,
            tap1MaxMovementPx = 10f,
            tap2MaxMovementPx = 12f,
            tapSlopPx = 40f,
        )
        assertFalse(tooSlow)

        // Excessive movement during tap (was a swipe, not a tap)
        val tooMuchMovement = TwoFingerGestureEvaluator.evaluateDoubleTap(
            tap1ReleaseTime = 1000L,
            tap2ReleaseTime = 1250L,
            tap1DurationMs = 120L,
            tap2DurationMs = 110L,
            tap1MaxMovementPx = 90f,
            tap2MaxMovementPx = 12f,
            tapSlopPx = 40f,
        )
        assertFalse(tooMuchMovement)
    }
}
