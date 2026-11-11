package com.shiraka.wereposition.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.shiraka.wereposition.data.model.RoutePoint
import com.shiraka.wereposition.data.model.SavedLocation
import com.shiraka.wereposition.data.model.SavedRoute
import com.shiraka.wereposition.data.model.SpecifiedBluetoothDevice
import com.shiraka.wereposition.data.model.SpecifiedWifiConnection
import org.json.JSONArray
import org.json.JSONObject

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var joystickSpeedMs: Float
        get() = prefs.getFloat("joystick_speed_ms", 3f).coerceIn(joystickMinSpeedMs, joystickMaxSpeedMs)
        set(value) = prefs.edit().putFloat(
            "joystick_speed_ms", value.coerceIn(joystickMinSpeedMs, joystickMaxSpeedMs)
        ).apply()

    val joystickMinSpeedMs: Float
        get() = prefs.getFloat("joystick_min_speed_ms", 0.5f).coerceIn(0.5f, 19.5f)

    val joystickMaxSpeedMs: Float
        get() = prefs.getFloat("joystick_max_speed_ms", 10f).coerceIn(joystickMinSpeedMs + 0.5f, 20f)

    fun setJoystickSpeedBounds(minSpeedMs: Float, maxSpeedMs: Float) {
        val min = minSpeedMs.coerceIn(0.5f, 19.5f)
        val max = maxSpeedMs.coerceIn(min + 0.5f, 20f)
        prefs.edit()
            .putFloat("joystick_min_speed_ms", min)
            .putFloat("joystick_max_speed_ms", max)
            .putFloat("joystick_speed_ms", joystickSpeedMs.coerceIn(min, max))
            .apply()
    }

    var joystickDirectionMode: String
        get() = prefs.getString("joystick_direction_mode", "NORTH")?.takeIf {
            it == "NORTH" || it == "DEVICE"
        } ?: "NORTH"
        set(value) = prefs.edit().putString(
            "joystick_direction_mode", if (value == "DEVICE") "DEVICE" else "NORTH"
        ).apply()

    val joystickOverlayEndDp: Int
        get() = prefs.getInt("joystick_overlay_end_dp", 34)

    val joystickOverlayBottomDp: Int
        get() = prefs.getInt("joystick_overlay_bottom_dp", 176)

    var joystickOverlayLocked: Boolean
        get() = prefs.getBoolean("joystick_overlay_locked", false)
        set(value) = prefs.edit().putBoolean("joystick_overlay_locked", value).apply()

    fun setJoystickOverlayPosition(endDp: Int, bottomDp: Int) {
        prefs.edit()
            .putInt("joystick_overlay_end_dp", endDp)
            .putInt("joystick_overlay_bottom_dp", bottomDp)
            .apply()
    }

    var isDarkMode: Boolean
        get() = prefs.getBoolean("is_dark_mode", true)
        set(value) = prefs.edit().putBoolean("is_dark_mode", value).apply()

    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(value) = prefs.edit().putString("language", value).apply()

    var isLanguageSet: Boolean
        get() = prefs.getBoolean("is_language_set", false)
        set(value) = prefs.edit().putBoolean("is_language_set", value).apply()


    var googleApiKey: String
        get() = prefs.getString("google_api_key", "") ?: ""
        set(value) = prefs.edit().putString("google_api_key", value).apply()

    var wigleApiToken: String
        get() = prefs.getString("wigle_api_token", "") ?: ""
        set(value) = prefs.edit().putString("wigle_api_token", value).apply()

    var opencellidApiToken: String
        get() = prefs.getString("opencellid_api_token", "") ?: ""
        set(value) = prefs.edit().putString("opencellid_api_token", value).apply()

    var mapType: String
        get() = prefs.getString("map_type", "NORMAL") ?: "NORMAL"
        set(value) = prefs.edit().putString("map_type", value).apply()

    var ignoredVersion: String
        get() = prefs.getString("ignored_version", "") ?: ""
        set(value) = prefs.edit().putString("ignored_version", value).apply()

    var isSpoofingActive: Boolean
        get() = prefs.getBoolean("is_spoofing_active", false)
        set(value) = prefs.edit().putBoolean("is_spoofing_active", value).apply()

    /**
     * An APK replacement cannot reload hook code already resident in system_server/GMS.
     * The package-replaced receiver may be killed immediately after returning, so this
     * safety marker must be persisted synchronously before the process can disappear.
     */
    var moduleRestartRequired: Boolean
        get() = prefs.getBoolean("module_restart_required", false)
        @SuppressLint("ApplySharedPref")
        set(value) {
            prefs.edit().putBoolean("module_restart_required", value).commit()
        }

    var lastSpoofedLat: String
        get() = prefs.getString("last_spoofed_lat", "0") ?: "0"
        set(value) = prefs.edit().putString("last_spoofed_lat", value).apply()

    var lastSpoofedLng: String
        get() = prefs.getString("last_spoofed_lng", "0") ?: "0"
        set(value) = prefs.edit().putString("last_spoofed_lng", value).apply()

    var mockWifi: Boolean
        get() = prefs.getBoolean("mock_wifi", true)
        set(value) = prefs.edit().putBoolean("mock_wifi", value).apply()

    var mockCell: Boolean
        get() = prefs.getBoolean("mock_cell", true)
        set(value) = prefs.edit().putBoolean("mock_cell", value).apply()

    var syntheticRfEnabled: Boolean
        get() = prefs.getBoolean("synthetic_rf_enabled", false)
        set(value) = prefs.edit().putBoolean("synthetic_rf_enabled", value).apply()

    var syntheticWifiCount: Int
        get() = prefs.getInt("synthetic_wifi_count", 3).coerceIn(0, 8)
        set(value) = prefs.edit().putInt("synthetic_wifi_count", value.coerceIn(0, 8)).apply()

    var syntheticCellCount: Int
        get() = prefs.getInt("synthetic_cell_count", 1).coerceIn(0, 3)
        set(value) = prefs.edit().putInt("synthetic_cell_count", value.coerceIn(0, 3)).apply()

    val syntheticRfOptions: SyntheticRfOptions
        get() = SyntheticRfOptions(
            syntheticRfEnabled, syntheticWifiCount, syntheticCellCount
        )

    var specifiedWifiEnabled: Boolean
        get() = prefs.getBoolean("specified_wifi_enabled", false)
        set(value) = prefs.edit().putBoolean("specified_wifi_enabled", value).apply()

    var specifiedWifi: SpecifiedWifiConnection
        get() = SpecifiedWifiConnection.fromJson(prefs.getString("specified_wifi", "{}") ?: "{}")
        set(value) = prefs.edit().putString("specified_wifi", value.toJson().toString()).apply()

    var specifiedBluetoothEnabled: Boolean
        get() = prefs.getBoolean("specified_bluetooth_enabled", false)
        set(value) = prefs.edit().putBoolean("specified_bluetooth_enabled", value).apply()

    var specifiedBluetoothDevices: List<SpecifiedBluetoothDevice>
        get() = SpecifiedBluetoothDevice.listFromJson(prefs.getString("specified_bluetooth", "[]") ?: "[]")
        set(value) = prefs.edit().putString(
            "specified_bluetooth", SpecifiedBluetoothDevice.listToJson(value).toString()
        ).apply()

    var mockBluetooth: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) = prefs.edit().putBoolean("mock_bluetooth", false).apply()

    var enableJitter: Boolean
        get() = prefs.getBoolean("enable_jitter", true)
        set(value) = prefs.edit().putBoolean("enable_jitter", value).apply()

    var jitterRadiusMeters: Int
        get() = prefs.getInt("jitter_radius_meters", 30).coerceIn(1, 80)
        set(value) = prefs.edit().putInt("jitter_radius_meters", value.coerceIn(1, 80)).apply()

    var jitterSpeed: String
        get() = prefs.getString("jitter_speed", "MEDIUM") ?: "MEDIUM"
        set(value) = prefs.edit().putString("jitter_speed", value).apply()

    var signalJitterEnabled: Boolean
        get() = prefs.getBoolean("signal_jitter_enabled", true)
        set(value) = prefs.edit().putBoolean("signal_jitter_enabled", value).apply()

    var signalJitterLevel: Int
        get() = prefs.getInt("signal_jitter_level", 40).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("signal_jitter_level", value.coerceIn(0, 100)).apply()

    var wifiConnectionMode: String
        get() = prefs.getString("wifi_connection_mode", "FIXED") ?: "FIXED"
        set(value) = prefs.edit().putString("wifi_connection_mode", value).apply()

    var altitude: String
        get() = prefs.getString("altitude", "0.0") ?: "0.0"
        set(value) = prefs.edit().putString("altitude", value).apply()

    var satelliteCount: String
        get() = prefs.getString("satellite_count", "20") ?: "20"
        set(value) = prefs.edit().putString("satellite_count", value).apply()

    fun getSavedLocations(): List<SavedLocation> {
        return getLocationList("saved_locations")
    }

    fun getRecentLocations(): List<SavedLocation> {
        val list = getLocationList("recent_locations")
        val limited = list.take(recentLocationDisplayCount)
        if (limited.size != list.size) saveLocationList("recent_locations", limited)
        return limited
    }

    var recentLocationDisplayCount: Int
        get() = prefs.getInt("recent_location_display_count", 7).coerceIn(1, 20)
        set(value) {
            val limit = value.coerceIn(1, 20)
            prefs.edit().putInt("recent_location_display_count", limit).apply()
            saveLocationList("recent_locations", getLocationList("recent_locations").take(limit))
        }

    var perAppLocationEnabled: Boolean
        get() = prefs.getBoolean("per_app_location_enabled", false)
        set(value) = prefs.edit().putBoolean("per_app_location_enabled", value).apply()

    var pureModeEnabled: Boolean
        get() = prefs.getBoolean("pure_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("pure_mode_enabled", value).apply()

    var sampleEnvironmentSeeded: Boolean
        get() = prefs.getBoolean("sample_environment_seeded", false)
        set(value) = prefs.edit().putBoolean("sample_environment_seeded", value).apply()

    var locationAllowedApps: Set<String>
        get() = prefs.getStringSet("location_allowed_apps", emptySet())?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet("location_allowed_apps", value.toSet()).apply()

    private fun getLocationList(key: String): List<SavedLocation> {
        val jsonString = prefs.getString(key, "[]") ?: "[]"
        val list = mutableListOf<SavedLocation>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(SavedLocation(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lng")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addSavedLocation(location: SavedLocation) {
        val list = getSavedLocations().toMutableList()
        list.add(location)
        saveLocationList("saved_locations", list)
    }

    fun addRecentLocation(location: SavedLocation) {
        val list = getRecentLocations()
            .filterNot { it.lat == location.lat && it.lng == location.lng }
            .toMutableList()
        list.add(0, location)
        saveLocationList("recent_locations", list.take(recentLocationDisplayCount))
    }

    fun removeSavedLocation(location: SavedLocation) {
        val list = getSavedLocations().toMutableList()
        list.removeAll { it.lat == location.lat && it.lng == location.lng }
        saveLocationList("saved_locations", list)
    }

    fun removeRecentLocation(location: SavedLocation) {
        val list = getRecentLocations().toMutableList()
        list.removeAll { it.lat == location.lat && it.lng == location.lng }
        saveLocationList("recent_locations", list)
    }

    private fun saveLocationList(key: String, list: List<SavedLocation>) {
        val jsonArray = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("name", it.name)
            obj.put("lat", it.lat)
            obj.put("lng", it.lng)
            jsonArray.put(obj)
        }
        prefs.edit().putString(key, jsonArray.toString()).apply()
    }

    fun getSavedRoutes(): List<SavedRoute> {
        val jsonString = prefs.getString("saved_routes", "[]") ?: "[]"
        val list = mutableListOf<SavedRoute>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pointsArray = obj.getJSONArray("points")
                val points = (0 until pointsArray.length()).map { j ->
                    val p = pointsArray.getJSONObject(j)
                    RoutePoint(p.getDouble("lat"), p.getDouble("lng"))
                }
                list.add(SavedRoute(obj.getString("name"), points))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addSavedRoute(route: SavedRoute) {
        val list = getSavedRoutes().toMutableList()
        list.add(route)
        saveRouteList(list)
    }

    fun removeSavedRoute(route: SavedRoute) {
        val list = getSavedRoutes().toMutableList()
        list.removeAll { it.name == route.name }
        saveRouteList(list)
    }

    private fun saveRouteList(list: List<SavedRoute>) {
        val jsonArray = JSONArray()
        list.forEach { route ->
            val obj = JSONObject()
            obj.put("name", route.name)
            val pointsArray = JSONArray()
            route.points.forEach { p ->
                val pObj = JSONObject()
                pObj.put("lat", p.lat)
                pObj.put("lng", p.lng)
                pointsArray.put(pObj)
            }
            obj.put("points", pointsArray)
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_routes", jsonArray.toString()).apply()
    }

}
