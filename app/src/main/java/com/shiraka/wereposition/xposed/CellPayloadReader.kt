package com.shiraka.wereposition.xposed

import org.json.JSONObject

/** Parses captured cell JSON and derives signal values for client telephony hooks. */
internal class CellPayloadReader(private val currentConfig: () -> JSONObject?) {
    private val lastConfig: JSONObject? get() = currentConfig()

    private fun normalizeCellTypeOrNull(rawType: String): String? =
        when (rawType.trim().uppercase(java.util.Locale.US)) {
            "GSM", "GPRS", "EDGE" -> "GSM"
            "UMTS", "WCDMA", "HSPA", "HSPAP", "HSPA+" -> "WCDMA"
            "LTE", "LTE_CA", "4G" -> "LTE"
            "NR", "NR5G", "5G" -> "NR"
            else -> null
        }

    fun explicitCellType(cell: org.json.JSONObject?): String? {
        cell ?: return null
        val raw = cell.optString("type", cell.optString("radio", ""))
        return normalizeCellTypeOrNull(raw)
    }

    fun normalizeCellType(rawType: String): String =
        normalizeCellTypeOrNull(rawType) ?: "UNKNOWN"

    fun cellAreaCode(cell: org.json.JSONObject, default: Int): Int =
        positiveJsonInt(cell, "tac", "lac", "area", default = default)

    fun cellIdentityCode(cell: org.json.JSONObject, default: Int): Int =
        positiveJsonInt(cell, "ci", "cid", "cellid", "cell", default = default)

    private fun positiveJsonInt(cell: org.json.JSONObject, vararg keys: String, default: Int): Int {
        for (key in keys) {
            if (!cell.has(key) || cell.isNull(key)) continue
            val value = cell.optInt(key, Int.MIN_VALUE)
            if (value > 0) return value
            val parsed = cell.optString(key).toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return default
    }

    private fun rawSignalDbm(cell: org.json.JSONObject): Int? = when {
            cell.optInt("dbm", Int.MIN_VALUE) in -140..-40 -> cell.optInt("dbm")
            cell.optInt("averageSignalStrength", Int.MIN_VALUE) in -140..-40 -> cell.optInt("averageSignalStrength")
            cell.optInt("signal", Int.MIN_VALUE) in -140..-40 -> cell.optInt("signal")
            else -> null
        }

    fun signalDbm(cell: org.json.JSONObject, index: Int): Int? {
        val base = rawSignalDbm(cell) ?: return null
        return (base + signalJitterDelta(index, 0, 5)).coerceIn(-140, -40)
    }

    private fun signalRsrq(cell: org.json.JSONObject, index: Int): Int? {
        val direct = cell.optInt("rsrq", Int.MIN_VALUE)
        if (direct !in -30..-3) return null
        return (direct + signalJitterDelta(index, 1, 3)).coerceIn(-30, -3)
    }

    private fun signalSinr(cell: org.json.JSONObject, index: Int): Int? {
        val direct = cell.optInt("sinr", Int.MIN_VALUE)
        val normalized = when {
            direct in -20..40 -> direct
            direct in -200..300 -> direct / 10
            else -> return null
        }
        return (normalized + signalJitterDelta(index, 2, 3)).coerceIn(-20, 30)
    }

    /**
     * OpenCellID commonly supplies only RSRP (`dbm`). Android's LTE model keeps
     * RSSI, RSRP, RSRQ and RSSNR as independent fields; leaving the other fields
     * at their constructor defaults makes callers treat them as unavailable (or
     * occasionally render a bogus -1 dB SINR). Keep an explicit source value
     * when one exists, otherwise derive a conservative, correlated RF tuple from
     * the measured RSRP. This does not invent a tower identity and remains bound
     * to the source cell's real signal measurement.
     */
    fun signalRssi(
        cell: org.json.JSONObject,
        index: Int,
        type: String,
        referenceDbm: Int
    ): Int? {
        val direct = jsonIntValue(cell, "rssi")?.takeIf { it in -113..-51 }
        if (direct != null) {
            return (direct + signalJitterDelta(index, 3, 3)).coerceIn(-113, -51)
        }
        if (type != "LTE") return null
        // A 25-27 dB LTE RSSI/RSRP separation is representative of a loaded
        // 10-20 MHz carrier and matches Android's valid RSSI range.
        val offset = 26 + signalJitterDelta(index, 3, 2)
        return (referenceDbm + offset).coerceIn(-113, -51)
    }

    fun resolvedSignalRsrq(
        cell: org.json.JSONObject,
        index: Int,
        type: String,
        referenceDbm: Int
    ): Int? {
        signalRsrq(cell, index)?.let { return it }
        if (type != "LTE" && type != "NR") return null
        val degradation = ((-referenceDbm - 75).coerceAtLeast(0) / 6)
        val estimate = -5 - degradation + signalJitterDelta(index, 4, 2)
        return estimate.coerceIn(-18, -3)
    }

    /** Returns the integer dB value exposed by CellSignalStrengthLte.getRssnr(). */
    fun resolvedSignalSinr(
        cell: org.json.JSONObject,
        index: Int,
        type: String,
        referenceDbm: Int
    ): Int? {
        signalSinr(cell, index)?.let { return it }
        if (type != "LTE" && type != "NR") return null
        val degradation = ((-referenceDbm - 70).coerceAtLeast(0) * 3) / 5
        val estimateDb = (18 - degradation).coerceIn(-10, 30)
        return (estimateDb + signalJitterDelta(index, 5, 2)).coerceIn(-20, 30)
    }

    private fun signalJitterDelta(index: Int, salt: Int, maxAmplitude: Int): Int {
        val config = lastConfig
        if (config?.optBoolean("signal_jitter_enabled", true) == false) return 0
        val level = config?.optInt("signal_jitter_level", 40)?.coerceIn(0, 100) ?: 40
        if (level <= 0 || maxAmplitude <= 0) return 0
        val amplitude = maxOf(1, (maxAmplitude * level / 100.0).toInt())
        val tick = System.currentTimeMillis() / 2500L
        val seed = tick + index * 97L + salt * 997L + (config?.optLong("start_timestamp", 0L) ?: 0L)
        val rng = java.util.Random(seed)
        return rng.nextInt(amplitude * 2 + 1) - amplitude
    }

    private fun firstUsableCell(cells: org.json.JSONArray?): org.json.JSONObject? {
        if (cells == null) return null
        for (index in 0 until cells.length()) {
            val cell = cells.optJSONObject(index) ?: continue
            if (cell.optBoolean("isRegistered", false) &&
                cellAreaCode(cell, 0) > 0 && cellIdentityCode(cell, 0) > 0
            ) return cell
        }
        return null
    }

    fun firstServiceCell(cells: org.json.JSONArray?): org.json.JSONObject? {
        if (cells == null) return null
        for (index in 0 until cells.length()) {
            val cell = cells.optJSONObject(index) ?: continue
            if (cellAreaCode(cell, 0) <= 0 || cellIdentityCode(cell, 0) <= 0) continue
            if (!cell.optBoolean("isRegistered", false)) continue
            if (cellOperatorNumeric(cell) == null || explicitCellType(cell) == null) continue
            return cell
        }
        return null
    }

    fun firstSignalCell(cells: org.json.JSONArray?): org.json.JSONObject? {
        if (cells == null) return null
        for (index in 0 until cells.length()) {
            val cell = cells.optJSONObject(index) ?: continue
            if (cellAreaCode(cell, 0) <= 0 || cellIdentityCode(cell, 0) <= 0) continue
            if (!cell.optBoolean("isRegistered", false)) continue
            if (cellOperatorNumeric(cell) == null || explicitCellType(cell) == null) continue
            if (rawSignalDbm(cell) == null) continue
            return cell
        }
        return null
    }

    fun firstCell(config: org.json.JSONObject?): org.json.JSONObject? =
        firstUsableCell(config?.optJSONArray("cell_json"))

    fun jsonIntValue(cell: org.json.JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!cell.has(key) || cell.isNull(key)) continue
            val value = runCatching {
                val raw = cell.get(key)
                when (raw) {
                    is Number -> raw.toInt()
                    else -> raw.toString().trim().toInt()
                }
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    fun cellOperatorNumeric(cell: org.json.JSONObject?): String? {
        cell ?: return null
        val mcc = jsonIntValue(cell, "mcc")?.takeIf { it in 100..999 } ?: return null
        val mnc = jsonIntValue(cell, "mnc", "net")?.takeIf { it in 0..999 } ?: return null
        val mncWidth = if (mnc >= 100) 3 else 2
        return String.format(java.util.Locale.US, "%03d%0${mncWidth}d", mcc, mnc)
    }

    fun cellOperatorName(cell: org.json.JSONObject?): String {
        cell ?: return ""
        for (key in arrayOf("operatorName", "operator_name", "carrier")) {
            val value = cell.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return cellOperatorNumeric(cell) ?: ""
    }

    fun cellNetworkType(cell: org.json.JSONObject?): Int {
        return when (explicitCellType(cell)) {
            "GSM" -> 16
            "WCDMA" -> 3
            "LTE" -> 13
            "NR" -> 20
            else -> 0
        }
    }

    fun cellPhoneType(cell: org.json.JSONObject?): Int =
        if (explicitCellType(cell) != null) 1 else 0

}
