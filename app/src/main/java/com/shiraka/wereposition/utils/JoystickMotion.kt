package com.shiraka.wereposition.utils

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** WGS84 great-circle step for both the app and floating joystick. */
object JoystickMotion {
    fun advance(lat: Double, lng: Double, bearing: Double, distanceMeters: Double): Pair<Double, Double> {
        val angularDistance = distanceMeters / 6_378_137.0
        val bearingRad = Math.toRadians(bearing)
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)
        val nextLat = asin(sin(latRad) * cos(angularDistance) +
            cos(latRad) * sin(angularDistance) * cos(bearingRad))
        val nextLng = lngRad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(nextLat)
        )
        return Math.toDegrees(nextLat) to
            EnvironmentCoveragePolicy.normalizeLongitude(Math.toDegrees(nextLng))
    }
}
