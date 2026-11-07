package com.shiraka.wereposition.utils

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.shiraka.wereposition.utils.EnvironmentRfResolver.ResolvedRfCoverage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** User-authored test signals. These values are never written to the collection database. */
data class SyntheticRfOptions(
    val enabled: Boolean = false,
    val wifiCount: Int = 3,
    val cellCount: Int = 1,
    val ssidPrefix: String = "WRP-Test"
)

class SyntheticRfGenerator(private val context: Context) {
    private data class Network(val mcc: Int, val mnc: Int)
    private data class CountryCache(val code: String?, val recordedAt: Long)

    // E.212 country profiles. Unknown countries fail closed rather than using a wrong MCC.
    private val networks = mapOf(
        "TW" to Network(466, 92), "JP" to Network(440, 10),
        "US" to Network(310, 260), "CN" to Network(460, 0),
        "HK" to Network(454, 0), "SG" to Network(525, 1),
        "KR" to Network(450, 5), "GB" to Network(234, 10),
        "DE" to Network(262, 1), "FR" to Network(208, 1),
        "AU" to Network(505, 1), "CA" to Network(302, 720),
        "IN" to Network(404, 10)
    )
    private val countryCache = ConcurrentHashMap<EnvironmentCoveragePolicy.Cell, CountryCache>()

    suspend fun fillMissing(
        latitude: Double,
        longitude: Double,
        local: ResolvedRfCoverage,
        options: SyntheticRfOptions
    ): ResolvedRfCoverage {
        if (!options.enabled || (local.hasWifi && local.hasCell)) return local
        val cell = local.cell ?: return local
        val country = countryAt(latitude, longitude, cell) ?: return local
        val network = networks[country] ?: return local
        val random = Random(cell.q * 31L + cell.r * 131L + country.hashCode())
        val wifi = if (local.hasWifi || options.wifiCount == 0) null else
            buildWifi(options.ssidPrefix, country, options.wifiCount.coerceIn(0, 8), random)
        val cells = if (local.hasCell || options.cellCount == 0) null else
            buildCells(network, options.cellCount.coerceIn(0, 3), random)
        return local.copy(
            wifiJson = wifi ?: local.wifiJson,
            cellJson = cells ?: local.cellJson,
            hasWifi = local.hasWifi || wifi != null,
            hasCell = local.hasCell || cells != null,
            wifiApCount = if (wifi != null) options.wifiCount.coerceIn(0, 8) else local.wifiApCount,
            synthetic = wifi != null || cells != null
        )
    }

    private suspend fun countryAt(
        latitude: Double,
        longitude: Double,
        cell: EnvironmentCoveragePolicy.Cell
    ): String? {
        val now = System.currentTimeMillis()
        countryCache[cell]?.takeIf {
            now - it.recordedAt < if (it.code == null) 30_000L else 30 * 60_000L
        }?.let { return it.code }
        if (!Geocoder.isPresent()) return null
        val code = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine<String?> { continuation ->
                runCatching {
                    Geocoder(context, Locale.ENGLISH).getFromLocation(
                        latitude, longitude, 1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(
                                    addresses.firstOrNull()?.countryCode?.uppercase(Locale.ROOT)
                                )
                            }
                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    )
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
        if (countryCache.size > 64) countryCache.clear()
        countryCache[cell] = CountryCache(code, now)
        return code
    }

    private fun buildWifi(prefix: String, country: String, count: Int, random: Random): String {
        val safePrefix = prefix.trim().take(24).ifBlank { "WRP-Test" }
        val aps = List(count) { index ->
            val bssid = (0 until 6).joinToString(":") { byte ->
                "%02X".format(Locale.US, if (byte == 0) 0x02 else random.nextInt(256))
            }
            val channel = listOf(1, 6, 11)[index % 3]
            JSONObject().apply {
                put("ssid", "$safePrefix-$country-${index + 1}")
                put("bssid", bssid)
                put("vendor", "Synthetic")
                put("frequency", 2407 + channel * 5)
                put("channel", channel)
                put("level", -52 - index * 7)
                put("capabilities", "[WPA2-PSK-CCMP][ESS]")
                put("wifiStandard", 4)
                put("linkSpeed", 72)
            }
        }
        return JSONObject().apply {
            put("isConnected", aps.isNotEmpty())
            put("connectedWifi", aps.firstOrNull() ?: JSONObject.NULL)
            put("nearbyWifi", JSONArray().apply { aps.drop(1).forEach(::put) })
        }.toString()
    }

    private fun buildCells(network: Network, count: Int, random: Random): String =
        JSONArray().apply {
            repeat(count) { index ->
                put(JSONObject().apply {
                    put("type", "LTE")
                    put("mcc", network.mcc)
                    put("mnc", network.mnc)
                    put("tac", 1 + random.nextInt(65_534))
                    put("ci", 1 + random.nextInt(268_435_454))
                    put("pci", random.nextInt(504))
                    put("dbm", -78 - index * 6)
                    put("isRegistered", index == 0)
                    put("operatorName", "Synthetic")
                })
            }
        }.toString()
}
