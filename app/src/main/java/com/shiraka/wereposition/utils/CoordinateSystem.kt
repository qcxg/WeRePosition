package com.shiraka.wereposition.utils

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Only the coordinate entry boundary converts; runtime and persisted positions are WGS84. */
enum class CoordinateSystem { WGS84, GCJ02, BD09 }

data class GeoPoint(val latitude: Double, val longitude: Double)

object CoordinateConverter {
    fun fromWgs84(point: GeoPoint, system: CoordinateSystem): GeoPoint = when (system) {
        CoordinateSystem.WGS84 -> point
        CoordinateSystem.GCJ02 -> wgsToGcj(point)
        CoordinateSystem.BD09 -> gcjToBd(wgsToGcj(point))
    }

    fun toWgs84(point: GeoPoint, system: CoordinateSystem): GeoPoint = when (system) {
        CoordinateSystem.WGS84 -> point
        CoordinateSystem.GCJ02 -> gcjToWgs(point)
        CoordinateSystem.BD09 -> gcjToWgs(bdToGcj(point))
    }

    private fun outsideChina(p: GeoPoint) =
        p.longitude < 72.004 || p.longitude > 137.8347 || p.latitude < 0.8293 || p.latitude > 55.8271 ||
            // The usual GCJ bounding box also contains Taiwan, Hong Kong and Macau.
            (p.latitude in 21.5..25.5 && p.longitude in 119.0..123.0) ||
            (p.latitude in 22.1..22.6 && p.longitude in 113.8..114.5) ||
            (p.latitude in 22.0..22.3 && p.longitude in 113.5..113.7)

    private fun wgsToGcj(p: GeoPoint): GeoPoint {
        if (outsideChina(p)) return p
        val x = p.longitude - 105.0
        val y = p.latitude - 35.0
        var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        dLat += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        dLat += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        dLat += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        dLon += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        dLon += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        dLon += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        val rad = p.latitude / 180.0 * PI
        val magic = 1.0 - EE * sin(rad) * sin(rad)
        val root = sqrt(magic)
        dLat = dLat * 180.0 / ((A * (1.0 - EE)) / (magic * root) * PI)
        dLon = dLon * 180.0 / (A / root * cos(rad) * PI)
        return GeoPoint(p.latitude + dLat, p.longitude + dLon)
    }

    private fun gcjToWgs(p: GeoPoint): GeoPoint {
        if (outsideChina(p)) return p
        var guess = p
        repeat(5) {
            val mapped = wgsToGcj(guess)
            guess = GeoPoint(guess.latitude + p.latitude - mapped.latitude,
                guess.longitude + p.longitude - mapped.longitude)
        }
        return guess
    }

    private fun gcjToBd(p: GeoPoint): GeoPoint {
        val x = p.longitude
        val y = p.latitude
        val z = sqrt(x * x + y * y) + 0.00002 * sin(y * X_PI)
        val theta = kotlin.math.atan2(y, x) + 0.000003 * cos(x * X_PI)
        return GeoPoint(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }

    private fun bdToGcj(p: GeoPoint): GeoPoint {
        val x = p.longitude - 0.0065
        val y = p.latitude - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = kotlin.math.atan2(y, x) - 0.000003 * cos(x * X_PI)
        var guess = GeoPoint(z * sin(theta), z * cos(theta))
        repeat(4) {
            val mapped = gcjToBd(guess)
            guess = GeoPoint(guess.latitude + p.latitude - mapped.latitude,
                guess.longitude + p.longitude - mapped.longitude)
        }
        return guess
    }

    private const val PI = kotlin.math.PI
    private const val X_PI = PI * 3000.0 / 180.0
    private const val A = 6378245.0
    private const val EE = 0.006693421622965943
}
