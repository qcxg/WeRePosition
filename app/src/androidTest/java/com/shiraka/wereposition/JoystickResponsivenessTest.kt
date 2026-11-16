package com.shiraka.wereposition

import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
        val oldPerApp = settings.perAppLocationEnabled
        val oldLat = settings.lastSpoofedLat
        val oldLng = settings.lastSpoofedLng
        val fixes = java.util.concurrent.CopyOnWriteArrayList<Location>()
        val locationManager = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { fixes.add(Location(it)) }
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
        fun multiTouch(action: Int, ids: List<Int>, points: List<Pair<Float, Float>>) {
            val properties = ids.map { id -> MotionEvent.PointerProperties().apply {
                this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = points.map { point -> MotionEvent.PointerCoords().apply {
                x = point.first; y = point.second; pressure = 1f; size = 1f
            } }.toTypedArray()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, ids.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        try {
            settings.pureModeEnabled = false
            settings.perAppLocationEnabled = false
            settings.joystickDirectionMode = "NORTH"
            settings.setJoystickOverlayPosition(34, 176)
            shell("cmd location set-location-enabled true")
            repository.startSpoofing(context, 3.14755, 101.69990, "STILL", 0f,
                System.currentTimeMillis(), emptyList(), false, mockWifi = false, mockCell = false, enableJitter = true)
            started = true
            context.startService(Intent(context, SpoofingService::class.java).apply {
                action = SpoofingService.ACTION_JOYSTICK
                putExtra(SpoofingService.EXTRA_JOYSTICK_VISIBLE, true)
            })
            waitFor("overlay snapshot") { SpoofingService.isJoystickVisible && shell("getprop gsm.wrp.joystick_mode") == "true" }
            context.contentResolver.query(SpooferProvider.RUNTIME_CONFIG_URI, null, null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Provider fallback lost joystick mode", 1, it.getInt(it.getColumnIndexOrThrow("joystick_mode")))
                assertEquals("Provider fallback re-enabled random drift", 0, it.getInt(it.getColumnIndexOrThrow("enable_jitter")))
            }
            instrumentation.runOnMainSync {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            }
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
            val stoppedAtNanos = SystemClock.elapsedRealtimeNanos()
            touch(MotionEvent.ACTION_UP, centerX, centerY - offset)
            down = false
            waitFor("zero speed") { shell("getprop gsm.wrp.frame").split('|').getOrNull(7) == "0" }
            val releaseLatency = SystemClock.uptimeMillis() - stoppedAt
            assertTrue("Release took ${releaseLatency}ms", releaseLatency < 800L)
            val stoppedPosition = SpooferProvider.currentPosition()
            delay(1_300L)
            assertEquals(stoppedPosition.latitude, SpooferProvider.currentPosition().latitude, 0.00000001)
            assertEquals(stoppedPosition.longitude, SpooferProvider.currentPosition().longitude, 0.00000001)
            waitFor("native zero-speed fix after release") {
                fixes.any {
                    val distance = FloatArray(1)
                    Location.distanceBetween(it.latitude, it.longitude,
                        stoppedPosition.latitude, stoppedPosition.longitude, distance)
                    it.elapsedRealtimeNanos > stoppedAtNanos + 800_000_000L && it.speed == 0f && distance[0] < 0.5f
                }
            }

            // Returning to the wheel centre must send zero even while the pointer is down.
            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, centerX + offset, centerY); down = true
            waitFor("moving before centre") { SpooferProvider.currentPosition().speed > 0f }
            touch(MotionEvent.ACTION_MOVE, centerX, centerY)
            waitFor("centre stops without ACTION_UP") { shell("getprop gsm.wrp.frame").split('|').getOrNull(7) == "0" }
            val centred = SpooferProvider.currentPosition()
            delay(700L)
            assertEquals(centred.latitude, SpooferProvider.currentPosition().latitude, 0.00000001)
            assertEquals(centred.longitude, SpooferProvider.currentPosition().longitude, 0.00000001)
            touch(MotionEvent.ACTION_MOVE, centerX, centerY - offset)
            waitFor("resume after centre") { SpooferProvider.currentPosition().speed > 0f }
            touch(MotionEvent.ACTION_CANCEL, centerX, centerY); down = false
            waitFor("cancel stops") { shell("getprop gsm.wrp.frame").split('|').getOrNull(7) == "0" }

            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, centerX + offset, centerY); down = true
            waitFor("moving before multiple pointers") { SpooferProvider.currentPosition().speed > 0f }
            val bothPoints = listOf((centerX + offset) to centerY, centerX to centerY)
            multiTouch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(0, 1), bothPoints)
            multiTouch(MotionEvent.ACTION_POINTER_UP, listOf(0, 1), bothPoints)
            waitFor("owner pointer up stops with second finger held") { shell("getprop gsm.wrp.frame").split('|').getOrNull(7) == "0" }
            multiTouch(MotionEvent.ACTION_MOVE, listOf(1), listOf(centerX to (centerY - offset)))
            delay(250L)
            assertEquals("Unowned second finger restarted motion", 0f, SpooferProvider.currentPosition().speed, 0f)
            multiTouch(MotionEvent.ACTION_UP, listOf(1), listOf(centerX to (centerY - offset))); down = false

            // A full settings refresh must also sample after coordinator admission.
            val fullAdmitted = CompletableDeferred<Unit>()
            val releaseFull = CompletableDeferred<Unit>()
            val fullBlocker = launch(Dispatchers.IO) {
                RuntimeConfigWriteCoordinator.commitIfLatest(RuntimeConfigWriteCoordinator.newGeneration()) {
                    fullAdmitted.complete(Unit)
                    releaseFull.await()
                    false
                }
            }
            fullAdmitted.await()
            val snapshotBefore = shell("getprop gsm.wrp.snapshot_generation")
            val oldSatelliteCount = SpooferProvider.satelliteCount
            try {
                downTime = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, centerX + offset, centerY); down = true
                waitFor("moving before full refresh") { SpooferProvider.currentPosition().speed > 0f }
                SpooferProvider.satelliteCount = oldSatelliteCount + 1
                context.startService(Intent(context, SpoofingService::class.java).setAction(SpoofingService.ACTION_REFRESH_CONFIG))
                delay(250L)
                touch(MotionEvent.ACTION_UP, centerX, centerY); down = false
                releaseFull.complete(Unit)
                fullBlocker.join()
                waitFor("full refresh after release") {
                    shell("getprop gsm.wrp.snapshot_generation") != snapshotBefore
                }
                assertEquals("Full refresh replayed pre-release speed", "0", shell("getprop gsm.wrp.frame").split('|').getOrNull(7))
            } finally {
                releaseFull.complete(Unit)
                fullBlocker.cancelAndJoin()
                SpooferProvider.satelliteCount = oldSatelliteCount
            }

            // A paused input thread must expire velocity independently on the service IO loop.
            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, centerX + offset, centerY); down = true
            waitFor("moving before input stall") { SpooferProvider.currentPosition().speed > 0f }
            val inputBlocked = CompletableDeferred<Unit>()
            val stalledInput = launch(Dispatchers.Default) {
                instrumentation.runOnMainSync { inputBlocked.complete(Unit); SystemClock.sleep(1_300L) }
            }
            inputBlocked.await()
            delay(800L)
            assertEquals("Input lease did not stop the service", 0f, SpooferProvider.currentPosition().speed, 0f)
            waitFor("input lease publishes zero") {
                shell("getprop gsm.wrp.frame").split('|').getOrNull(7) == "0"
            }
            stalledInput.join()
            touch(MotionEvent.ACTION_UP, centerX, centerY); down = false
            waitFor("stopped after input resumes") { shell("getprop gsm.wrp.frame").split('|').getOrNull(7) == "0" }
            val lockProcess = ProcessBuilder("su", "-c",
                "(flock -x 9 && printf 'LOCKED\\n' && sleep 1.5) 9>/data/local/tmp/wereposition_runtime.lock").start()
            try {
                assertEquals("LOCKED", withContext(Dispatchers.IO) { lockProcess.inputStream.bufferedReader().readLine() })
                val frameBeforeLock = shell("getprop gsm.wrp.frame")
                downTime = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, centerX + offset, centerY); down = true
                delay(250L)
                touch(MotionEvent.ACTION_UP, centerX, centerY); down = false
                val stoppedBehindRootLock = SpooferProvider.currentPosition()
                withContext(Dispatchers.IO) { assertTrue(lockProcess.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) }
                val rootUnlockedAt = SystemClock.uptimeMillis()
                waitFor("fresh zero frame after root lock") {
                    val raw = shell("getprop gsm.wrp.frame")
                    raw != frameBeforeLock && raw.split('|').getOrNull(7) == "0"
                }
                assertTrue("Root lock contention entered failure backoff", SystemClock.uptimeMillis() - rootUnlockedAt < 800L)
                val committed = shell("getprop gsm.wrp.frame").split('|')
                assertEquals(stoppedBehindRootLock.latitude, committed[4].toDouble() / 10_000_000, 0.00000011)
                assertEquals(stoppedBehindRootLock.longitude, committed[5].toDouble() / 10_000_000, 0.00000011)
            } finally { lockProcess.destroy() }
            repository.startSpoofing(context, 3.15755, 101.70990, "STILL", 0f,
                System.currentTimeMillis(), emptyList(), false, mockWifi = false, mockCell = false, enableJitter = false)
            waitFor("new fixed run supersedes joystick") {
                !SpoofingService.isJoystickVisible && shell("getprop gsm.wrp.joystick_mode") == "false"
            }
            assertEquals("Old overlay replaced the new anchor", 3.15755, SpooferProvider.anchorPosition().latitude, 0.00000001)
            assertEquals("Old overlay replaced the new anchor", 101.70990, SpooferProvider.anchorPosition().longitude, 0.00000001)
            android.util.Log.i("WRP_JOYSTICK_TEST", "latestTurnMs=$turnLatency releaseMs=$releaseLatency; nativeStop/centre/cancel/fullRefresh/inputLease=PASS; staleFrameReplay=false")
        } finally {
            if (down) touch(MotionEvent.ACTION_CANCEL, centerX, centerY)
            releaseWriter.complete(Unit)
            releaseNextWriter.complete(Unit)
            blocker?.cancelAndJoin()
            nextBlocker?.cancelAndJoin()
            if (started) repository.stopSpoofing(context)
            instrumentation.runOnMainSync { locationManager.removeUpdates(listener) }
            settings.pureModeEnabled = oldPure
            settings.joystickDirectionMode = oldMode
            settings.perAppLocationEnabled = oldPerApp
            settings.lastSpoofedLat = oldLat
            settings.lastSpoofedLng = oldLng
            settings.setJoystickOverlayPosition(oldEnd, oldBottom)
            shell("cmd location set-location-enabled $locationEnabled")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
