package com.shiraka.wereposition

import android.Manifest
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shiraka.wereposition.data.model.SpecifiedBluetoothDevice
import com.shiraka.wereposition.data.repository.LocationRepository
import com.shiraka.wereposition.utils.SettingsManager
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/** Uses a separately scoped, real Android client. No direct invocation of hook helpers. */
@RunWith(AndroidJUnit4::class)
class ClassicBluetoothRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val probe = "com.shiraka.wereposition.btprobe"
    private val a2dpAddress = "02:57:52:50:00:01"
    private val headsetAddress = "02:57:52:50:00:02"
    private val pairedAddress = "02:57:52:50:00:03"

    private fun shell(command: String): String = instrumentation.uiAutomation
        .executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }

    private suspend fun snapshot(label: String): JSONObject {
        shell("am force-stop $probe")
        shell("run-as $probe rm -f files/report.json")
        shell("am start -W -n $probe/.ProbeActivity --es phase $label")
        val result = withTimeout(15_000L) {
            var report: JSONObject? = null
            while (report == null) {
                delay(300)
                report = runCatching { JSONObject(shell("run-as $probe cat files/report.json")) }.getOrNull()
            }
            report
        }
        assertFalse("Probe API error: $result", result.has("error"))
        assertTrue("Enable Bluetooth before running this test", result.getBoolean("enabled"))
        File(context.filesDir, "bluetooth-$label.json").writeText(result.toString(2))
        return result
    }

    private fun addresses(report: JSONObject, list: String = "bonded"): Set<String> =
        report.getJSONArray(list).let { rows -> (0 until rows.length()).map {
            rows.getJSONObject(it).getString("address")
        }.toSet() }

    @Test(timeout = 180_000)
    fun specifiedDevicesAreAdditiveAndRevertOnDisableAndStop() = runBlocking {
        org.junit.Assume.assumeTrue("Opt in with -e bluetoothProbe true after manual fixture preparation",
            InstrumentationRegistry.getArguments().getString("bluetoothProbe") == "true")
        val settings = SettingsManager(context)
        assertFalse("Stop the existing simulation first", settings.isSpoofingActive)
        // Explicitly fail before altering state if the independent APK is not installed.
        context.packageManager.getApplicationInfo(probe, 0)
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        shell("pm grant $probe android.permission.BLUETOOTH_CONNECT")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val service = withTimeout(10_000) {
            while (LocationApp.mService == null) delay(100)
            requireNotNull(LocationApp.mService)
        }
        delay(2_000) // Let activity restoration finish before starting an isolated runtime.
        assertTrue("Manually add the separate WRP Bluetooth Test client to LSPosed scope before running; " +
            "this test never changes module scope", probe in service.scope)
        val previousEnabled = settings.specifiedBluetoothEnabled
        val previousDevices = settings.specifiedBluetoothDevices
        val previousWifi = settings.specifiedWifiEnabled
        val previousPure = settings.pureModeEnabled
        val previousLocationEnabled = shell("cmd location is-location-enabled").trim() == "true"
        val repository = GlobalContext.get().get<LocationRepository>()
        var started = false
        try {
            val baseline = snapshot("baseline")
            assertFalse(addresses(baseline).contains(a2dpAddress))
            settings.pureModeEnabled = false
            shell("cmd location set-location-enabled true")
            settings.specifiedWifiEnabled = false
            settings.specifiedBluetoothEnabled = true
            settings.specifiedBluetoothDevices = listOf(
                SpecifiedBluetoothDevice(a2dpAddress, "WRP A2DP TEST", "WRP Alias", 1028, true, false,
                    listOf("0000110b-0000-1000-8000-00805f9b34fb")),
                SpecifiedBluetoothDevice(headsetAddress, "WRP HEADSET TEST", "", 1028, false, true),
                SpecifiedBluetoothDevice(pairedAddress, "WRP PAIRED TEST", "", 0, false, false)
            )
            instrumentation.runOnMainSync { activity.startActivity(Intent(context, MainActivity::class.java)) }
            delay(500)
            repository.startSpoofing(context, 24.966021, 121.377552, "STILL", 0f,
                System.currentTimeMillis(), emptyList(), false, mockWifi = false, mockCell = false, enableJitter = false)
            started = true
            delay(1_500)
            val active = snapshot("active")
            assertTrue("Real paired devices disappeared", addresses(active).containsAll(addresses(baseline)))
            assertEquals(addresses(baseline).size + 3, addresses(active).size)
            val rows = active.getJSONArray("bonded")
            val specified = (0 until rows.length()).map { rows.getJSONObject(it) }
                .first { it.getString("address") == a2dpAddress }
            assertEquals("WRP A2DP TEST", specified.getString("name"))
            assertEquals("WRP Alias", specified.getString("alias"))
            assertEquals(12, specified.getInt("bondState"))
            assertEquals(1, specified.getInt("type"))
            assertEquals(1028, specified.getInt("deviceClass"))
            assertEquals("0000110b-0000-1000-8000-00805f9b34fb", specified.getJSONArray("uuids").getString(0))
            for ((profile, address) in listOf("a2dp" to a2dpAddress, "headset" to headsetAddress)) {
                val result = active.getJSONObject(profile)
                val base = baseline.getJSONObject(profile)
                assertTrue("No $profile proxy", result.getBoolean("proxyAvailable"))
                assertEquals(addresses(base, "connected") + address, addresses(result, "connected"))
                assertEquals(addresses(base, "matchingConnected") + address, addresses(result, "matchingConnected"))
                assertEquals(addresses(base, "matchingDisconnected"), addresses(result, "matchingDisconnected"))
                assertEquals(2, result.getJSONObject("states").getInt(address))
                assertEquals(0, result.getJSONObject("states").getInt(pairedAddress))
                assertEquals(2, result.getInt("adapterState"))
            }
            settings.specifiedBluetoothEnabled = false
            delay(3_000)
            assertEquals(addresses(baseline), addresses(snapshot("disabled")))
            settings.specifiedBluetoothEnabled = true
            delay(3_000)
            assertTrue(addresses(snapshot("reenabled")).contains(a2dpAddress))
            repository.stopSpoofing(context)
            started = false
            delay(1_000)
            assertEquals(addresses(baseline), addresses(snapshot("stopped")))
        } finally {
            if (started) repository.stopSpoofing(context)
            settings.specifiedBluetoothEnabled = previousEnabled
            settings.specifiedBluetoothDevices = previousDevices
            settings.specifiedWifiEnabled = previousWifi
            settings.pureModeEnabled = previousPure
            shell("cmd location set-location-enabled $previousLocationEnabled")
            shell("am force-stop $probe")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
