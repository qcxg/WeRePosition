package com.shiraka.wereposition.xposed

import org.json.JSONObject
import java.util.Random

/** Installs client-only Wi-Fi hooks and builds the selected scan/connection payload. */
internal class ClientWifiHook(
    private val readConfig: () -> JSONObject?,
    private val shouldMockWifi: (JSONObject?) -> Boolean,
    private val currentConfig: () -> JSONObject?,
    private val rng: Random
) {
    private val lastConfig: JSONObject? get() = currentConfig()

    private fun specifiedWifi(config: JSONObject?): JSONObject? =
        config?.takeIf { it.optBoolean("specified_wifi_enabled", false) }
            ?.optJSONObject("specified_wifi_json")
            ?.takeIf { it.optString("ssid").isNotBlank() &&
                com.shiraka.wereposition.data.model.SpecifiedWifiConnection.MAC_REGEX.matches(it.optString("bssid")) }

    private fun ipv4ToLittleEndian(value: String): Int? {
        val octets = value.split('.')
        if (octets.size != 4) return null
        val numbers = octets.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return numbers.foldIndexed(0) { index, result, number -> result or (number shl (index * 8)) }
    }

    private fun selectSpoofedWifi(wifiObj: JSONObject?): JSONObject? {
        specifiedWifi(lastConfig)?.let { return it }
        if (wifiObj != null) {
            val mode = lastConfig?.optString("wifi_connection_mode", "FIXED") ?: "FIXED"
            if (mode == "RANDOM") {
                val candidates = ArrayList<JSONObject>()
                val connected = wifiObj.optJSONObject("connectedWifi")
                if (isUsableWifiObject(connected)) candidates.add(connected!!)
                val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
                if (nearbyArray != null) {
                    for (i in 0 until nearbyArray.length()) {
                        val candidate = nearbyArray.optJSONObject(i)
                        if (isUsableWifiObject(candidate)) candidates.add(candidate!!)
                    }
                }
                if (candidates.isNotEmpty()) {
                    val seed = lastConfig?.optLong("start_timestamp", 0L)?.takeIf { it > 0L }
                        ?: lastConfig?.optLong("config_updated_at", 0L)
                        ?: System.currentTimeMillis()
                    return candidates[Math.floorMod(seed.toInt(), candidates.size)]
                }
            }
            val connected = wifiObj.optJSONObject("connectedWifi")
            if (isUsableWifiObject(connected)) return connected
            val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
            if (nearbyArray != null) {
                for (i in 0 until nearbyArray.length()) {
                    val candidate = nearbyArray.optJSONObject(i)
                    if (isUsableWifiObject(candidate)) return candidate
                }
            }
        }
        return null
    }

    private fun isUsableWifiObject(wifi: JSONObject?): Boolean {
        if (wifi == null) return false
        val bssid = wifi.optString("bssid", "")
        val ssid = wifi.optString("ssid", "")
        return bssid.isNotBlank() || (ssid.isNotBlank() && ssid != "<unknown ssid>")
    }

    private fun hasSpoofedWifiData(wifiObj: JSONObject?): Boolean {
        if (selectSpoofedWifi(wifiObj) != null) return true
        return (wifiObj?.optJSONArray("nearbyWifi")?.length() ?: 0) > 0
    }

    private fun wifiSsid(wifi: JSONObject): String {
        val ssidVal = wifi.optString("ssid", "")
        if (ssidVal.isNotBlank() && ssidVal != "<unknown ssid>") return ssidVal
        // A hidden or missing SSID is unknown; inventing a patterned name here makes
        // imported scan data look synthetic and can disagree with the saved record.
        return "<unknown ssid>"
    }

    private fun wifiBssid(wifi: JSONObject): String {
        val bssid = wifi.optString("bssid", "")
        return if (bssid.isNotBlank()) bssid else "02:00:00:00:00:00"
    }

    private fun wifiMac(wifi: JSONObject): String =
        wifi.optString("macAddress").takeIf(String::isNotBlank) ?: "02:00:00:00:00:00"

    private fun wifiRssi(wifi: JSONObject): Int {
        val level = wifi.optInt("level", Int.MIN_VALUE)
        if (level in -95..-20) return level
        val rssi = wifi.optInt("rssi", Int.MIN_VALUE)
        if (rssi in -95..-20) return rssi
        return -55
    }

    private fun applyWifiInfoFields(wifiInfo: Any, wifi: JSONObject, classLoader: ClassLoader) {
        val finalSsid = wifiSsid(wifi)
        val bssidVal = wifiBssid(wifi)
        val freqVal = wifi.optInt("frequency", 2412).takeIf { it > 0 } ?: 2412
        val levelVal = wifiRssi(wifi)
        val linkSpeedVal = wifi.optInt("linkSpeed", 150).takeIf { it > 0 } ?: 150
        val networkIdVal = wifi.optInt("networkId", 1).takeIf { it >= 0 } ?: 1
        val standardVal = wifi.optInt("wifiStandard", 4).takeIf { it > 0 } ?: 4
        val macAddressVal = wifiMac(wifi)

        try { XposedHelpers.callMethod(wifiInfo, "setBSSID", bssidVal) } catch (_: Throwable) {}
        try { XposedHelpers.callMethod(wifiInfo, "setMacAddress", macAddressVal) } catch (_: Throwable) {}
        try {
            val wifiSsidClass = XposedHelpers.findClass("android.net.wifi.WifiSsid", classLoader)
            val createMethod = XposedHelpers.findMethodExact(wifiSsidClass, "createFromAsciiEncoded", String::class.java)
            XposedHelpers.setObjectField(wifiInfo, "mWifiSsid", createMethod.invoke(null, finalSsid))
        } catch (_: Throwable) {
            try { XposedHelpers.setObjectField(wifiInfo, "mSSID", "\"$finalSsid\"") } catch (_: Throwable) {}
        }
        try { XposedHelpers.setObjectField(wifiInfo, "mBSSID", bssidVal) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(wifiInfo, "mBssid", bssidVal) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(wifiInfo, "mMacAddress", macAddressVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mRssi", levelVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mRssiDbm", levelVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mFrequency", freqVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mTxLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mRxLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mNetworkId", networkIdVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mWifiStandard", standardVal) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(wifiInfo, "mTrusted", true) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(wifiInfo, "mIsUsable", true) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(wifiInfo, "mScore", 60) } catch (_: Throwable) {}
        try {
            val supplicantState = XposedHelpers.findClass("android.net.wifi.SupplicantState", classLoader)
            XposedHelpers.setObjectField(wifiInfo, "mSupplicantState", supplicantState.getField("COMPLETED").get(null))
        } catch (_: Throwable) {}
    }

    private fun buildSpoofedWifiInfo(classLoader: ClassLoader, wifi: JSONObject): Any {
        val finalSsid = wifiSsid(wifi)
        val bssidVal = wifiBssid(wifi)
        val freqVal = wifi.optInt("frequency", 2412).takeIf { it > 0 } ?: 2412
        val levelVal = wifiRssi(wifi)
        val linkSpeedVal = wifi.optInt("linkSpeed", 150).takeIf { it > 0 } ?: 150
        val macAddressVal = wifiMac(wifi)
        return try {
            val builderClass = XposedHelpers.findClass("android.net.wifi.WifiInfo\$Builder", classLoader)
            val builder = XposedHelpers.newInstance(builderClass)
            try { XposedHelpers.callMethod(builder, "setSsid", finalSsid) } catch (_: Throwable) {
                try { XposedHelpers.callMethod(builder, "setSsid", finalSsid.toByteArray(Charsets.UTF_8)) } catch (_: Throwable) {}
            }
            try { XposedHelpers.callMethod(builder, "setBssid", bssidVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setMacAddress", macAddressVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setRssi", levelVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setFrequency", freqVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(builder, "setNetworkId", wifi.optInt("networkId", 1)) } catch (_: Throwable) {}
            val builtInfo = XposedHelpers.callMethod(builder, "build")!!
            applyWifiInfoFields(builtInfo, wifi, classLoader)
            builtInfo
        } catch (_: Throwable) {
            val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
            val info = XposedHelpers.newInstance(wifiInfoClass)
            applyWifiInfoFields(info, wifi, classLoader)
            info
        }
    }

    fun install(classLoader: ClassLoader) {

        // ── 1. WifiInfo getter Hook ──
        val wifiInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!shouldMockWifi(config)) return
                val mockWifi = config.optBoolean("mock_wifi", true)
                val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                val connectedWifi = selectSpoofedWifi(wifiObj)
                val isConnected = connectedWifi != null

                when (param.method!!.name) {
                    "getBSSID" -> param.result =
                        connectedWifi?.let { wifiBssid(it) } ?: "02:00:00:00:00:00"
                    "getMacAddress" -> param.result =
                        connectedWifi?.let(::wifiMac) ?: "02:00:00:00:00:00"
                    "getSSID" -> {
                        param.result = connectedWifi?.let { "\"${wifiSsid(it)}\"" } ?: "<unknown ssid>"
                    }
                    "getNetworkId" -> param.result =
                        connectedWifi?.optInt("networkId", 1) ?: -1
                    "getRssi" -> param.result =
                        connectedWifi?.let { wifiRssi(it) } ?: -127
                    "getLinkSpeed" -> param.result =
                        connectedWifi?.optInt("linkSpeed", 150) ?: -1
                    "getFrequency" -> param.result =
                        connectedWifi?.optInt("frequency", 2412) ?: -1
                    "getIpAddress" -> param.result = if (isConnected) {
                        connectedWifi?.optString("ipAddress")?.let(::ipv4ToLittleEndian)
                            ?: if (specifiedWifi(config) != null) 0 else 0x6401A8C0
                    } else 0
                    "getWifiStandard" -> param.result = connectedWifi?.optInt("wifiStandard", 4) ?: 0
                    "getTxLinkSpeedMbps", "getRxLinkSpeedMbps" ->
                        param.result = connectedWifi?.optInt("linkSpeed", 150) ?: -1
                }
            }
        }

        try {
            val wifiInfoMethods = listOf(
                "getBSSID", "getMacAddress", "getSSID", "getNetworkId",
                "getRssi", "getLinkSpeed", "getFrequency", "getIpAddress",
                "getWifiStandard", "getTxLinkSpeedMbps", "getRxLinkSpeedMbps"
            )
            for (method in wifiInfoMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        "android.net.wifi.WifiInfo", classLoader, method, wifiInfoHook
                    )
                } catch (e: Throwable) { /* 部分方法在低版本可能不存在 */ }
            }
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 1b. WifiInfo.getSupplicantState() ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo", classLoader, "getSupplicantState",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        val isConnected = selectSpoofedWifi(wifiObj) != null
                        try {
                            val enumClass = XposedHelpers.findClass(
                                "android.net.wifi.SupplicantState", classLoader
                            )
                            val stateStr = if (isConnected) "COMPLETED" else "DISCONNECTED"
                            param.result = enumClass.getField(stateStr).get(null)
                        } catch (e: Throwable) { /* 忽略 */ }
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        // ── 2. Wi-Fi 扫描结果伪造 (getScanResults) ──
        val realCapabilities = listOf(
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]",
            "[WPA2-PSK-CCMP+TKIP][RSN-PSK-CCMP+TKIP][ESS]",
            "[WPA2-PSK-CCMP][ESS][WPS]",
            "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS]",
            "[RSN-PSK-CCMP][ESS]",
            "[WPA2-EAP-CCMP][RSN-EAP-CCMP][ESS]",
            "[ESS]",
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]",
            "[WPA2-SAE-CCMP][RSN-SAE-CCMP][ESS]",
            "[WPA2-PSK+SAE-CCMP][RSN-PSK+SAE-CCMP][ESS]"
        )

        val wifiScanHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!shouldMockWifi(config)) return
                val fakeList = java.util.ArrayList<Any>()
                val mockWifi = config.optBoolean("mock_wifi", true)
                val wifiObj = if (mockWifi || specifiedWifi(config) != null) {
                    config.optJSONObject("wifi_json") ?: JSONObject()
                } else null
                if (wifiObj != null) {
                    try {
                        val scanResultClass =
                            XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                        val baseTimestamp = android.os.SystemClock.elapsedRealtimeNanos()

                        fun addFakeScanResult(wifi: org.json.JSONObject) {
                            val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                            val bssidVal = wifi.optString("bssid", "")
                            val finalSsid = wifiSsid(wifi).takeUnless { it == "<unknown ssid>" } ?: ""
                            XposedHelpers.setObjectField(fakeScanResult, "SSID", finalSsid)
                            XposedHelpers.setObjectField(fakeScanResult, "BSSID", bssidVal)
                            val level = wifi.optInt("level", -65)
                            XposedHelpers.setIntField(fakeScanResult, "level", level)
                            XposedHelpers.setIntField(
                                fakeScanResult, "frequency",
                                wifi.optInt("frequency", 2412)
                            )
                            XposedHelpers.setObjectField(
                                fakeScanResult, "capabilities",
                                wifi.optString("capabilities").takeIf(String::isNotBlank)
                                    ?: realCapabilities.random()
                            )
                            try {
                                val offsetNanos = (rng.nextInt(200_000) * 1000L)
                                XposedHelpers.setLongField(
                                    fakeScanResult, "timestamp",
                                    (baseTimestamp - offsetNanos) / 1000
                                )
                            } catch (e: Throwable) {}
                            fakeList.add(fakeScanResult)
                        }

                        val connectedWifi = selectSpoofedWifi(wifiObj)
                        if (connectedWifi != null) {
                            addFakeScanResult(connectedWifi)
                        }

                        val previousConnected = wifiObj.optJSONObject("connectedWifi")
                        if (previousConnected != null && isUsableWifiObject(previousConnected) &&
                            (connectedWifi == null || !wifiBssid(previousConnected).equals(wifiBssid(connectedWifi), true))) {
                            addFakeScanResult(previousConnected)
                        }

                        val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
                        if (nearbyArray != null && nearbyArray.length() > 0) {
                            for (i in 0 until nearbyArray.length()) {
                                val wifi = nearbyArray.getJSONObject(i)
                                if (connectedWifi != null && wifiBssid(wifi).equals(wifiBssid(connectedWifi), ignoreCase = true)) continue
                                addFakeScanResult(wifi)
                            }
                        }
                    } catch (e: Throwable) { /* 忽略 */ }
                }
                XposedBridge.logEvery(
                    "wifiScanResults:${fakeList.size}",
                    "[WeRePosition] getScanResults returning fake AP count=${fakeList.size}"
                )
                param.result = fakeList
            }
        }

        // ── 3. WifiManager 整体 Hook ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getScanResults", wifiScanHook
            )

            // getWifiState()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getWifiState",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = config.optJSONObject("wifi_json")
                        val hasWifiData = specifiedWifi(config) != null || hasSpoofedWifiData(wifiObj)
                        if (mockWifi || specifiedWifi(config) != null) {
                            param.result = if (hasWifiData) 3 else 1 // 3 is WIFI_STATE_ENABLED, 1 is disabled
                        }
                    }
                })

            // isWifiEnabled()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "isWifiEnabled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = config.optJSONObject("wifi_json")
                        val hasWifiData = specifiedWifi(config) != null || hasSpoofedWifiData(wifiObj)
                        if (mockWifi || specifiedWifi(config) != null) {
                            param.result = hasWifiData
                        }
                    }
                })

            // getConnectionInfo() — 返回伪造的 WifiInfo 对象
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        val connectedWifi = selectSpoofedWifi(wifiObj)

                        if (connectedWifi != null) {
                            try {
                                param.result = buildSpoofedWifiInfo(classLoader, connectedWifi)
                            } catch (e: Throwable) { /* 忽略 */ }
                        } else {
                            try {
                                val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
                                val fakeWifiInfo = XposedHelpers.newInstance(wifiInfoClass)
                                try { XposedHelpers.setObjectField(fakeWifiInfo, "mBSSID", "02:00:00:00:00:00") } catch(e:Throwable){}
                                try { XposedHelpers.setObjectField(fakeWifiInfo, "mMacAddress", "02:00:00:00:00:00") } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mNetworkId", -1) } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mRssi", -127) } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mLinkSpeed", -1) } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mFrequency", -1) } catch(e:Throwable){}
                                param.result = fakeWifiInfo
                            } catch (e: Throwable) {}
                        }
                    }
                })

            // getConfiguredNetworks()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getConfiguredNetworks",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        param.result = java.util.ArrayList<Any>()
                    }
                })

            // getDhcpInfo()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getDhcpInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val connectedWifi = selectSpoofedWifi(config.optJSONObject("wifi_json"))
                        if (connectedWifi == null) {
                            param.result = null
                            return
                        }
                        val specified = specifiedWifi(config) != null
                        if (specified && connectedWifi.optString("ipAddress").isBlank()) {
                            param.result = null
                            return
                        }
                        try {
                            val dhcpClass = XposedHelpers.findClass("android.net.DhcpInfo", classLoader)
                            val dhcp = XposedHelpers.newInstance(dhcpClass)
                            XposedHelpers.setIntField(dhcp, "ipAddress", ipv4ToLittleEndian(connectedWifi.optString("ipAddress")) ?: 0x6401A8C0.toInt())
                            XposedHelpers.setIntField(dhcp, "gateway", ipv4ToLittleEndian(connectedWifi.optString("gateway")) ?: if (specified) 0 else 0x0101A8C0)
                            XposedHelpers.setIntField(dhcp, "netmask", 0x00FFFFFF)     // 255.255.255.0
                            XposedHelpers.setIntField(dhcp, "dns1", ipv4ToLittleEndian(connectedWifi.optString("dns1")) ?: if (specified) 0 else 0x0101A8C0)
                            XposedHelpers.setIntField(dhcp, "dns2", ipv4ToLittleEndian(connectedWifi.optString("dns2")) ?: if (specified) 0 else 0x08080808)
                            XposedHelpers.setIntField(dhcp, "serverAddress", ipv4ToLittleEndian(connectedWifi.optString("gateway")) ?: if (specified) 0 else 0x0101A8C0)
                            param.result = dhcp
                        } catch (e: Throwable) { /* 忽略 */ }
                    }
                })
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 4. NetworkInfo.getExtraInfo() ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.NetworkInfo", classLoader, "getExtraInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return
                        val networkType = runCatching {
                            XposedHelpers.callMethod(param.thisObject!!, "getType") as Int
                        }.getOrNull()
                        if (networkType != 1) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        val connectedWifi = selectSpoofedWifi(wifiObj)
                        if (connectedWifi != null) {
                            param.result = "\"${wifiSsid(connectedWifi)}\""
                        } else {
                            param.result = null
                        }
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 5. WifiScanner Hook ──
        try {
            val wifiScannerClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiScanner", classLoader)
            if (wifiScannerClass != null) {
                val scannerHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!shouldMockWifi(config)) return

                        param.result = null
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi || specifiedWifi(config) != null) {
                            config.optJSONObject("wifi_json") ?: JSONObject()
                        } else null
                        fun dispatchWifiScanResults(fakeScanDataArray: Any) {
                            val candidates = param.args.filterNotNull().asReversed()
                            var delivered = false
                            for (candidate in candidates) {
                                if (XposedHelpers.tryCallMethodDeep(candidate, "onResults", fakeScanDataArray)) {
                                    delivered = true
                                    break
                                }
                            }
                            if (!delivered) {
                                for (candidate in candidates) {
                                    if (XposedHelpers.tryCallMethodDeep(candidate, "onSuccess")) {
                                        delivered = true
                                        break
                                    }
                                }
                            }
                            if (!delivered) {
                                XposedBridge.logOpenCellIdEvery(
                                    "wifiScanner:no-callback",
                                    "WifiScanner.startScan intercepted but no listener callback accepted args=${param.args.map { it?.javaClass?.name }}",
                                    30_000L
                                )
                            }
                        }
                        if (wifiObj != null) {
                            try {
                                val scanResultClass = XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                                val baseTimestamp = android.os.SystemClock.elapsedRealtimeNanos()
                                val fakeList = java.util.ArrayList<Any>()

                                fun addFakeScanResult(wifi: org.json.JSONObject) {
                                    val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                                    val finalSsid = wifiSsid(wifi)
                                    val bssidVal = wifiBssid(wifi)
                                    XposedHelpers.setObjectField(fakeScanResult, "SSID", finalSsid)
                                    XposedHelpers.setObjectField(fakeScanResult, "BSSID", bssidVal)
                                    val level = wifiRssi(wifi)
                                    XposedHelpers.setIntField(fakeScanResult, "level", level)
                                    XposedHelpers.setIntField(fakeScanResult, "frequency", wifi.optInt("frequency", 2412))
                                    XposedHelpers.setObjectField(fakeScanResult, "capabilities",
                                        wifi.optString("capabilities").takeIf(String::isNotBlank)
                                            ?: "[WPA2-PSK-CCMP][ESS]")
                                    try {
                                        val offsetNanos = (rng.nextInt(200_000) * 1000L)
                                        XposedHelpers.setLongField(fakeScanResult, "timestamp", (baseTimestamp - offsetNanos) / 1000)
                                    } catch (e: Throwable) {}
                                    fakeList.add(fakeScanResult)
                                }

                                val connectedWifi = selectSpoofedWifi(wifiObj)
                                if (connectedWifi != null) {
                                    addFakeScanResult(connectedWifi)
                                }

                                val previousConnected = wifiObj.optJSONObject("connectedWifi")
                                if (previousConnected != null && isUsableWifiObject(previousConnected) &&
                                    (connectedWifi == null || !wifiBssid(previousConnected).equals(wifiBssid(connectedWifi), true))) {
                                    addFakeScanResult(previousConnected)
                                }

                                val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
                                if (nearbyArray != null && nearbyArray.length() > 0) {
                                    for (i in 0 until nearbyArray.length()) {
                                        val wifi = nearbyArray.getJSONObject(i)
                                        if (connectedWifi != null && wifiBssid(wifi).equals(wifiBssid(connectedWifi), ignoreCase = true)) continue
                                        addFakeScanResult(wifi)
                                    }
                                }

                                if (fakeList.isNotEmpty()) {
                                    val scanResultArray = java.lang.reflect.Array.newInstance(scanResultClass, fakeList.size)
                                    for (i in 0 until fakeList.size) {
                                        java.lang.reflect.Array.set(scanResultArray, i, fakeList[i])
                                    }

                                    // 构造 ScanData 对象（包含 ScanResult 数组）
                                    val scanDataClass = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ScanData", classLoader)
                                    val fakeScanData = XposedHelpers.newInstance(scanDataClass, 0, 0, scanResultArray)
                                    val fakeScanDataArray = java.lang.reflect.Array.newInstance(scanDataClass, 1)
                                    java.lang.reflect.Array.set(fakeScanDataArray, 0, fakeScanData)

                                    // 主动回调 Listener，把假数据塞回去
                                    dispatchWifiScanResults(fakeScanDataArray)
                                } else {
                                    val scanDataClass = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ScanData", classLoader)
                                    val emptyScanData = XposedHelpers.newInstance(scanDataClass, 0, 0, java.lang.reflect.Array.newInstance(scanResultClass, 0))
                                    val fakeScanDataArray = java.lang.reflect.Array.newInstance(scanDataClass, 1)
                                    java.lang.reflect.Array.set(fakeScanDataArray, 0, emptyScanData)
                                    dispatchWifiScanResults(fakeScanDataArray)
                                }
                            } catch (e: Throwable) {
                                XposedBridge.logOpenCellIdEvery(
                                    "wifi-scanner-build:${e.javaClass.name}",
                                    "WifiScanner simulated result build failed: ${e.javaClass.simpleName}: ${e.message}",
                                    60_000L
                                )
                            }
                        } else {
                            try {
                                val scanDataClass = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ScanData", classLoader)
                                val scanResultClass = XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                                val emptyScanData = XposedHelpers.newInstance(scanDataClass, 0, 0, java.lang.reflect.Array.newInstance(scanResultClass, 0))
                                val fakeScanDataArray = java.lang.reflect.Array.newInstance(scanDataClass, 1)
                                java.lang.reflect.Array.set(fakeScanDataArray, 0, emptyScanData)
                                dispatchWifiScanResults(fakeScanDataArray)
                            } catch (e: Throwable) { /* 忽略 */ }
                        }
                    }
                }

                // startScan(ScanSettings, ScanListener) 和重载
                XposedBridge.hookAllMethods(wifiScannerClass, "startScan", scannerHook)
            }
        } catch (e: Throwable) { XposedBridge.log(e) }

        XposedBridge.log("[WeRePosition] Wi-Fi environment hooks installed")
    }

}
