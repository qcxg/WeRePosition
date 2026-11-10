package com.shiraka.wereposition.xposed

import org.json.JSONObject

/** Formats synthetic NMEA sentences; hook installation and caller policy stay in LocationHooker. */
internal class NmeaSentenceFactory(
    private val updateSatelliteCacheIfNeeded: () -> Unit,
    private val satelliteSnapshot: () -> Array<SatelliteData>?,
    private val activeSnapshot: () -> Boolean,
    private val spoofedBearing: (JSONObject) -> Float,
    private val spoofedSpeed: (JSONObject) -> Float,
    private val hasSpoofedMotion: (JSONObject) -> Boolean
) {
    private val cachedSatellites: Array<SatelliteData>? get() = satelliteSnapshot()
    private val isSpoofingActiveCache: Boolean get() = activeSnapshot()
    private var nmeaGsvPageCounter = 0

    fun spoofNmeaMessage(sentence: String, config: JSONObject): String? {
        try {
            val targetLat = config.optDouble("lat", 0.0)
            val targetLng = config.optDouble("lng", 0.0)
            if (!targetLat.isFinite() || !targetLng.isFinite() ||
                targetLat !in -90.0..90.0 || targetLng !in -180.0..180.0
            ) return null

            val parts = sentence.split("*")
            val mainPart = parts[0]
            val fields = mainPart.split(",").toMutableList()
            if (fields.isEmpty()) return null

            val type = fields[0]
            var modified = false

            if (type.endsWith("GSV")) {
                return buildSpoofedGsvSentence()
            }
            if (type.endsWith("GSA")) {
                return buildSpoofedGsaSentence()
            }

            if (type.endsWith("RMC") && fields.size >= 7) {
                val (latStr, latDir) = convertToNmeaLatitude(targetLat)
                val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
                if (fields.size > 2) fields[2] = "A"
                fields[3] = latStr
                fields[4] = latDir
                fields[5] = lngStr
                fields[6] = lngDir
                if (fields.size > 8) fields[8] = String.format(java.util.Locale.US, "%.1f", spoofedBearing(config))
                modified = true
            } else if (type.endsWith("GGA") && fields.size >= 6) {
                val (latStr, latDir) = convertToNmeaLatitude(targetLat)
                val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
                fields[2] = latStr
                fields[3] = latDir
                fields[4] = lngStr
                fields[5] = lngDir
                if (fields.size > 6) fields[6] = "1"
                if (fields.size > 7) fields[7] = String.format(java.util.Locale.US, "%02d", config.optInt("satellite_count", 20).coerceIn(0, 64))
                if (fields.size > 8) fields[8] = estimateNmeaHdop()
                modified = true
            } else if (type.endsWith("GLL") && fields.size >= 5) {
                val (latStr, latDir) = convertToNmeaLatitude(targetLat)
                val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
                fields[1] = latStr
                fields[2] = latDir
                fields[3] = lngStr
                fields[4] = lngDir
                if (fields.size > 6) fields[6] = "A"
                modified = true
            } else if (type.endsWith("VTG") && fields.size >= 2) {
                fields[1] = String.format(java.util.Locale.US, "%.1f", spoofedBearing(config))
                if (fields.size > 2) fields[2] = "T"
                modified = true
            }

            if (!modified) return null

            val newMainPart = fields.joinToString(",")
            val newChecksum = calculateNmeaChecksum(newMainPart)

            val tail = if (parts.size > 1) {
                val rawTail = parts[1]
                val lineEnding = rawTail.substring(Math.min(2, rawTail.length))
                "*$newChecksum$lineEnding"
            } else {
                "*$newChecksum"
            }
            return newMainPart + tail
        } catch (e: Exception) {
            XposedBridge.log(e)
            return null
        }
    }

    private fun buildSpoofedGsaSentence(): String? {
        updateSatelliteCacheIfNeeded()
        val sats = cachedSatellites ?: return null
        if (!isSpoofingActiveCache || sats.isEmpty()) return null
        val used = sats.filter { it.usedInFix }.take(12).map { nmeaPrn(it) }.toMutableList()
        while (used.size < 12) used.add("")
        val hdop = estimateNmeaHdop()
        val hdopValue = hdop.toDoubleOrNull() ?: 0.9
        val pdop = String.format(java.util.Locale.US, "%.1f", (hdopValue * 1.7).coerceIn(0.8, 3.5))
        val vdop = String.format(java.util.Locale.US, "%.1f", (hdopValue * 1.3).coerceIn(0.7, 2.8))
        val fields = mutableListOf("\$GNGSA", "A", "3")
        fields.addAll(used)
        fields.add(pdop)
        fields.add(hdop)
        fields.add(vdop)
        return appendNmeaChecksum(fields.joinToString(","))
    }

    fun buildSpoofedNmeaBurst(config: JSONObject): List<String> {
        val targetLat = config.optDouble("lat", 0.0)
        val targetLng = config.optDouble("lng", 0.0)
        if (targetLat == 0.0 && targetLng == 0.0) return emptyList()
        updateSatelliteCacheIfNeeded()
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val time = String.format(
            java.util.Locale.US,
            "%02d%02d%02d.00",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND)
        )
        val date = String.format(
            java.util.Locale.US,
            "%02d%02d%02d",
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.YEAR) % 100
        )
        val (latStr, latDir) = convertToNmeaLatitude(targetLat)
        val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
        val sats = cachedSatellites
        val satCount = (sats?.size ?: config.optInt("satellite_count", 20)).coerceIn(0, 64)
        val hdop = estimateNmeaHdop()
        val altitude = config.optDouble("altitude", 0.0)
        val speedKnots = spoofedSpeed(config) * 1.943844f
        val bearing = if (hasSpoofedMotion(config)) spoofedBearing(config) else 0f
        val sentences = ArrayList<String>()
        sentences.add(appendNmeaChecksum("\$GNRMC,$time,A,$latStr,$latDir,$lngStr,$lngDir,${String.format(java.util.Locale.US, "%.1f", speedKnots)},${String.format(java.util.Locale.US, "%.1f", bearing)},$date,,,A"))
        sentences.add(appendNmeaChecksum("\$GNGGA,$time,$latStr,$latDir,$lngStr,$lngDir,1,${String.format(java.util.Locale.US, "%02d", satCount)},$hdop,${String.format(java.util.Locale.US, "%.1f", altitude)},M,0.0,M,,"))
        sentences.add(appendNmeaChecksum("\$GNGLL,$latStr,$latDir,$lngStr,$lngDir,$time,A,A"))
        sentences.add(appendNmeaChecksum("\$GNVTG,${String.format(java.util.Locale.US, "%.1f", bearing)},T,,M,${String.format(java.util.Locale.US, "%.1f", speedKnots)},N,${String.format(java.util.Locale.US, "%.1f", spoofedSpeed(config) * 3.6f)},K,A"))
        buildSpoofedGsaSentence()?.let { sentences.add(it) }
        val gsvPages = ((satCount + 3) / 4).coerceAtLeast(1)
        repeat(gsvPages) {
            buildSpoofedGsvSentence()?.let { sentence -> sentences.add(sentence) }
        }
        return sentences
    }

    private fun buildSpoofedGsvSentence(): String? {
        updateSatelliteCacheIfNeeded()
        val sats = cachedSatellites ?: return null
        if (!isSpoofingActiveCache || sats.isEmpty()) return null
        val totalMessages = ((sats.size + 3) / 4).coerceAtLeast(1)
        val page = (nmeaGsvPageCounter % totalMessages) + 1
        nmeaGsvPageCounter = (nmeaGsvPageCounter + 1) % totalMessages
        val start = (page - 1) * 4
        val end = minOf(start + 4, sats.size)
        val fields = mutableListOf(
            "\$GNGSV",
            totalMessages.toString(),
            page.toString(),
            sats.size.toString()
        )
        for (i in start until end) {
            val sat = sats[i]
            fields.add(nmeaPrn(sat))
            fields.add(sat.elevation.toInt().coerceIn(0, 90).toString())
            fields.add(sat.azimuth.toInt().coerceIn(0, 359).toString())
            fields.add(sat.cn0.toInt().coerceIn(0, 99).toString())
        }
        return appendNmeaChecksum(fields.joinToString(","))
    }

    private fun nmeaPrn(sat: SatelliteData): String {
        val prn = when (sat.type) {
            3 -> sat.svid + 64
            5 -> sat.svid + 200
            else -> sat.svid
        }
        return String.format(java.util.Locale.US, "%02d", prn)
    }

    private fun estimateNmeaHdop(): String {
        updateSatelliteCacheIfNeeded()
        val sats = cachedSatellites ?: return "0.9"
        val used = sats.count { it.usedInFix }.coerceAtLeast(1)
        val avgElevation = sats.filter { it.usedInFix }.map { it.elevation }.ifEmpty { sats.map { it.elevation } }.average()
        val hdop = (2.4 - used * 0.11 - avgElevation / 120.0).coerceIn(0.6, 2.2)
        return String.format(java.util.Locale.US, "%.1f", hdop)
    }

    private fun appendNmeaChecksum(mainPart: String): String {
        return mainPart + "*" + calculateNmeaChecksum(mainPart)
    }

    private fun convertToNmeaLatitude(lat: Double): Pair<String, String> {
        val absLat = Math.abs(lat)
        val degrees = absLat.toInt()
        val minutes = (absLat - degrees) * 60.0
        val latStr = String.format(java.util.Locale.US, "%02d%08.5f", degrees, minutes)
        val dir = if (lat >= 0) "N" else "S"
        return Pair(latStr, dir)
    }

    private fun convertToNmeaLongitude(lng: Double): Pair<String, String> {
        val absLng = Math.abs(lng)
        val degrees = absLng.toInt()
        val minutes = (absLng - degrees) * 60.0
        val lngStr = String.format(java.util.Locale.US, "%03d%08.5f", degrees, minutes)
        val dir = if (lng >= 0) "E" else "W"
        return Pair(lngStr, dir)
    }

    private fun calculateNmeaChecksum(sentence: String): String {
        var checksum = 0
        val startIndex = if (sentence.startsWith("$")) 1 else 0
        val endIndex = sentence.indexOf('*')
        val limit = if (endIndex != -1) endIndex else sentence.length
        for (i in startIndex until limit) {
            checksum = checksum xor sentence[i].code
        }
        return String.format(java.util.Locale.US, "%02X", checksum)
    }

}
