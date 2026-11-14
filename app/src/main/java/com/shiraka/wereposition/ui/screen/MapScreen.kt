package com.shiraka.wereposition.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.shiraka.wereposition.ui.components.GlassOverlaySurface
import com.shiraka.wereposition.ui.components.AppMapView
import com.shiraka.wereposition.ui.components.AppMapController
import com.shiraka.wereposition.ui.components.AppMapMarker
import com.shiraka.wereposition.ui.components.MarkerType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import com.shiraka.wereposition.R
import com.shiraka.wereposition.data.model.AppState
import com.shiraka.wereposition.data.model.AppMapType
import com.shiraka.wereposition.ui.components.MapTypeDialog
import com.shiraka.wereposition.data.model.RoutePoint
import com.shiraka.wereposition.data.model.RoutePlanStage
import com.shiraka.wereposition.data.model.SimMode
import com.shiraka.wereposition.ui.theme.AppColors
import com.shiraka.wereposition.viewmodel.MainViewModel
import kotlin.math.*

// 全屏路线规划页面

@Composable
@Suppress("UNUSED_PARAMETER")
fun FullScreenMapPage(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean,
    isInPipMode: Boolean = false,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val currentLocationLabel = stringResource(R.string.current_location)
    val coordinateSelectedMessage = stringResource(R.string.coordinate_selected)
    val saveSuccessMessage = stringResource(R.string.save_success)
    var mapRef by remember { mutableStateOf<AppMapController?>(null) }
    var cameraLat by rememberSaveable { mutableStateOf(uiState.latitudeInput.toDoubleOrNull() ?: 0.0) }
    var cameraLng by rememberSaveable { mutableStateOf(uiState.longitudeInput.toDoubleOrNull() ?: 0.0) }
    var cameraZoom by rememberSaveable { mutableFloatStateOf(18f) }
    fun rememberViewport(map: AppMapController) {
        map.cameraTargetLat?.let { cameraLat = it }
        map.cameraTargetLng?.let { cameraLng = it }
        map.cameraZoom?.let { cameraZoom = it }
    }
    var showSavedLocations by remember { mutableStateOf(false) }
    var showMapTypeDialog by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var showSavedRoutesDialog by remember { mutableStateOf(false) }
    val coverageLocations by viewModel.coverageLocations.collectAsState()
    val latestCoverageLocations = rememberUpdatedState(coverageLocations)
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<AppPoiItem>>(emptyList()) }
    var showSearchResults by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val mapHazeState = rememberHazeState()

    // 拦截返回键：如果有搜索结果，按返回键先关闭搜索结果
    BackHandler(enabled = showSearchResults) {
        showSearchResults = false
    }

    val stage = uiState.routePlanStage
    val routePoints = uiState.routePoints

    // 同步地图类型
    LaunchedEffect(mapRef, uiState.mapType) {
        mapRef?.setMapType(uiState.mapType)
    }

    // 同步路点标记和折线到地图
    var liveMarker by remember { mutableStateOf<AppMapMarker?>(null) }
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    LaunchedEffect(routePoints, mapRef, routeColor) {
        val map = mapRef ?: return@LaunchedEffect
        map.clear()
        liveMarker = null
        com.shiraka.wereposition.utils.MapCoverageHelper.drawCoverage(map, coverageLocations)

        if (routePoints.size >= 2) {
            map.addPolyline(
                routePoints.map { Pair(it.lat, it.lng) },
                routeColor,
                8f
            )
        }
        routePoints.forEachIndexed { idx, p ->
            val type = when (idx) {
                0 -> MarkerType.GREEN
                routePoints.lastIndex -> MarkerType.RED
                else -> MarkerType.DEFAULT
            }
            
            map.addMarker(p.lat, p.lng, "${idx + 1}", type)
        }

        // 确保被 clear() 清除的实时位置图标能够重新绘制
        if (uiState.isSpoofingActive) {
            val currentLat = uiState.latitudeInput.toDoubleOrNull()
            val currentLng = uiState.longitudeInput.toDoubleOrNull()
            if (currentLat != null && currentLng != null) {
                liveMarker = map.addMarker(
                    currentLat, currentLng,
                    currentLocationLabel,
                    MarkerType.ORANGE
                )
            }
        }
    }

    // Coverage owns separate polygon overlays. New scan rows should not clear and rebuild
    // route polylines and markers, which would otherwise flash every time Room emits.
    LaunchedEffect(mapRef, coverageLocations) {
        val map = mapRef ?: return@LaunchedEffect
        com.shiraka.wereposition.utils.MapCoverageHelper.drawCoverage(map, coverageLocations)
    }

    // 运行中时跟踪实时位置
    val lat = uiState.latitudeInput.toDoubleOrNull()
    val lng = uiState.longitudeInput.toDoubleOrNull()
    LaunchedEffect(lat, lng, uiState.isSpoofingActive, uiState.routePlanStage) {
        if (uiState.isSpoofingActive && lat != null && lng != null) {
            // 只有在非路线规划运行时才跟随坐标点移动镜头，如果是路线规划则需要纵览全局
            if (uiState.routePlanStage != RoutePlanStage.RUNNING) {
                mapRef?.animateCamera(lat, lng)
            }
            // 更新或创建实时位置标记
            if (liveMarker != null) {
                liveMarker?.setPosition(lat, lng)
            } else {
                liveMarker = mapRef?.addMarker(
                    lat, lng,
                    currentLocationLabel,
                    MarkerType.ORANGE
                )
            }
        }
    }

    // 根据路线和模式自动缩放
    // Point edits repaint overlays only. Fit the route once when entering RUNNING
    // (or attaching a new map), never as a side effect of adding/removing a point.
    LaunchedEffect(mapRef, isInPipMode, uiState.routePlanStage) {
        if (uiState.routePlanStage == RoutePlanStage.RUNNING && routePoints.size >= 2) {
            // 路线规划运行时，显示完整的路线范围
            val padding = if (isInPipMode) 30 else 150
            mapRef?.fitBounds(routePoints.map { Pair(it.lat, it.lng) }, padding)
        } else if (isInPipMode) {
            // 单点定位进入小窗时，放大到 18f
            val currentLat = uiState.latitudeInput.toDoubleOrNull()
            val currentLng = uiState.longitudeInput.toDoubleOrNull()
            if (currentLat != null && currentLng != null) {
                mapRef?.animateCamera(currentLat, currentLng, 18f)
            }
        }
    }

    // 就绪时弹出配置弹窗
    LaunchedEffect(stage) {
        if (stage == RoutePlanStage.READY) showConfigDialog = true
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // Sample only the map, so controls never blur themselves.
        Box(Modifier.fillMaxSize().hazeSource(mapHazeState)) {
        AppMapView(modifier = Modifier.fillMaxSize()) { map ->
            mapRef = map
            map.disableUiControls()
            map.moveCamera(cameraLat, cameraLng, cameraZoom)
            map.setOnCameraChangeListener { _, _ ->
                rememberViewport(map)
                com.shiraka.wereposition.utils.MapCoverageHelper.drawCoverage(
                    map,
                    latestCoverageLocations.value
                )
            }
        }

        }

        // 选点模式的十字准星
        if (!isInPipMode && (stage == RoutePlanStage.SELECTING || stage == RoutePlanStage.IDLE)) {
            Icon(
                Icons.Rounded.AddLocationAlt, null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center).size(40.dp).padding(bottom = 16.dp)
            )
        }

        // 顶部栏（含搜索）
        if (!isInPipMode) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
            TopBar(
                stage = stage,
                routePointCount = routePoints.size,
                onBack = onClose
            )
            // 搜索栏
            if (stage == RoutePlanStage.SELECTING || stage == RoutePlanStage.IDLE) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassOverlaySurface(hazeState = mapHazeState,
                        modifier = Modifier.weight(1f).shadow(4.dp, MaterialTheme.shapes.extraLarge),
                        shape = MaterialTheme.shapes.extraLarge) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            if (searchQuery.isNotBlank()) {
                                performPoiSearch(context, searchQuery) { r ->
                                    searchResults = r
                                    showSearchResults = r.isNotEmpty()
                                }
                            }
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(start = 16.dp, end = 4.dp),
                        decorationBox = { innerTextField ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                Spacer(Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(stringResource(R.string.search_location_hint), fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                    }
                                    innerTextField()
                                }
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = ""; showSearchResults = false },
                                        modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Rounded.Close, stringResource(R.string.clear_search),
                                            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    )
                    }
                }
                // 搜索结果
                AnimatedVisibility(visible = showSearchResults && searchResults.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    ) {
                        LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                            items(searchResults.take(15)) { poi ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                        val pLat = poi.lat
                                        val pLng = poi.lng
                                        mapRef?.animateCamera(pLat, pLng, 16f)
                                        showSearchResults = false
                                        searchQuery = poi.title
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(poi.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                                        Text(poi.snippet, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        }
        } // End of if(!isInPipMode) for TopBar

        // One glass tool group keeps map actions visible after adding points.
        if (!isInPipMode) {
            GlassOverlaySurface(hazeState = mapHazeState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                    .shadow(4.dp, MaterialTheme.shapes.extraLarge),
                shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(6.dp).animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MapToolButton(Icons.Rounded.Layers, stringResource(R.string.map_type_label)) {
                        showMapTypeDialog = true
                    }
                    AnimatedVisibility(stage == RoutePlanStage.SELECTING && routePoints.isEmpty()) {
                        MapToolButton(Icons.Rounded.Route, stringResource(R.string.route_library)) {
                            showSavedRoutesDialog = true
                        }
                    }
                    AnimatedVisibility(stage == RoutePlanStage.SELECTING || stage == RoutePlanStage.IDLE) {
                        MapToolButton(Icons.Rounded.Bookmarks, stringResource(R.string.collection_list)) {
                            showSavedLocations = true
                        }
                    }
                    if (stage != RoutePlanStage.RUNNING) {
                        HorizontalDivider(Modifier.width(36.dp).align(Alignment.CenterHorizontally),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        MapToolButton(Icons.Rounded.MyLocation, stringResource(R.string.locate_to_current)) {
                            viewModel.fetchCurrentLocation(context) { lat, lng -> mapRef?.animateCamera(lat, lng, 16f) }
                        }
                    }
                }
            }
        }

        // IDLE阶段（全屏选点模式）只显示单一确认选点按钮
        if (!isInPipMode) {
            if (stage == RoutePlanStage.IDLE) {
                Button(
                    onClick = {
                        val tLat = mapRef?.cameraTargetLat
                        val tLng = mapRef?.cameraTargetLng
                        if (tLat != null && tLng != null) {
                            viewModel.confirmMapPoint(tLat, tLng)
                            Toast.makeText(context, coordinateSelectedMessage, Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.confirm_point), fontWeight = FontWeight.Bold)
                }
            } else {
                // 底部操作栏 (路线规划模式)
                BottomActionBar(
                    hazeState = mapHazeState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    stage = stage,
                    routePoints = routePoints,
                    onConfirmPoint = {
                        val tLat = mapRef?.cameraTargetLat
                        val tLng = mapRef?.cameraTargetLng
                        if (tLat != null && tLng != null) {
                            mapRef?.let(::rememberViewport)
                            viewModel.addRoutePoint(tLat, tLng)
                        }
                    },
                    onUndoPoint = { viewModel.undoLastRoutePoint() },
                    onFinishSelecting = { viewModel.finishSelectingPoints() },
                    onRestartSelecting = { viewModel.editRoutePoints() },
                    onSaveRoute = { showSaveRouteDialog = true },
                    onStartPlanning = { showConfigDialog = true },
                    onStopRoute = { viewModel.stopRoutePlanning(); onClose() }
                )
            }
        }
    }

    // 配置弹窗
    if (showConfigDialog) {
        RoutePlanConfigDialog(
            uiState = uiState,
            onDismiss = {
                showConfigDialog = false
            },
            onStartRoute = {
                showConfigDialog = false
                viewModel.startRoutePlanning()
            },
            onSpeedChange = viewModel::setRouteSimMode,
            onCustomSpeedChange = viewModel::setCustomSpeedMs
        )
    }

    if (showMapTypeDialog) {
        MapTypeDialog(
            currentMapType = uiState.mapType,
            onMapTypeSelected = { viewModel.setMapType(it) },
            onDismiss = { showMapTypeDialog = false }
        )
    }

    if (showSaveRouteDialog) {
        var routeName by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showSaveRouteDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("收藏当前路线", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = routeName,
                        onValueChange = { routeName = it },
                        label = { Text(stringResource(R.string.route_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = { showSaveRouteDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            if (routeName.isNotBlank()) {
                                viewModel.saveRoute(routeName, routePoints)
                                Toast.makeText(context, saveSuccessMessage, Toast.LENGTH_SHORT).show()
                                showSaveRouteDialog = false
                            }
                        }) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }

    // 地点收藏列表
    if (showSavedLocations) {
        SavedLocationsDialog(
            savedLocations = uiState.savedLocations,
            onDismiss = { showSavedLocations = false },
            onSelect = { loc ->
                showSavedLocations = false
                mapRef?.animateCamera(loc.lat, loc.lng)
            },
            onDelete = { loc -> viewModel.removeSavedLocation(loc) }
        )
    }

    if (showSavedRoutesDialog) {
        Dialog(onDismissRequest = { showSavedRoutesDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.route_library), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    if (uiState.savedRoutes.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_saved_routes), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(uiState.savedRoutes) { route ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.loadSavedRoute(route)
                                        showSavedRoutesDialog = false
                                    }.padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(route.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                        Text(stringResource(R.string.route_nodes_count, route.points.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                    IconButton(onClick = { viewModel.deleteSavedRoute(route) }) {
                                        Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = { showSavedRoutesDialog = false }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
    }
}

// Route controls use official expressive components and the app's shared color scheme.

@Composable
private fun TopBar(
    stage: RoutePlanStage,
    routePointCount: Int,
    onBack: () -> Unit
) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = if (stage == RoutePlanStage.IDLE) Modifier else Modifier.fillMaxWidth(),
            leadingContent = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                }
            }
        ) {
            if (stage != RoutePlanStage.IDLE) Text(
                when (stage) {
                    RoutePlanStage.SELECTING -> stringResource(R.string.selecting_points_hint, routePointCount)
                    RoutePlanStage.READY -> stringResource(R.string.route_ready_hint, routePointCount)
                    RoutePlanStage.RUNNING -> stringResource(R.string.route_looping)
                    else -> ""
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun BottomActionBar(
    modifier: Modifier,
    hazeState: HazeState,
    stage: RoutePlanStage,
    routePoints: List<RoutePoint>,
    onConfirmPoint: () -> Unit,
    onUndoPoint: () -> Unit,
    onFinishSelecting: () -> Unit,
    onRestartSelecting: () -> Unit,
    onSaveRoute: () -> Unit,
    onStartPlanning: () -> Unit,
    onStopRoute: () -> Unit
) {
    val shape = MaterialTheme.shapes.large
    GlassOverlaySurface(
        hazeState = hazeState,
        modifier = modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .shadow(5.dp, shape)
            // Empty glass areas must not pass rapid taps through to map zoom gestures.
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        shape = shape
    ) {
        AnimatedContent(targetState = stage, label = "routeActions",
            transitionSpec = { (fadeIn() + slideInVertically { it / 5 }) togetherWith fadeOut() }) { currentStage ->
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Route, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp).size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.route_planning), style = MaterialTheme.typography.titleMedium)
                        Text(if (currentStage == RoutePlanStage.RUNNING) stringResource(R.string.route_looping)
                            else stringResource(R.string.selected_points_count, routePoints.size,
                                if (routePoints.size < 2) stringResource(R.string.at_least_two_points) else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (currentStage == RoutePlanStage.SELECTING) IconButton(onClick = onUndoPoint,
                        enabled = routePoints.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo_last_point))
                    }
                    if (currentStage != RoutePlanStage.RUNNING) IconButton(onClick = onSaveRoute,
                        enabled = routePoints.size >= 2) {
                        Icon(Icons.Rounded.BookmarkBorder, stringResource(R.string.save_route))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (currentStage) {
                        RoutePlanStage.IDLE -> Unit
                        RoutePlanStage.SELECTING -> {
                            RoutePrimaryButton(stringResource(R.string.confirm_point), Icons.Rounded.AddLocation,
                                onConfirmPoint, Modifier.weight(1f))
                            FilledTonalButton(onClick = onFinishSelecting, enabled = routePoints.size >= 2,
                                shapes = ButtonDefaults.shapesFor(48.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.finish_selecting))
                            }
                        }
                        RoutePlanStage.READY -> {
                            FilledTonalButton(onClick = onRestartSelecting, shapes = ButtonDefaults.shapesFor(48.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.edit_route_points))
                            }
                            RoutePrimaryButton(stringResource(R.string.start_planning), Icons.Rounded.PlayArrow,
                                onStartPlanning, Modifier.weight(1f))
                        }
                        RoutePlanStage.RUNNING -> RoutePrimaryButton(stringResource(R.string.stop_planning),
                            Icons.Rounded.Stop, onStopRoute, Modifier.weight(1f), destructive = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutePrimaryButton(
    label: String, icon: ImageVector, onClick: () -> Unit,
    modifier: Modifier = Modifier, destructive: Boolean = false
) {
    Button(onClick = onClick, shapes = ButtonDefaults.shapesFor(48.dp),
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun RoutePlanConfigDialog(
    uiState: AppState,
    onDismiss: () -> Unit,
    onStartRoute: () -> Unit,
    onSpeedChange: (SimMode) -> Unit,
    onCustomSpeedChange: (Double) -> Unit = {}
) {
    var customInput by remember { mutableStateOf(uiState.customSpeedMs.toString()) }
    val modes = listOf(SimMode.WALKING, SimMode.RUNNING, SimMode.CYCLING, SimMode.DRIVING, SimMode.CUSTOM)
    val customSpeed = customInput.toDoubleOrNull()
    val validSpeed = customSpeed != null && customSpeed.isFinite() && customSpeed in 0.1..100.0
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Route, null) },
        title = { Text(stringResource(R.string.route_config)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.loop_auto), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(stringResource(R.string.loop_description), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Text(stringResource(R.string.movement_speed), style = MaterialTheme.typography.titleMedium)
                // Connected toggles retain official checked/pressed shape morphing. Two columns also fit larger fonts.
                modes.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        row.forEachIndexed { index, mode ->
                            ToggleButton(checked = uiState.routeSimMode == mode,
                                onCheckedChange = { onSpeedChange(mode) },
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                                    .semantics { role = androidx.compose.ui.semantics.Role.RadioButton },
                                shapes = when {
                                    row.size == 1 -> ToggleButtonDefaults.shapesFor(56.dp)
                                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(when (mode) {
                                        SimMode.WALKING -> R.string.walking
                                        SimMode.RUNNING -> R.string.running
                                        SimMode.CYCLING -> R.string.cycling
                                        SimMode.DRIVING -> R.string.driving
                                        else -> R.string.custom
                                    }))
                                    if (mode != SimMode.CUSTOM) Text(
                                        stringResource(R.string.route_speed_value, mode.speedMs),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(uiState.routeSimMode == SimMode.CUSTOM) {
                    OutlinedTextField(value = customInput, onValueChange = { value ->
                        customInput = value
                        value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..100.0 }?.let(onCustomSpeedChange)
                    }, label = { Text(stringResource(R.string.speed_unit)) },
                        supportingText = { Text(stringResource(R.string.route_speed_range)) },
                        isError = !validSpeed,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = onStartRoute, shapes = ButtonDefaults.shapes(),
                enabled = uiState.routeSimMode != SimMode.CUSTOM || validSpeed) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_simulation))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// Official expressive icon buttons share the glass surface and expose a long-press label.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Start),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        FilledTonalIconButton(onClick = onClick, shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSurface)) {
            Icon(icon, label, Modifier.size(24.dp))
        }
    }
}

// 保存名称对话框（SpoofingScreen复用）

@Composable
fun SaveNameDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
