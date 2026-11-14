package com.shiraka.wereposition

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shiraka.wereposition.data.repository.LocationRepository
import com.shiraka.wereposition.provider.SpooferProvider
import com.shiraka.wereposition.service.SpoofingService
import com.shiraka.wereposition.utils.RuntimeConfigWriteCoordinator
import com.shiraka.wereposition.utils.SettingsManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Real touch input and root property output, including a deliberately stalled writer. */
@RunWith(AndroidJUnit4::class)
class JoystickResponsivenessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private suspend fun waitFor(label: String, check: () -> Boolean) {
        android.util.Log.i("WRP_JOYSTICK_TEST", "Waiting: $label")
        assertTrue("Timed out: $label", withTimeoutOrNull(8_000L) {
            while (!check()) delay(20L)
            true
        } == true)
    }

    @Test(timeout = 90_000)
    fun newestTurnWinsAfterWriterStallAndReleaseStopsPromptly() = runBlocking<Unit> {
        val settings = SettingsManager(context)
        assertFalse("Stop the current simulation before testing", settings.isSpoofingActive)
        assertTrue("Overlay permission is required", Settings.canDrawOverlays(context))
        val oldPure = settings.pureModeEnabled
        val oldEnd = settings.joystickOverlayEndDp
        val oldBottom = settings.joystickOverlayBottomDp
        val oldMode = settings.joystickDirectionMode
        val locationEnabled = shell("cmd location is-location-enabled") == "true"
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1_500L)
        val repository = GlobalContext.get().get<LocationRepository>()
        var started = false
        var down = false
        var downTime = 0L
        var centerX = 0f
        var centerY = 0f
        val releaseWriter = CompletableDeferred<Unit>()
        val releaseNextWriter = CompletableDeferred<Unit>()
        var blocker: Job? = null
        var nextBlocker: Job? = null
        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        try {
            settings.pureModeEnabled = false
            settings.joystickDirectionMode = "NORTH"
            settings.setJoystickOverlayPosition(34, 176)
            shell("cmd location set-location-enabled true")
            repository.startSpoofing(context, 3.14755, 101.69990, "STILL", 0f,
                System.currentTimeMillis(), emptyList(), false, mockWifi = false, mockCell = false, enableJitter = false)
            started = true
            context.startService(Intent(context, SpoofingService::class.java).apply {
                action = SpoofingService.ACTION_JOYSTICK
                putExtra(SpoofingService.EXTRA_JOYSTICK_VISIBLE, true)
            })
            waitFor("overlay snapshot") { SpoofingService.isJoystickVisible && shell("getprop gsm.wrp.joystick_mode") == "true" }
            val size = (128 * context.resources.displayMetrics.density).toInt()
            // Custom overlay canvases have no accessibility nodes; inspect the real WindowManager
            // frame instead of guessing screen coordinates or adding production-only test controls.
            val windowDump = shell("dumpsys window windows")
            java.io.File(context.filesDir, "joystick-windows.txt").writeText(windowDump)
            var wheel: Rect? = null
            for (block in windowDump.split(Regex("(?m)^\\s*Window #"))) {
                if (!block.contains(context.packageName) || !block.contains("w=$size h=$size") ||
                    !block.contains("APPLICATION_OVERLAY")) continue
                val match = Regex("(?:mFrame|frame)=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
                    .find(block) ?: continue
                val coordinates = match.groupValues.drop(1).map(String::toInt)
                wheel = Rect(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
                break
            }
            assertNotNull("Wheel window frame missing; see files/joystick-windows.txt", wheel)
            android.util.Log.i("WRP_JOYSTICK_TEST", "wheel=$wheel")
            centerX = wheel!!.exactCenterX()
            centerY = wheel!!.exactCenterY()
            val offset = 30 * context.resources.displayMetrics.density
            val admitted = CompletableDeferred<Unit>()
            blocker = launch(Dispatchers.IO) {
                RuntimeConfigWriteCoordinator.commitIfLatest(RuntimeConfigWriteCoordinator.newGeneration()) {
                    admitted.complete(Unit)
                    releaseWriter.await()
                    false // Test stall does not commit or advance generation ordering.
                }
            }
            admitted.await()
            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, centerX + offset, centerY)
            down = true
            waitFor("east input") { SpooferProvider.currentPosition().bearing in 85f..95f }
            delay(250L) // Admit the service writer behind the test stall with an eastward input.
            touch(MotionEvent.ACTION_MOVE, centerX, centerY - offset)
            waitFor("north input") { SpooferProvider.currentPosition().bearing < 5f }
            val frameBefore = shell("getprop gsm.wrp.frame")
            val firstWriteFinished = CompletableDeferred<Unit>()
            nextBlocker = launch(Dispatchers.IO) {
                RuntimeConfigWriteCoordinator.commitIfLatest(RuntimeConfigWriteCoordinator.newGeneration()) {
                    firstWriteFinished.complete(Unit)
                    releaseNextWriter.await()
                    false
                }
            }
            delay(50L) // Queue a second barrier behind the already waiting service write.
            val releasedAt = SystemClock.uptimeMillis()
            releaseWriter.complete(Unit)
            blocker.join()
            firstWriteFinished.await() // Hold the first committed frame so sampling cannot miss a stale replay.
            var next = ""
            waitFor("first committed frame") {
                next = shell("getprop gsm.wrp.frame")
                next != frameBefore && next.split('|').size == 8
            }
            val turnLatency = SystemClock.uptimeMillis() - releasedAt
            assertEquals("A queued eastward frame must not replay after the turn", 0, next.split('|')[6].toInt())
            assertTrue("Latest turn took ${turnLatency}ms", turnLatency < 800L)
            releaseNextWriter.complete(Unit)
            nextBlocker.join()
            val stoppedAt = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_UP, centerX, centerY - offset)
            down = false
            waitFor("zero speed") { shell("getprop gsm.wrp.frame").split('|').getOrNull(7) == "0" }
            val releaseLatency = SystemClock.uptimeMillis() - stoppedAt
            assertTrue("Release took ${releaseLatency}ms", releaseLatency < 800L)
            android.util.Log.i("WRP_JOYSTICK_TEST", "latestTurnMs=$turnLatency releaseMs=$releaseLatency; staleFrameReplay=false")
        } finally {
            if (down) touch(MotionEvent.ACTION_CANCEL, centerX, centerY)
            releaseWriter.complete(Unit)
            releaseNextWriter.complete(Unit)
            blocker?.cancelAndJoin()
            nextBlocker?.cancelAndJoin()
            if (started) repository.stopSpoofing(context)
            settings.pureModeEnabled = oldPure
            settings.joystickDirectionMode = oldMode
            settings.setJoystickOverlayPosition(oldEnd, oldBottom)
            shell("cmd location set-location-enabled $locationEnabled")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
