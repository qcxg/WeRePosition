package com.shiraka.wereposition.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shiraka.wereposition.R
import com.shiraka.wereposition.data.model.AppState
import com.shiraka.wereposition.data.model.SpecifiedBluetoothDevice
import com.shiraka.wereposition.data.model.SpecifiedWifiConnection
import com.shiraka.wereposition.utils.SavedConnectionImporter
import com.shiraka.wereposition.utils.SavedWifiNetwork
import com.shiraka.wereposition.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
internal fun SpecifiedConnectionsSettings(viewModel: MainViewModel, uiState: AppState) {
    val context = LocalContext.current
    val importer = remember(context) { SavedConnectionImporter(context) }
    val scope = rememberCoroutineScope()
    var wifiEnabled by remember(uiState.specifiedWifiEnabled) { mutableStateOf(uiState.specifiedWifiEnabled) }
    var wifi by remember(uiState.specifiedWifi) { mutableStateOf(uiState.specifiedWifi) }
    var ipAddress by remember(uiState.specifiedWifi) { mutableStateOf(uiState.specifiedWifi.ipAddress) }
    var gateway by remember(uiState.specifiedWifi) { mutableStateOf(uiState.specifiedWifi.gateway) }
    var dns1 by remember(uiState.specifiedWifi) { mutableStateOf(uiState.specifiedWifi.dns1) }
    var dns2 by remember(uiState.specifiedWifi) { mutableStateOf(uiState.specifiedWifi.dns2) }
    var frequency by remember(uiState.specifiedWifi) { mutableStateOf(uiState.specifiedWifi.frequency.toString()) }
    var rssi by remember(uiState.specifiedWifi) { mutableStateOf(uiState.specifiedWifi.rssi.toString()) }
    var linkSpeed by remember(uiState.specifiedWifi) { mutableStateOf(uiState.specifiedWifi.linkSpeed.toString()) }
    var wifiStandard by remember(uiState.specifiedWifi) { mutableStateOf(uiState.specifiedWifi.wifiStandard.toString()) }
    var savedNetworks by remember { mutableStateOf<List<SavedWifiNetwork>>(emptyList()) }
    var showWifiImport by remember { mutableStateOf(false) }
    var bluetoothEnabled by remember(uiState.specifiedBluetoothEnabled) { mutableStateOf(uiState.specifiedBluetoothEnabled) }
    var deviceDrafts by remember(uiState.specifiedBluetoothDevices) {
        mutableStateOf(uiState.specifiedBluetoothDevices.map(::BluetoothDeviceDraft))
    }
    var bondedDevices by remember { mutableStateOf<List<SpecifiedBluetoothDevice>>(emptyList()) }
    var showBluetoothImport by remember { mutableStateOf(false) }
    val savedText = stringResource(R.string.settings_saved)
    val unavailableText = stringResource(R.string.specified_import_unavailable)
    val permissionText = stringResource(R.string.specified_bluetooth_permission)

    fun importBluetooth() {
        bondedDevices = runCatching { importer.bondedClassicBluetooth() }.getOrDefault(emptyList())
        if (bondedDevices.isEmpty()) Toast.makeText(context, unavailableText, Toast.LENGTH_SHORT).show()
        else showBluetoothImport = true
    }

    fun updateDevice(index: Int, transform: (BluetoothDeviceDraft) -> BluetoothDeviceDraft) {
        deviceDrafts = deviceDrafts.toMutableList().also { it[index] = transform(it[index]) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) importBluetooth()
        else Toast.makeText(context, permissionText, Toast.LENGTH_SHORT).show()
    }

    Text(stringResource(R.string.specified_connections_summary), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.specified_wifi), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = wifiEnabled, onCheckedChange = { enabled ->
            wifiEnabled = enabled
            if (!enabled) viewModel.setSpecifiedWifi(false, uiState.specifiedWifi)
        })
    }
    if (wifiEnabled) {
    OutlinedButton(onClick = {
        scope.launch {
            savedNetworks = importer.savedWifiNetworks()
            if (savedNetworks.isEmpty()) Toast.makeText(context, unavailableText, Toast.LENGTH_SHORT).show()
            else showWifiImport = true
        }
    }) { Text(stringResource(R.string.specified_import_saved_wifi)) }
    Text(stringResource(R.string.specified_wifi_import_note), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    SpecifiedField(wifi.ssid, { wifi = wifi.copy(ssid = it) }, stringResource(R.string.specified_ssid))
    SpecifiedField(wifi.bssid, { wifi = wifi.copy(bssid = it) }, stringResource(R.string.specified_bssid))
    SpecifiedField(wifi.macAddress, { wifi = wifi.copy(macAddress = it) }, stringResource(R.string.specified_mac))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpecifiedField(frequency, { frequency = it }, stringResource(R.string.specified_frequency), Modifier.weight(1f))
        SpecifiedField(rssi, { rssi = it }, stringResource(R.string.specified_rssi), Modifier.weight(1f))
    }
    SpecifiedField(linkSpeed, { linkSpeed = it }, stringResource(R.string.specified_link_speed))
    SpecifiedField(wifiStandard, { wifiStandard = it }, stringResource(R.string.specified_wifi_standard))
    SpecifiedField(wifi.capabilities, { wifi = wifi.copy(capabilities = it) }, stringResource(R.string.specified_capabilities))
    SpecifiedField(ipAddress, { ipAddress = it }, stringResource(R.string.specified_ip))
    SpecifiedField(gateway, { gateway = it }, stringResource(R.string.specified_gateway))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpecifiedField(dns1, { dns1 = it }, "DNS 1", Modifier.weight(1f))
        SpecifiedField(dns2, { dns2 = it }, "DNS 2", Modifier.weight(1f))
    }
    }
    val wifiDraft = wifi.copy(ipAddress = ipAddress, gateway = gateway, dns1 = dns1, dns2 = dns2)
    if (wifiEnabled && !wifiDraft.isValid) {
        Text(stringResource(R.string.specified_wifi_validation), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    if (wifiEnabled) Button(onClick = {
        val value = wifi.copy(
            frequency = frequency.toIntOrNull()?.coerceIn(1, 71000) ?: 2412,
            rssi = rssi.toIntOrNull()?.coerceIn(-127, 0) ?: -55,
            linkSpeed = linkSpeed.toIntOrNull()?.coerceAtLeast(1) ?: 150,
            wifiStandard = wifiStandard.toIntOrNull()?.coerceIn(1, 8) ?: 4,
            ipAddress = ipAddress, gateway = gateway, dns1 = dns1, dns2 = dns2
        )
        viewModel.setSpecifiedWifi(wifiEnabled, value)
        Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
    }, enabled = !wifiEnabled || wifiDraft.isValid) { Text(stringResource(R.string.save)) }

    HorizontalDivider(Modifier.padding(vertical = 20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.specified_bluetooth), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = bluetoothEnabled, onCheckedChange = { enabled ->
            bluetoothEnabled = enabled
            if (enabled && deviceDrafts.isEmpty()) deviceDrafts = listOf(BluetoothDeviceDraft(SpecifiedBluetoothDevice()))
            if (!enabled) viewModel.setSpecifiedBluetooth(false, uiState.specifiedBluetoothDevices)
        })
    }
    if (bluetoothEnabled) {
    Text(stringResource(R.string.specified_bluetooth_summary), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else importBluetooth()
        }) { Text(stringResource(R.string.specified_import_bluetooth)) }
        TextButton(onClick = { deviceDrafts = deviceDrafts + BluetoothDeviceDraft(SpecifiedBluetoothDevice()) }) {
            Text(stringResource(R.string.specified_add_device))
        }
    }
    deviceDrafts.forEachIndexed { index, draft ->
        val device = draft.device
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(stringResource(R.string.specified_device_number, index + 1), style = MaterialTheme.typography.labelLarge)
            SpecifiedField(device.name, { value -> updateDevice(index) { it.copy(device = it.device.copy(name = value)) } }, stringResource(R.string.specified_device_name))
            SpecifiedField(device.address, { value -> updateDevice(index) { it.copy(device = it.device.copy(address = value)) } }, stringResource(R.string.specified_mac))
            SpecifiedField(device.alias, { value -> updateDevice(index) { it.copy(device = it.device.copy(alias = value)) } }, stringResource(R.string.specified_device_alias))
            SpecifiedField(draft.deviceClassText, { value -> updateDevice(index) { it.copy(deviceClassText = value) } }, stringResource(R.string.specified_device_class))
            SpecifiedField(draft.uuidsText, { value -> updateDevice(index) { it.copy(uuidsText = value) } }, stringResource(R.string.specified_device_uuids))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = device.a2dp, onCheckedChange = { checked -> updateDevice(index) { it.copy(device = it.device.copy(a2dp = checked)) } })
                Text("A2DP")
                Checkbox(checked = device.headset, onCheckedChange = { checked -> updateDevice(index) { it.copy(device = it.device.copy(headset = checked)) } })
                Text("Headset")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { deviceDrafts = deviceDrafts.toMutableList().also { it.removeAt(index) } }) { Text(stringResource(R.string.delete)) }
            }
        }
    }
    }
    if (bluetoothEnabled && (deviceDrafts.isEmpty() || deviceDrafts.any { !it.isValid })) {
        Text(stringResource(R.string.specified_bluetooth_validation),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (bluetoothEnabled) Button(onClick = {
        viewModel.setSpecifiedBluetooth(true, deviceDrafts.map { it.toDevice() })
        Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
    }, enabled = deviceDrafts.isNotEmpty() && deviceDrafts.all { it.isValid }) {
        Text(stringResource(R.string.save))
    }

    if (showWifiImport) {
        SavedConnectionDialog(
            title = stringResource(R.string.specified_import_saved_wifi),
            rows = savedNetworks.map { it.ssid + " · " + it.security },
            onDismiss = { showWifiImport = false },
            onSelect = { index ->
                wifi = importer.fillCurrentWifi(savedNetworks[index])
                frequency = wifi.frequency.toString()
                rssi = wifi.rssi.toString()
                linkSpeed = wifi.linkSpeed.toString()
                wifiStandard = wifi.wifiStandard.toString()
                ipAddress = wifi.ipAddress
                gateway = wifi.gateway
                dns1 = wifi.dns1
                dns2 = wifi.dns2
                showWifiImport = false
            }
        )
    }
    if (showBluetoothImport) {
        SavedConnectionDialog(
            title = stringResource(R.string.specified_import_bluetooth),
            rows = bondedDevices.map { "${it.name.ifBlank { it.address }} · ${it.address}" },
            onDismiss = { showBluetoothImport = false },
            onSelect = { index ->
                val selected = bondedDevices[index]
                deviceDrafts = deviceDrafts.filter {
                    it.device.address.isNotBlank() && !it.device.address.equals(selected.address, true)
                } + BluetoothDeviceDraft(selected)
                showBluetoothImport = false
            }
        )
    }
}

@Composable
private fun SpecifiedField(value: String, onValueChange: (String) -> Unit, label: String,
                           modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        singleLine = true, modifier = modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SavedConnectionDialog(title: String, rows: List<String>, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                rows.forEachIndexed { index, label ->
                    TextButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private data class BluetoothDeviceDraft(
    val device: SpecifiedBluetoothDevice,
    val deviceClassText: String = device.deviceClass.toString(),
    val uuidsText: String = device.uuids.joinToString(", ")
) {
    private val parsedUuids: List<String>
        get() = uuidsText.split(',').map(String::trim).filter(String::isNotBlank)

    val isValid: Boolean
        get() = deviceClassText.toIntOrNull() in 0..0xFFFFFF &&
            device.copy(uuids = parsedUuids).isValid

    fun toDevice(): SpecifiedBluetoothDevice = device.copy(
        deviceClass = deviceClassText.toInt(),
        uuids = parsedUuids
    )
}
