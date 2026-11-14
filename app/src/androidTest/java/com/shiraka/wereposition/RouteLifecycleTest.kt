package com.shiraka.wereposition

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.InputDevice
import android.view.MotionEvent
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.shiraka.wereposition.data.model.ActiveRouteSession
import com.shiraka.wereposition.data.model.RoutePlanStage
import com.shiraka.wereposition.data.model.SimMode
import com.shiraka.wereposition.data.repository.LocationRepository
import com.shiraka.wereposition.provider.SpooferProvider
import com.shiraka.wereposition.service.SpoofingService
import com.shiraka.wereposition.utils.SettingsManager
import com.shiraka.wereposition.viewmodel.MainViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class RouteLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private suspend fun waitFor(label: String, check: () -> Boolean) {
        assertTrue(label, withTimeoutOrNull(12_000L) { while (!check()) delay(50); true } == true)
    }
    private fun viewModel(activity: MainActivity): MainViewModel = MainActivity::class.java
        .getDeclaredMethod("getViewModel").apply { isAccessible = true }.invoke(activity) as MainViewModel
    private fun findLabel(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun click(label: String): Boolean {
        findLabel(label)?.let { node ->
            if ((node.text?.toString() == label || node.contentDescription?.toString() == label) && node.isEnabled) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                }
                return true
            }
        }
        return false
    }
    private fun mapView(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            mapView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
    private suspend fun map(activity: MainActivity): GoogleMap {
        val ready = CompletableDeferred<GoogleMap>()
        waitFor("map view") {
            var found = false
            instrumentation.runOnMainSync {
                mapView(activity.window.decorView)?.let { found = true; it.getMapAsync { map -> ready.complete(map) } }
            }
            found
        }
        return withTimeout(12_000) { ready.await() }
    }

    private suspend fun assertHomeReturnsToSameRoute(activity: MainActivity, vm: MainViewModel) {
        val points = vm.uiState.value.routePoints
        val token = SpooferProvider.runtimeLifecycleToken
        val startedAt = SpooferProvider.startTimestamp
        shell("input keyevent 4")
        waitFor("home tab") { click(activity.getString(R.string.fixed_simulation)) }
        waitFor("home route status") { findLabel(activity.getString(R.string.home_route_running)) != null }
        delay(400)
        instrumentation.runOnMainSync {
            assertNull("Running route must not mount the editable home map", mapView(activity.window.decorView))
        }
        assertEquals(RoutePlanStage.RUNNING, vm.uiState.value.routePlanStage)
        assertEquals(token, SpooferProvider.runtimeLifecycleToken)
        waitFor("return to route") { click(activity.getString(R.string.return_to_route)) }
        waitFor("existing route page") { findLabel(activity.getString(R.string.stop_planning)) != null }
        assertEquals(points, vm.uiState.value.routePoints)
        assertEquals(token, SpooferProvider.runtimeLifecycleToken)
        assertEquals(startedAt, SpooferProvider.startTimestamp)
    }

    @Test(timeout = 120_000)
    fun selectionKeepsViewportAndRecreatedUiRejoinsSameRoute() = runBlocking<Unit> {
        val settings = SettingsManager(context)
        // Navigation-only verification can reuse a user's existing run unchanged.
        if (InstrumentationRegistry.getArguments().getString("reuseActiveRoute") == "true") {
            val existing = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val vm = viewModel(existing)
            waitFor("existing route restored") {
                !vm.uiState.value.isInitializing && vm.uiState.value.routePlanStage == RoutePlanStage.RUNNING
            }
            waitFor("existing route page") { findLabel(existing.getString(R.string.stop_planning)) != null }
            assertHomeReturnsToSameRoute(existing, vm)
            return@runBlocking
        }
        assertFalse("Stop any existing simulation", settings.isSpoofingActive)
        val oldPure = settings.pureModeEnabled
        val oldLat = settings.lastSpoofedLat
        val oldLng = settings.lastSpoofedLng
        val oldLocation = shell("cmd location is-location-enabled")
        val repository = GlobalContext.get().get<LocationRepository>()
        var activity: MainActivity? = null
        fun launch(): MainActivity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            settings.pureModeEnabled = false
            shell("cmd location set-location-enabled true")
            activity = launch()
            val first = activity
            val vm = viewModel(first)
            waitFor("initialization") { !vm.uiState.value.isInitializing }
            delay(1_000)
            waitFor("joystick tab") { click(first.getString(R.string.nav_joystick)) }
            delay(350)
            waitFor("route planning entry") { click(first.getString(R.string.route_planning)) }
            waitFor("selecting") { vm.uiState.value.routePlanStage == RoutePlanStage.SELECTING }
            val googleMap = map(first)
            instrumentation.runOnMainSync { googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(3.14755, 101.69990), 15.25f)) }
            delay(500)
            repeat(3) { index ->
                val longitude = 101.69990 + index * 0.002
                instrumentation.runOnMainSync { googleMap.moveCamera(CameraUpdateFactory.newLatLng(LatLng(3.14755, longitude))) }
                waitFor("add point") { click(first.getString(R.string.confirm_point)) }
                waitFor("point count") { vm.uiState.value.routePoints.size == index + 1 }
                delay(600)
                instrumentation.runOnMainSync {
                    assertEquals("Selecting a point changed zoom", 15.25f, googleMap.cameraPosition.zoom, 0.01f)
                    assertEquals("Selecting a point changed center", longitude, googleMap.cameraPosition.target.longitude, 0.00001)
                }
            }
            repeat(3) { assertTrue("rapid add", click(first.getString(R.string.confirm_point))) }
            waitFor("rapid point count") { vm.uiState.value.routePoints.size == 6 }
            delay(600)
            instrumentation.runOnMainSync {
                assertEquals("Rapid point selection changed zoom", 15.25f, googleMap.cameraPosition.zoom, 0.01f)
            }
            instrumentation.runOnMainSync { vm.setRouteSimMode(SimMode.CUSTOM); vm.setCustomSpeedMs(2.3); vm.startRoutePlanning() }
            waitFor("route active") { vm.uiState.value.isSpoofingActive && SpoofingService.isRunning }
            val points = vm.uiState.value.routePoints
            val token = SpooferProvider.runtimeLifecycleToken
            val startedAt = SpooferProvider.startTimestamp
            assertHomeReturnsToSameRoute(first, vm)
            instrumentation.runOnMainSync { first.finish() }
            waitFor("old activity destroyed") { first.isDestroyed }
            assertTrue(SpooferProvider.isActive)
            activity = launch()
            val second = activity
            val restored = viewModel(second)
            waitFor("route state reattached") {
                !restored.uiState.value.isInitializing && restored.uiState.value.routePlanStage == RoutePlanStage.RUNNING
            }
            assertEquals(points, restored.uiState.value.routePoints)
            assertEquals(SimMode.CUSTOM, restored.uiState.value.routeSimMode)
            assertEquals(2.3, restored.uiState.value.customSpeedMs, 0.001)
            assertEquals(token, SpooferProvider.runtimeLifecycleToken)
            assertEquals(startedAt, SpooferProvider.startTimestamp)
            waitFor("route page restored") {
                findLabel(second.getString(R.string.stop_planning)) != null
            }
            android.util.Log.i("WRP_ROUTE_TEST", "viewport stable across 3 spaced + 3 rapid points; home hides editable map and returns to same route; UI recreated with same token/start/points/custom speed")
            // Optional external process-death driver: it kills this process in this
            // window, validates cold recovery, then stops the fixture and restores settings.
            if (InstrumentationRegistry.getArguments().getString("awaitHostKill") == "true") {
                context.filesDir.resolve("route-recovery-fixture.json").writeText(JSONObject().apply {
                    put("startedAt", startedAt)
                    put("oldPure", oldPure)
                    put("oldLat", oldLat)
                    put("oldLng", oldLng)
                    put("oldLocation", oldLocation)
                    put("pointCount", points.size)
                    put("speed", 2.3)
                }.toString())
                delay(30_000)
            }
        } finally {
            withContext(NonCancellable) { repository.stopSpoofing(context) }
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            settings.pureModeEnabled = oldPure
            settings.lastSpoofedLat = oldLat
            settings.lastSpoofedLng = oldLng
            shell("cmd location set-location-enabled $oldLocation")
        }
    }

    @Test
    fun routeRecoveryRejectsStoppedOldBootAndMalformedSnapshots() {
        val snapshot = JSONObject("""{"active":true,"is_route_mode":true,"boot_id":"test-boot","config_generation":20,"route_points":[{"lat":3.1,"lng":101.6},{"lat":3.2,"lng":101.7}],"sim_mode":"CUSTOM","sim_speed":2.3,"start_timestamp":123456} """)
        fun parse(boot: String = "test-boot", stop: Long = 19) = ActiveRouteSession.fromSnapshot(snapshot.toString(), boot, stop)
        assertNotNull(parse())
        assertNull(parse(stop = 20)); assertNull(parse(stop = 21)); assertNull(parse(boot = "older-boot"))
        snapshot.put("active", false); assertNull(parse()); snapshot.put("active", true)
        snapshot.put("sim_speed", 0); assertNull(parse()); snapshot.put("sim_speed", 2.3)
        snapshot.getJSONArray("route_points").getJSONObject(1).put("lat", 91); assertNull(parse())
        assertNull(ActiveRouteSession.fromSnapshot("malformed", "test-boot", 0))
    }
}
