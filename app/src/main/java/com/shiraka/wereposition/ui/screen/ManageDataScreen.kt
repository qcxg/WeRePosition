package com.shiraka.wereposition.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiraka.wereposition.R
import com.shiraka.wereposition.data.db.CompleteLocation
import com.shiraka.wereposition.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shiraka.wereposition.ui.components.AppMapView
import com.shiraka.wereposition.ui.components.AppMapController
import com.shiraka.wereposition.utils.MapCoverageHelper

private data class ManagedLocationGroup(
    val ids: List<Long>,
    val first: CompleteLocation,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val nearbyName: String,
    val wifiCount: Int,
    val cellCount: Int,
    val bluetoothCount: Int,
    val wifiSources: List<String>,
    val cellSources: List<String>,
    val bluetoothSources: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageDataScreen(
    viewModel: MainViewModel,
    uiState: com.shiraka.wereposition.data.model.AppState,
    isDark: Boolean,
    onOpenScanner: () -> Unit
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var mapController by remember { mutableStateOf<AppMapController?>(null) }
    
    var editingItem by remember { mutableStateOf<CompleteLocation?>(null) }
    var showSamplePreview by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportEnvironmentData(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importEnvironmentData(it) {
                Toast.makeText(context, R.string.import_data, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val dataList = uiState.manageDataList
    val coverageLocations by viewModel.coverageLocations.collectAsState()
    val latestCoverageLocations = rememberUpdatedState(coverageLocations)
    val groupedData = remember(dataList, uiState.nearbyPlaceNames) {
        groupManagedLocations(dataList, uiState.nearbyPlaceNames)
    }
    LaunchedEffect(dataList) {
        viewModel.refreshNearbyPlaceNames(dataList)
    }
    
    LaunchedEffect(mapController, uiState.mapType) {
        mapController?.setMapType(uiState.mapType)
    }

    LaunchedEffect(mapController, coverageLocations) {
        val controller = mapController ?: return@LaunchedEffect
        if (coverageLocations.isNotEmpty()) {
            val latest = coverageLocations.first()
            controller.moveCamera(latest.lat, latest.lng, 15f)
        }
        MapCoverageHelper.drawCoverage(controller, coverageLocations)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(stringResource(R.string.selected_items, selectedIds.size))
                    } else {
                        Text(stringResource(R.string.title_manage_data))
                    }
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedIds.clear()
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            val allIds = groupedData.flatMap { it.ids }
                            if (selectedIds.size == allIds.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(allIds)
                            }
                        }) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                        if (selectedIds.isNotEmpty()) {
                            IconButton(onClick = {
                                viewModel.deleteManageData(selectedIds.toList())
                                isSelectionMode = false
                                selectedIds.clear()
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(Icons.Rounded.Checklist, contentDescription = stringResource(R.string.select_all))
                        }
                        IconButton(onClick = { showClearAllConfirm = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.clear_all), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "collection-actions") {
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.spatial_env_collection), style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (uiState.isContinuousScanning)
                                stringResource(R.string.scanning_reference_points, uiState.environmentRecordCount)
                            else stringResource(R.string.view_heatmap_start_scan),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onOpenScanner, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Map, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.env_map_scan))
                        }
                        HorizontalDivider()
                        Text(stringResource(R.string.env_data_sharing_desc), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.import_data)) }
                            OutlinedButton(
                                onClick = { exportLauncher.launch("environment_data.json") },
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.export_data)) }
                        }
                    }
                }
            }

            item(key = "preview-toggle") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sample_preview_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = showSamplePreview, onCheckedChange = { showSamplePreview = it })
                }
            }
            if (showSamplePreview) {
                item(key = "preview-taipei") {
                    SampleEnvironmentCard("台北 101", "25.03397, 121.56447", 4, 2)
                }
                item(key = "preview-tokyo") {
                    SampleEnvironmentCard("東京車站", "35.68124, 139.76713", 6, 1)
                }
                item(key = "preview-note") {
                    Text(stringResource(R.string.sample_preview_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (coverageLocations.isNotEmpty()) {
                item(key = "coverage-map") {
                    Box(Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.large)) {
                        AppMapView(
                            modifier = Modifier.fillMaxSize(),
                            onMapReady = { controller ->
                                mapController = controller
                                controller.disableUiControls()
                                controller.setOnCameraChangeListener { _, _ ->
                                    MapCoverageHelper.drawCoverage(controller, latestCoverageLocations.value)
                                }
                            }
                        )
                    }
                }
            }

            item(key = "records-heading") {
                Text(stringResource(R.string.title_manage_data), style = MaterialTheme.typography.titleMedium)
            }
            if (uiState.manageDataIsLoading) {
                item { Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (groupedData.isEmpty()) {
                item { Text(stringResource(R.string.no_data_collected), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(groupedData, key = { "record-${it.ids.joinToString("-")}" }) { item ->
                        val isSelected = item.ids.all { selectedIds.contains(it) }
                        DataListItem(
                            group = item,
                            isDark = isDark,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onSelect = {
                                if (isSelected) selectedIds.removeAll(item.ids.toSet())
                                else selectedIds.addAll(item.ids.filterNot { selectedIds.contains(it) })
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedIds.addAll(item.ids)
                                }
                            },
                            onClick = {
                                viewModel.updateLatitude(String.format(Locale.US, "%.6f", item.lat))
                                viewModel.updateLongitude(String.format(Locale.US, "%.6f", item.lng))
                                mapController?.animateCamera(item.lat, item.lng, 17f)
                            },
                            onDeleteSingle = {
                                viewModel.deleteManageData(item.ids)
                            },
                            onEdit = {
                                editingItem = item.first
                            }
                        )
                }
            }

        }
    }

    if (editingItem != null) {
        val editingLocation = editingItem!!.location
        val editingKey = placeKey(editingLocation.lat, editingLocation.lng)
        val initialPlaceName = editingLocation.placeName
            .takeIf { it.isNotBlank() && !isImportSourceText(it) && isReadablePlaceNameForUi(it) }
            ?: uiState.nearbyPlaceNames[editingKey].orEmpty()
        val initialRemark = editingLocation.remark
            .takeUnless { it.startsWith("经纬度") || it.startsWith("經緯度") }
            .orEmpty()
        var placeName by remember(editingItem, uiState.nearbyPlaceNames) { mutableStateOf(initialPlaceName) }
        var remark by remember(editingItem) { mutableStateOf(initialRemark) }
        
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text(stringResource(R.string.edit_location_data)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = placeName,
                        onValueChange = { placeName = it },
                        label = { Text(stringResource(R.string.place_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remark,
                        onValueChange = { remark = it },
                        label = { Text(stringResource(R.string.remark_note)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateManageDataMetadata(editingItem!!.location.id, placeName, remark)
                    editingItem = null
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.clear_all_data)) },
            text = { Text(stringResource(R.string.clear_all_data_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllManageData()
                        showClearAllConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SampleEnvironmentCard(name: String, coordinates: String, wifiCount: Int, cellCount: Int) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(coordinates, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Wi-Fi $wifiCount", style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.sample_cell_count, cellCount), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DataListItem(
    group: ManagedLocationGroup,
    isDark: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDeleteSingle: () -> Unit,
    onEdit: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(group.timestamp) { dateFormat.format(Date(group.timestamp)) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { 
                    if (isSelectionMode) onSelect() 
                    else onClick()
                },
                onLongClick = onLongClick
            ),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else com.shiraka.wereposition.ui.theme.AppColors.cardBackground(isDark),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() },
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = String.format(Locale.US, "%.5f, %.5f", group.lat, group.lng),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconTextRow(Icons.Rounded.Wifi, sourceText(group.wifiCount, group.wifiSources))
                    IconTextRow(Icons.Rounded.CellTower, sourceText(group.cellCount, group.cellSources))
                    if (group.bluetoothCount > 0) {
                        IconTextRow(Icons.Rounded.Bluetooth, sourceText(group.bluetoothCount, group.bluetoothSources))
                    }
                }
                
                if (group.nearbyName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "此地點位於 ${group.nearbyName} 附近",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            if (!isSelectionMode) {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.edit_location_data),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = onDeleteSingle) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconTextRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

private fun groupManagedLocations(
    records: List<CompleteLocation>,
    nearbyNames: Map<String, String>
): List<ManagedLocationGroup> {
    return records
        .groupBy { placeKey(it.location.lat, it.location.lng) }
        .values
        .map { group ->
            val first = group.minByOrNull { it.location.timestamp } ?: group.first()
            val key = placeKey(first.location.lat, first.location.lng)
            val placeName = nearbyNames[key]
                ?: group.map { it.location.placeName }.firstOrNull { it.isNotBlank() && !isImportSourceText(it) && isReadablePlaceNameForUi(it) }
                ?: ""

            ManagedLocationGroup(
                ids = group.map { it.location.id },
                first = first,
                lat = first.location.lat,
                lng = first.location.lng,
                timestamp = group.maxOf { it.location.timestamp },
                nearbyName = placeName,
                wifiCount = group.sumOf { it.wifis.size },
                cellCount = group.sumOf { it.cells.size },
                bluetoothCount = group.sumOf { it.bluetooths.size },
                wifiSources = sourceLabelsForGroup(group, "wifi"),
                cellSources = sourceLabelsForGroup(group, "cell"),
                bluetoothSources = sourceLabelsForGroup(group, "bluetooth")
            )
        }
        .sortedByDescending { it.timestamp }
}

private fun sourceText(count: Int, sources: List<String>): String {
    if (count <= 0) return "0"
    val labels = sources.map { it.trim().trimStart('/') }.filter { it.isNotBlank() }
    return if (labels.isEmpty()) "$count" else "$count · ${labels.joinToString(" · ")}"
}

private fun placeKey(lat: Double, lng: Double): String {
    return String.format(Locale.US, "%.6f,%.6f", lat, lng)
}

private fun sourceLabelsForGroup(group: List<CompleteLocation>, kind: String): List<String> {
    val records = group.filter {
        when (kind) {
            "wifi" -> it.wifis.isNotEmpty()
            "cell" -> it.cells.isNotEmpty()
            "bluetooth" -> it.bluetooths.isNotEmpty()
            else -> false
        }
    }
    if (records.isEmpty()) return emptyList()

    val groupText = group.joinToString(" ") { "${it.location.placeName} ${it.location.remark}" }
    val labels = mutableListOf<String>()
    if (kind == "wifi" && groupText.contains("WiGLE", ignoreCase = true)) {
        labels += "由 WiGLE 導入"
    }
    if (kind == "cell" && groupText.contains("OpenCellID", ignoreCase = true)) {
        labels += "由 OpenCellID 導入"
    }
    if (labels.isEmpty() && isImportSourceText(groupText)) {
        labels += if (kind == "cell") "由 OpenCellID 導入" else "由 WiGLE 導入"
    }
    if (labels.isEmpty()) {
        labels += "本地采集"
    }
    return labels.distinct()
}

private fun isImportSourceText(text: String): Boolean {
    return text.contains("WiGLE", ignoreCase = true) ||
        text.contains("OpenCellID", ignoreCase = true) ||
        text.contains("导入") ||
        text.contains("導入") ||
        text.contains("Import", ignoreCase = true)
}

private fun isReadablePlaceNameForUi(text: String): Boolean {
    val name = text.trim()
    if (name.isBlank()) return false
    if (name.equals("Unnamed Road", ignoreCase = true)) return false
    if (name.contains("+")) return false
    return !name.matches(Regex("[0-9０-９\\-－ー丁目番地号之の\\s]+"))
}
