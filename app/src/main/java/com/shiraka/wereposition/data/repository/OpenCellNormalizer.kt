package com.shiraka.wereposition.data.repository

/** Normalizes third-party cell payloads before they enter trusted local storage. */
object OpenCellNormalizer {
    fun normalize(cellsArray: org.json.JSONArray): org.json.JSONArray {
        val formattedCells = org.json.JSONArray()
        for (i in 0 until cellsArray.length()) {
            val cell = cellsArray.optJSONObject(i) ?: continue
            val area = cellArea(cell)
            val identity = cellIdentity(cell)
            val mcc = cell.optInt("mcc", -1)
            val mnc = if (cell.has("mnc")) cell.optInt("mnc", -1) else cell.optInt("net", -1)
            if (area <= 0 || identity <= 0 || mcc !in 100..999 || mnc !in 0..999) {
                android.util.Log.d("OpenCellID", "normalizeCellArrayForStorage: Skipping invalid cell: $cell")
                continue
            }

            val type = normalizeCellType(cell.optString("type", cell.optString("radio", "LTE")))
            val cellObj = org.json.JSONObject().apply {
                put("type", type)
                put("radio", cell.optString("radio", type))
                put("mcc", mcc)
                put("mnc", mnc)
                put("tac", area)
                put("lac", area)
                put("ci", identity)
                put("cid", identity)
                put("cellid", identity)
                put("pci", positiveCellInt(cell, "pci", default = (identity % 504).coerceIn(0, 503)))
                put("dbm", cellSignalDbm(cell, i))
                put("isRegistered", cell.optBoolean("isRegistered", i == 0))
            }
            formattedCells.put(cellObj)
        }
        return formattedCells
    }

    internal fun normalizeCellType(rawType: String): String {
        return when (rawType.uppercase(java.util.Locale.US)) {
            "GSM" -> "GSM"
            "UMTS", "WCDMA" -> "WCDMA"
            "NR", "NR5G", "5G" -> "NR"
            else -> "LTE"
        }
    }

    internal fun cellArea(cell: org.json.JSONObject): Int =
        positiveCellInt(cell, "tac", "lac", "area", default = 0)

    internal fun cellIdentity(cell: org.json.JSONObject): Int =
        positiveCellInt(cell, "ci", "cid", "cellid", "cell", default = 0)

    internal fun positiveCellInt(cell: org.json.JSONObject, vararg keys: String, default: Int): Int {
        for (key in keys) {
            if (!cell.has(key) || cell.isNull(key)) continue
            val value = cell.optInt(key, Int.MIN_VALUE)
            if (value > 0) return value
            val parsed = cell.optString(key).toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return default
    }

    internal fun cellSignalDbm(cell: org.json.JSONObject, index: Int): Int {
        val direct = cell.optInt("dbm", Int.MIN_VALUE)
        if (direct in -140..-40) return direct
        val average = cell.optInt("averageSignalStrength", Int.MIN_VALUE)
        if (average in -140..-40) return average
        val signal = cell.optInt("signal", Int.MIN_VALUE)
        if (signal in -140..-40) return signal
        return (-70 - index * 3).coerceAtLeast(-110)
    }

}
