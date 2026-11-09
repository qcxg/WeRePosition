package com.shiraka.wereposition.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

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
    onOpenScanner: () -> Unit,
    onLocate: (Double, Double) -> Unit
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    
    var editingItem by remember { mutableStateOf<CompleteLocation?>(null) }
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
    val groupedData = remember(dataList, uiState.nearbyPlaceNames) {
        groupManagedLocations(dataList, uiState.nearbyPlaceNames)
    }
    LaunchedEffect(dataList) {
        viewModel.refreshNearbyPlaceNames(dataList)
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
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item(key = "collection-actions") {
                    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.spatial_env_collection), style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (uiState.isContinuousScanning)
                                stringResource(R.string.scanning_reference_points, uiState.environmentRecordCount)
                            else stringResource(R.string.view_heatmap_start_scan),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(onClick = onOpenScanner, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Map, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.env_map_scan))
                        }
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
                                onLocate(item.lat, item.lng)
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
    val density = LocalDensity.current
    val revealWidth = with(density) { 80.dp.toPx() }
    var dragOffset by remember(group.ids) { mutableFloatStateOf(0f) }
    var dragging by remember(group.ids) { mutableStateOf(false) }
    var revealDelete by remember(group.ids) { mutableStateOf(false) }
    val offset by animateFloatAsState(
        targetValue = if (dragging) dragOffset else if (revealDelete) -revealWidth else 0f,
        label = "recordSwipe"
    )
    Column {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
            Box(Modifier.align(Alignment.CenterEnd).width(80.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                IconButton(onClick = onDeleteSingle) {
                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .offset { IntOffset(offset.roundToInt(), 0) }
                    .pointerInput(group.ids, isSelectionMode) {
                        if (!isSelectionMode) detectHorizontalDragGestures(
                            onDragStart = { dragging = true; dragOffset = if (revealDelete) -revealWidth else 0f },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                dragOffset = (dragOffset + amount).coerceIn(-revealWidth, 0f)
                            },
                            onDragEnd = { revealDelete = dragOffset < -revealWidth / 2f; dragging = false },
                            onDragCancel = { dragging = false }
                        )
                    }
                    .combinedClickable(
                        onClick = { if (isSelectionMode) onSelect() else onEdit() },
                        onLongClick = onLongClick
                    ),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (isSelectionMode) Checkbox(isSelected, onCheckedChange = { onSelect() })
                    Icon(Icons.Rounded.Place, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.nearbyName.ifBlank { timeStr }, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(String.format(Locale.US, "%.5f, %.5f · %s", group.lat, group.lng, timeStr),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Wi-Fi ${group.wifiCount} · ${stringResource(R.string.sample_cell_count, group.cellCount)}" +
                            if (group.bluetoothCount > 0) " · Bluetooth ${group.bluetoothCount}" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (group.wifiSources.contains("虛擬資料") || group.cellSources.contains("虛擬資料")) {
                            Text(stringResource(R.string.synthetic_record), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    if (!isSelectionMode) IconButton(onClick = onClick) {
                        Icon(Icons.Rounded.MyLocation, stringResource(R.string.open_on_map), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
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
    if (groupText.contains("[SYNTHETIC_DATA]")) return listOf("虛擬資料")
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
