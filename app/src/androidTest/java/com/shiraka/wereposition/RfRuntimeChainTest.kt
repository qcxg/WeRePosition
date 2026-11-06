package com.shiraka.wereposition

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.location.LocationListener
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shiraka.wereposition.data.db.AppDatabase
import com.shiraka.wereposition.data.db.CellDevice
import com.shiraka.wereposition.data.db.LocationCell
import com.shiraka.wereposition.data.db.LocationConnectedWifi
import com.shiraka.wereposition.data.db.LocationRecord
import com.shiraka.wereposition.data.db.LocationWifi
import com.shiraka.wereposition.data.db.WifiDevice
import com.shiraka.wereposition.data.repository.LocationRepository
import com.shiraka.wereposition.provider.SpooferProvider
import com.shiraka.wereposition.utils.EnvironmentRfResolver
import com.shiraka.wereposition.utils.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A temporary synthetic fixture, never a production source of RF records. */
@RunWith(AndroidJUnit4::class)
class RfRuntimeChainTest {
    @Test(timeout = 90_000)
    fun syntheticRfFlowsThroughDatabaseServiceAndFramework() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsManager(context)
        assertFalse("Stop an existing simulation before the test", settings.isSpoofingActive)

        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.ACCESS_FINE_LOCATION
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        var moduleScope: List<String>? = null
        repeat(10) {
            moduleScope = LocationApp.mService?.scope
            if (moduleScope == null) delay(500)
        }
        requireNotNull(moduleScope) { "LSPosed service was not connected" }
        assertTrue("Android framework missing from scope: $moduleScope", moduleScope!!.contains("android"))
        assertFalse(moduleScope!!.contains("com.google.android.gms"))
        assertFalse(moduleScope!!.contains("com.android.location.fused"))

        val database = AppDatabase.getDatabase(context)
        val dao = database.environmentDao()
        val repository = GlobalContext.get().get<LocationRepository>()
        val resolver = EnvironmentRfResolver(dao)
        // Outside the GCJ-02 conversion region so the framework callback can be
        // compared directly with the synthetic coordinate stored in Room.
        val lat = 35.6762
        val lng = 139.6503
        val suffix = ByteArray(4).also(SecureRandom()::nextBytes)
            .joinToString(":") { "%02X".format(it) }
        val bssid = "02:7A:$suffix"
        val cellKey = "WRP_TEST_${System.currentTimeMillis()}"
        var recordId = 0L
        var started = false

        try {
            recordId = dao.insertLocation(
                LocationRecord(lat = lat, lng = lng, placeName = "WRP TEST ONLY", remark = cellKey)
            )
            dao.insertWifiDevice(WifiDevice(bssid, "WRP_TEST_ONLY", 2412, "[WPA2-PSK-CCMP][ESS]"))
            dao.insertLocationWifi(LocationWifi(recordId, bssid, -63))
            dao.insertConnectedWifi(
                LocationConnectedWifi(recordId, bssid, "WRP_TEST_ONLY", "", bssid,
                    2412, 72, -63, "[WPA2-PSK-CCMP][ESS]", 1, 4)
            )
            dao.insertCellDevice(
                CellDevice(cellKey = cellKey, type = "LTE", mcc = 1, mnc = 1,
                    tac = 42, ci = 424242, pci = 42)
            )
            dao.insertLocationCell(LocationCell(recordId, cellKey, -82, true))

            val resolved = resolver.resolve(lat, lng)
            assertTrue(resolved.hasWifi)
            assertTrue(resolved.hasCell)
            assertEquals(bssid, JSONObject(resolved.wifiJson)
                .getJSONArray("nearbyWifi").getJSONObject(0).getString("bssid"))
            assertEquals(1, JSONArray(resolved.cellJson).length())
            assertFalse(resolver.resolve(lat + 0.002, lng).hasWifi)

            repository.startSpoofing(
                context = context, lat = lat, lng = lng, simMode = "STILL",
                simBearing = 0f, startTime = System.currentTimeMillis(),
                routePoints = emptyList(), isRouteMode = false,
                appCoordinateSystems = emptyMap(), wifiJson = resolved.wifiJson,
                cellJson = resolved.cellJson, mockWifi = true, mockCell = true,
                enableJitter = false
            )
            started = true

            context.contentResolver.query(SpooferProvider.RUNTIME_CONFIG_URI, null, null, null, null)
                .use { cursor ->
                    requireNotNull(cursor)
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("active")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("mock_wifi")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("mock_cell")))
                    assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("config_generation")) > 0L)
                    val wifi = JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("wifi_json")))
                    val cells = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("cell_json")))
                    assertEquals(bssid, wifi.getJSONArray("nearbyWifi").getJSONObject(0).getString("bssid"))
                    assertEquals(1, cells.length())
                }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val sawFrameworkFix = AtomicBoolean(false)
            val callbackCount = AtomicInteger(0)
            val closestDistanceMeters = AtomicReference(Float.POSITIVE_INFINITY)
            val listener = LocationListener { location ->
                callbackCount.incrementAndGet()
                val result = FloatArray(1)
                android.location.Location.distanceBetween(
                    lat, lng, location.latitude, location.longitude, result
                )
                closestDistanceMeters.updateAndGet { previous -> minOf(previous, result[0]) }
                if (kotlin.math.abs(location.latitude - lat) < 0.001 &&
                    kotlin.math.abs(location.longitude - lng) < 0.001) {
                    sawFrameworkFix.set(true)
                }
            }
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter(locationManager::isProviderEnabled)
            assertTrue("No enabled framework location providers", providers.isNotEmpty())
            try {
                providers.forEach { provider ->
                    locationManager.requestLocationUpdates(
                        provider, 0L, 0f, listener, Looper.getMainLooper()
                    )
                }
                for (attempt in 0 until 15) {
                    if (sawFrameworkFix.get()) break
                    delay(1_000)
                }
                assertTrue(
                    "No matching framework callback; count=${callbackCount.get()}, " +
                        "closestDistanceMeters=${closestDistanceMeters.get()}, providers=$providers",
                    sawFrameworkFix.get()
                )
            } finally {
                locationManager.removeUpdates(listener)
            }
        } finally {
            try {
                if (started || settings.isSpoofingActive || SpooferProvider.isActive) {
                    repository.stopSpoofing(context)
                }
            } finally {
                if (recordId != 0L) dao.deleteLocation(recordId)
                database.openHelper.writableDatabase.execSQL("DELETE FROM wifi_devices WHERE bssid = ?", arrayOf(bssid))
                database.openHelper.writableDatabase.execSQL("DELETE FROM cell_devices WHERE cellKey = ?", arrayOf(cellKey))
                activity.finish()
            }
        }
    }
}
