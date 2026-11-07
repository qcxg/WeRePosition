package com.shiraka.wereposition.xposed

import org.json.JSONObject
import com.shiraka.wereposition.xposed.LocationHooker.SatelliteData

/** Pure satellite geometry shared by system and client GNSS delivery. */
internal object SatelliteSkyGenerator {
    fun generateSatelliteData(
        satIndex: Int,
        config: JSONObject,
        enableJitter: Boolean,
        timeSec: Double
    ): SatelliteData {
        val updateIntervalSec = 4.0
        val steppedTimeSec = Math.floor((timeSec + satIndex) / updateIntervalSec) * updateIntervalSec - satIndex
        val targetLat = config.optDouble("lat", 0.0)
        val targetLng = config.optDouble("lng", 0.0)

        val type = when (satIndex % 10) {
            0, 1, 2, 3, 4 -> 1
            5, 6 -> 3
            else -> 5
        }
        val svid = when (type) {
            1 -> 1 + (satIndex * 7) % 32 // GPS
            3 -> 1 + (satIndex * 3) % 24 // GLONASS (GnssStatus standard is 1-24)
            else -> 1 + (satIndex * 5) % 63 // BDS
        }

        val orbitalRadiusMeters = when (type) {
            3 -> 25_510_000.0
            5 -> 27_906_000.0
            else -> 26_560_000.0
        }
        val inclinationDeg = when (type) {
            3 -> 64.8
            5 -> 55.0
            else -> 55.0
        }
        val orbitalPeriodSec = when (type) {
            3 -> 40_544.0
            5 -> 46_800.0
            else -> 43_082.0
        }

        val raan = Math.toRadians(((satIndex * 47 + type * 23) % 360).toDouble())
        val inclination = Math.toRadians(inclinationDeg)
        val meanAnomaly = Math.toRadians(((satIndex * 137 + 53) % 360).toDouble()) +
            (2.0 * Math.PI * steppedTimeSec / orbitalPeriodSec)

        val cosRaan = Math.cos(raan)
        val sinRaan = Math.sin(raan)
        val cosInc = Math.cos(inclination)
        val sinInc = Math.sin(inclination)
        val xOrb = orbitalRadiusMeters * Math.cos(meanAnomaly)
        val yOrb = orbitalRadiusMeters * Math.sin(meanAnomaly)
        val xEci = cosRaan * xOrb - sinRaan * cosInc * yOrb
        val yEci = sinRaan * xOrb + cosRaan * cosInc * yOrb
        val zEci = sinInc * yOrb

        val gmst = Math.toRadians(normalizeDegrees(280.46061837 + 360.98564736629 * (steppedTimeSec / 86400.0)))
        val cosGmst = Math.cos(gmst)
        val sinGmst = Math.sin(gmst)
        val satX = cosGmst * xEci + sinGmst * yEci
        val satY = -sinGmst * xEci + cosGmst * yEci
        val satZ = zEci

        val look = lookAnglesFromReceiver(targetLat, targetLng, satX, satY, satZ)
        val noise = if (enableJitter) {
            val dynamicRng = java.util.Random((steppedTimeSec / 3.0).toLong() + satIndex * 17L)
            (dynamicRng.nextDouble() - 0.5) * 3.0
        } else 0.0
        val cn0 = (18.0 + (look.second.coerceIn(0.0, 85.0) / 85.0) * 24.0 + noise)
            .coerceIn(12.0, 46.0)
            .toFloat()

        return SatelliteData(
            svid = svid,
            type = type,
            elevation = look.second.toFloat().coerceIn(-90f, 90f),
            azimuth = look.first.toFloat(),
            cn0 = cn0,
            usedInFix = false
        )
    }

    fun generateFallbackSkySatellite(
        satIndex: Int,
        config: JSONObject,
        enableJitter: Boolean,
        timeSec: Double
    ): SatelliteData {
        val targetLat = config.optDouble("lat", 0.0)
        val targetLng = config.optDouble("lng", 0.0)
        val type = when (satIndex % 10) {
            0, 1, 2, 3, 4 -> 1
            5, 6 -> 3
            else -> 5
        }
        val svid = when (type) {
            1 -> 1 + (satIndex * 7) % 32
            3 -> 1 + (satIndex * 3) % 24
            else -> 1 + (satIndex * 5) % 63
        }
        val phase = normalizeDegrees(targetLng + targetLat * 0.37 + satIndex * 137.5 + timeSec / 240.0)
        val elevation = (18.0 + ((satIndex * 23 + Math.abs(targetLat).toInt()) % 58)).toFloat()
        val cn0Noise = if (enableJitter) {
            val rng = java.util.Random((timeSec / 4.0).toLong() + satIndex * 31L)
            (rng.nextDouble() - 0.5) * 3.0
        } else 0.0
        val cn0 = (20.0 + elevation / 90.0 * 22.0 + cn0Noise).coerceIn(14.0, 45.0).toFloat()
        return SatelliteData(svid, type, elevation, phase.toFloat(), cn0, false)
    }

    private fun lookAnglesFromReceiver(
        latDeg: Double,
        lngDeg: Double,
        satX: Double,
        satY: Double,
        satZ: Double
    ): Pair<Double, Double> {
        val lat = Math.toRadians(latDeg)
        val lng = Math.toRadians(lngDeg)
        val earthRadius = 6_378_137.0
        val recX = earthRadius * Math.cos(lat) * Math.cos(lng)
        val recY = earthRadius * Math.cos(lat) * Math.sin(lng)
        val recZ = earthRadius * Math.sin(lat)
        val dx = satX - recX
        val dy = satY - recY
        val dz = satZ - recZ
        val east = -Math.sin(lng) * dx + Math.cos(lng) * dy
        val north = -Math.sin(lat) * Math.cos(lng) * dx -
            Math.sin(lat) * Math.sin(lng) * dy +
            Math.cos(lat) * dz
        val up = Math.cos(lat) * Math.cos(lng) * dx +
            Math.cos(lat) * Math.sin(lng) * dy +
            Math.sin(lat) * dz
        val horizontal = Math.sqrt(east * east + north * north)
        val azimuth = normalizeDegrees(Math.toDegrees(Math.atan2(east, north)))
        val elevation = Math.toDegrees(Math.atan2(up, horizontal))
        return Pair(azimuth, elevation)
    }

    fun normalizeDegrees(value: Double): Double {
        val mod = value % 360.0
        return if (mod < 0.0) mod + 360.0 else mod
    }

}
