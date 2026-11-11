package com.shiraka.wereposition.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import com.shiraka.wereposition.data.model.SpecifiedBluetoothDevice
import com.shiraka.wereposition.data.model.SpecifiedWifiConnection

data class SavedWifiNetwork(val networkId: Int, val ssid: String, val security: String)

/** Reads only connection metadata. Saved Wi-Fi credentials are never opened or copied. */
class SavedConnectionImporter(private val context: Context) {
    suspend fun savedWifiNetworks(): List<SavedWifiNetwork> {
        val output = RootManager().executeCommand("cmd wifi list-networks")
        if (output == "ERROR") return emptyList()
        val rows = output.lineSequence().mapNotNull { line ->
            val match = SAVED_WIFI_ROW.matchEntire(line) ?: return@mapNotNull null
            SavedWifiNetwork(match.groupValues[1].toInt(), match.groupValues[2].trim(), match.groupValues[3])
        }
        return rows.distinctBy { it.networkId }.toList()
    }

    @SuppressLint("MissingPermission")
    fun bondedClassicBluetooth(): List<SpecifiedBluetoothDevice> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .asSequence()
            .filter { it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
            .map { device ->
                SpecifiedBluetoothDevice(
                    address = device.address.orEmpty(),
                    name = device.name.orEmpty(),
                    alias = device.alias.orEmpty(),
                    deviceClass = device.bluetoothClass?.let { it.majorDeviceClass or it.deviceClass } ?: 0,
                    uuids = device.uuids?.map { it.uuid.toString() }.orEmpty()
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun fillCurrentWifi(network: SavedWifiNetwork): SpecifiedWifiConnection {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
        val info = runCatching { manager?.connectionInfo }.getOrNull()
        val currentSsid = info?.ssid?.removeSurrounding("\"")
        val matching = currentSsid == network.ssid
        val dhcp = if (matching) runCatching { manager?.dhcpInfo }.getOrNull() else null
        return SpecifiedWifiConnection(
            ssid = network.ssid,
            bssid = if (matching) info?.bssid.orEmpty() else "",
            macAddress = if (matching) info?.macAddress.orEmpty() else "",
            frequency = if (matching) info?.frequency ?: 2412 else 2412,
            rssi = if (matching) info?.rssi ?: -55 else -55,
            linkSpeed = if (matching) info?.linkSpeed ?: 150 else 150,
            wifiStandard = if (matching) info?.wifiStandard ?: 4 else 4,
            networkId = network.networkId,
            ipAddress = dhcp?.ipAddress?.let(::ipv4FromLittleEndian).orEmpty(),
            gateway = dhcp?.gateway?.let(::ipv4FromLittleEndian).orEmpty(),
            dns1 = dhcp?.dns1?.let(::ipv4FromLittleEndian).orEmpty(),
            dns2 = dhcp?.dns2?.let(::ipv4FromLittleEndian).orEmpty(),
            security = network.security,
            capabilities = when {
                network.security.contains("sae", true) -> "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]"
                network.security.contains("wpa2", true) -> "[WPA2-PSK-CCMP][ESS]"
                network.security.contains("owe", true) -> "[OWE][ESS]"
                else -> "[ESS]"
            }
        )
    }

    private fun ipv4FromLittleEndian(value: Int): String =
        (0..3).joinToString(".") { offset -> ((value ushr (offset * 8)) and 0xff).toString() }

    companion object {
        private val SAVED_WIFI_ROW = Regex("^\\s*(\\d+)\\s{2,}(.+?)\\s{2,}([^\\s]+)\\s*$")
    }
}
