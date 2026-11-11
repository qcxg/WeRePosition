package com.shiraka.wereposition.xposed

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.os.ParcelUuid
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Adds configured classic devices in selected client processes; never hides real devices. */
internal class ClientBluetoothHook(
    private val readConfig: () -> JSONObject?,
    private val shouldDeliver: (JSONObject?) -> Boolean
) {
    @Volatile private var parsedConfig: JSONObject? = null
    @Volatile private var parsedDevices: List<DeviceSpec> = emptyList()

    private data class DeviceSpec(
        val address: String,
        val name: String,
        val alias: String,
        val deviceClass: Int,
        val a2dp: Boolean,
        val headset: Boolean,
        val uuids: Array<ParcelUuid>
    ) {
        fun supports(profile: Int) = when (profile) {
            BluetoothProfile.A2DP -> a2dp
            BluetoothProfile.HEADSET -> headset
            else -> false
        }
    }

    private fun configuredDevices(): List<DeviceSpec> {
        val config = readConfig() ?: return emptyList()
        if (!shouldDeliver(config) || !config.optBoolean("specified_bluetooth_enabled", false)) return emptyList()
        if (config === parsedConfig) return parsedDevices
        return synchronized(this) {
            if (config === parsedConfig) parsedDevices else parseDevices(config).also {
                parsedDevices = it
                parsedConfig = config
            }
        }
    }

    private fun parseDevices(config: JSONObject): List<DeviceSpec> {
        val array = config.optJSONArray("specified_bluetooth_json") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val address = json.optString("address")
            if (!com.shiraka.wereposition.data.model.SpecifiedWifiConnection.MAC_REGEX.matches(address)) return@mapNotNull null
            DeviceSpec(
                address = address.uppercase(),
                name = json.optString("name"),
                alias = json.optString("alias"),
                deviceClass = json.optInt("deviceClass", 0),
                a2dp = json.optBoolean("a2dp", true),
                headset = json.optBoolean("headset", false),
                uuids = parseUuids(json.optJSONArray("uuids"))
            )
        }
    }

    private fun parseUuids(array: JSONArray?): Array<ParcelUuid> =
        if (array == null) emptyArray() else (0 until array.length()).mapNotNull { index ->
            runCatching { ParcelUuid(UUID.fromString(array.optString(index))) }.getOrNull()
        }.toTypedArray()

    private fun specFor(device: Any?): DeviceSpec? {
        val address = (device as? BluetoothDevice)?.address ?: return null
        return configuredDevices().firstOrNull { it.address.equals(address, ignoreCase = true) }
    }

    private fun remoteDevices(adapter: BluetoothAdapter?, profile: Int? = null): List<BluetoothDevice> {
        if (adapter == null) return emptyList()
        return configuredDevices().filter { profile == null || it.supports(profile) }.mapNotNull { spec ->
            runCatching { adapter.getRemoteDevice(spec.address) }.getOrNull()
        }
    }

    fun install(classLoader: ClassLoader) {
        val adapterClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothAdapter", classLoader) ?: return
        runCatching {
            XposedBridge.hookAllMethods(adapterClass, "getBondedDevices", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val additions = remoteDevices(param.thisObject as? BluetoothAdapter)
                    if (additions.isEmpty()) return
                    val original = (param.result as? Set<*>)?.filterIsInstance<BluetoothDevice>().orEmpty()
                    param.result = LinkedHashSet<BluetoothDevice>().apply {
                        addAll(original)
                        addAll(additions)
                    }
                }
            })
        }
        runCatching {
            XposedBridge.hookAllMethods(adapterClass, "getProfileConnectionState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = param.args.firstOrNull() as? Int ?: return
                    if (configuredDevices().any { it.supports(profile) }) {
                        param.result = BluetoothProfile.STATE_CONNECTED
                    }
                }
            })
        }

        val deviceClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothDevice", classLoader) ?: return
        val getters = listOf("getName", "getAlias", "getBluetoothClass", "getBondState", "getType", "getUuids", "isConnected")
        for (getter in getters) {
            runCatching {
                XposedBridge.hookAllMethods(deviceClass, getter, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val spec = specFor(param.thisObject) ?: return
                        when (getter) {
                            "getName" -> if (spec.name.isNotBlank()) param.result = spec.name
                            "getAlias" -> if (spec.alias.isNotBlank() || spec.name.isNotBlank()) {
                                param.result = spec.alias.ifBlank { spec.name }
                            }
                            "getBondState" -> param.result = BluetoothDevice.BOND_BONDED
                            "getType" -> param.result = BluetoothDevice.DEVICE_TYPE_CLASSIC
                            "getUuids" -> if (spec.uuids.isNotEmpty()) param.result = spec.uuids
                            "isConnected" -> param.result = true
                            "getBluetoothClass" -> {
                                if (spec.deviceClass <= 0) return
                                val klass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothClass", classLoader) ?: return
                                val constructor = klass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                                constructor.isAccessible = true
                                param.result = constructor.newInstance(spec.deviceClass)
                            }
                        }
                    }
                })
            }.onFailure { XposedBridge.log(it) }
        }

        for ((className, profile) in listOf(
            "android.bluetooth.BluetoothA2dp" to BluetoothProfile.A2DP,
            "android.bluetooth.BluetoothHeadset" to BluetoothProfile.HEADSET
        )) {
            val klass = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
            val connectedListHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestedStates = if (param.method?.name == "getDevicesMatchingConnectionStates") {
                        param.args.firstOrNull() as? IntArray
                    } else null
                    if (requestedStates != null && BluetoothProfile.STATE_CONNECTED !in requestedStates) return
                    val additions = remoteDevices(BluetoothAdapter.getDefaultAdapter(), profile)
                    if (additions.isEmpty()) return
                    val original = (param.result as? List<*>)?.filterIsInstance<BluetoothDevice>().orEmpty()
                    param.result = ArrayList<BluetoothDevice>().apply {
                        addAll(original)
                        additions.forEach { if (none { existing -> existing.address.equals(it.address, true) }) add(it) }
                    }
                }
            }
            for (name in listOf("getConnectedDevices", "getDevicesMatchingConnectionStates")) {
                runCatching { XposedBridge.hookAllMethods(klass, name, connectedListHook) }
            }
            runCatching {
                XposedBridge.hookAllMethods(klass, "getConnectionState", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (specFor(param.args.firstOrNull())?.supports(profile) == true) {
                            param.result = BluetoothProfile.STATE_CONNECTED
                        }
                    }
                })
            }
        }
    }
}
