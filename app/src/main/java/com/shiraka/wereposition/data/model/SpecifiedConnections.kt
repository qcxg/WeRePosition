package com.shiraka.wereposition.data.model

import org.json.JSONArray
import org.json.JSONObject

data class SpecifiedWifiConnection(
    val ssid: String = "",
    val bssid: String = "",
    val macAddress: String = "",
    val frequency: Int = 2412,
    val rssi: Int = -55,
    val linkSpeed: Int = 150,
    val wifiStandard: Int = 4,
    val networkId: Int = 1,
    val ipAddress: String = "",
    val gateway: String = "",
    val dns1: String = "",
    val dns2: String = "",
    val security: String = "",
    val capabilities: String = ""
) {
    val hasValidBssid: Boolean get() = normalizedMac(bssid)?.let {
        it != "02:00:00:00:00:00"
    } == true

    val isValid: Boolean get() = ssid.isNotBlank() && hasValidBssid &&
        (macAddress.isBlank() || normalizedMac(macAddress) != null) &&
        listOf(ipAddress, gateway, dns1, dns2).all { it.isBlank() || validIpv4(it) }

    fun normalized() = copy(ssid = ssid.trim(), bssid = normalizedMac(bssid) ?: bssid.trim(),
        macAddress = normalizedMac(macAddress) ?: macAddress.trim(), ipAddress = ipAddress.trim(),
        gateway = gateway.trim(), dns1 = dns1.trim(), dns2 = dns2.trim())

    fun toJson() = JSONObject().apply {
        put("ssid", ssid.trim())
        put("bssid", normalizedMac(bssid) ?: bssid.trim())
        put("macAddress", normalizedMac(macAddress) ?: macAddress.trim())
        put("frequency", frequency)
        put("level", rssi)
        put("linkSpeed", linkSpeed)
        put("wifiStandard", wifiStandard)
        put("networkId", networkId)
        put("ipAddress", ipAddress.trim())
        put("gateway", gateway.trim())
        put("dns1", dns1.trim())
        put("dns2", dns2.trim())
        put("security", security.trim())
        put("capabilities", capabilities.trim())
    }

    companion object {
        val MAC_REGEX = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        private val DECIMAL_OCTET = Regex("[0-9]{1,3}")

        fun normalizedMac(value: String): String? = com.shiraka.wereposition.utils.MacAddressInput.normalize(value)

        fun validIpv4(value: String): Boolean = value.trim().split('.').let { octets ->
            octets.size == 4 && octets.all { it.matches(DECIMAL_OCTET) && it.toIntOrNull() in 0..255 }
        }

        fun fromJson(value: String): SpecifiedWifiConnection = runCatching {
            val json = JSONObject(value)
            SpecifiedWifiConnection(
                ssid = json.optString("ssid"),
                bssid = json.optString("bssid"),
                macAddress = json.optString("macAddress"),
                frequency = json.optInt("frequency", 2412),
                rssi = json.optInt("level", -55),
                linkSpeed = json.optInt("linkSpeed", 150),
                wifiStandard = json.optInt("wifiStandard", 4),
                networkId = json.optInt("networkId", 1),
                ipAddress = json.optString("ipAddress"),
                gateway = json.optString("gateway"),
                dns1 = json.optString("dns1"),
                dns2 = json.optString("dns2"),
                security = json.optString("security"),
                capabilities = json.optString("capabilities")
            )
        }.getOrDefault(SpecifiedWifiConnection())
    }
}

data class SpecifiedBluetoothDevice(
    val address: String = "",
    val name: String = "",
    val alias: String = "",
    val deviceClass: Int = 0,
    val a2dp: Boolean = true,
    val headset: Boolean = false,
    val uuids: List<String> = emptyList()
) {
    val isValid: Boolean get() = SpecifiedWifiConnection.MAC_REGEX.matches(address) &&
        uuids.all { runCatching { java.util.UUID.fromString(it) }.isSuccess }

    fun toJson() = JSONObject().apply {
        put("address", address.trim())
        put("name", name.trim())
        put("alias", alias.trim())
        put("deviceClass", deviceClass)
        put("a2dp", a2dp)
        put("headset", headset)
        put("uuids", JSONArray(uuids))
    }

    companion object {
        fun fromJson(json: JSONObject) = SpecifiedBluetoothDevice(
            address = json.optString("address"),
            name = json.optString("name"),
            alias = json.optString("alias"),
            deviceClass = json.optInt("deviceClass", 0),
            a2dp = json.optBoolean("a2dp", true),
            headset = json.optBoolean("headset", false),
            uuids = (json.optJSONArray("uuids") ?: JSONArray()).let { values ->
                (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
            }
        )

        fun listFromJson(value: String): List<SpecifiedBluetoothDevice> = runCatching {
            val array = JSONArray(value)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())

        fun listToJson(devices: List<SpecifiedBluetoothDevice>): JSONArray = JSONArray().apply {
            devices.forEach { put(it.toJson()) }
        }
    }
}
