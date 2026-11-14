package com.shiraka.wereposition.data.model

import org.json.JSONArray
import org.json.JSONObject

/** A route can reconnect only to an accepted active snapshot from this boot. */
data class ActiveRouteSession(
    val points: List<RoutePoint>,
    val mode: SimMode,
    val speedMs: Float,
    val startedAt: Long
) {
    companion object {
        fun parsePoints(json: String): List<RoutePoint> = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val point = array.getJSONObject(index)
                RoutePoint(point.getDouble("lat"), point.getDouble("lng")).also {
                    require(it.lat.isFinite() && it.lat in -90.0..90.0)
                    require(it.lng.isFinite() && it.lng in -180.0..180.0)
                }
            }
        }.getOrDefault(emptyList())

        fun fromSnapshot(json: String, bootId: String, stopGeneration: Long): ActiveRouteSession? = runCatching {
            val snapshot = JSONObject(json)
            require(bootId.isNotBlank() && snapshot.getString("boot_id") == bootId)
            require(snapshot.getBoolean("active") && snapshot.getBoolean("is_route_mode"))
            require(stopGeneration >= 0 && snapshot.getLong("config_generation") > stopGeneration)
            val points = parsePoints(snapshot.getJSONArray("route_points").toString())
            require(points.size >= 2)
            val mode = SimMode.valueOf(snapshot.getString("sim_mode"))
            val speed = if (mode == SimMode.CUSTOM) snapshot.getDouble("sim_speed").toFloat() else mode.speedMs.toFloat()
            require(speed.isFinite() && speed in 0.1f..100f)
            val startedAt = snapshot.getLong("start_timestamp")
            require(startedAt > 0)
            ActiveRouteSession(points, mode, speed, startedAt)
        }.getOrNull()
    }
}
