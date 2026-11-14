package com.shiraka.wereposition.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.shiraka.wereposition.R
import com.shiraka.wereposition.data.model.AppState
import com.shiraka.wereposition.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppAccessScreen(viewModel: MainViewModel, uiState: AppState, onBack: () -> Unit, showHeader: Boolean = true) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    val rfPackages = remember(uiState.hookedApps) { uiState.hookedApps.map { it.packageName }.toSet() }
    // Capture scope when the list arrives; toggling must not move rows under the user's finger.
    val orderedApps = remember(uiState.installedApps) {
        uiState.installedApps.sortedByDescending {
            it.packageName in rfPackages || it.packageName in uiState.locationAllowedApps
        }
    }
    val filtered = remember(orderedApps, query, showSystemApps) {
        orderedApps.filter {
            (showSystemApps || !it.isSystem) && (query.isBlank() ||
                it.appName.contains(query, true) || it.packageName.contains(query, true))
        }
    }
    val modeColor by animateColorAsState(
        if (uiState.perAppLocationEnabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh, label = "isolationMode"
    )
    val locationLabel = stringResource(R.string.app_access_location)
    val rfLabel = stringResource(R.string.app_access_rf)
    val modeLabel = stringResource(R.string.app_access_location_mode)

    Scaffold(
        contentWindowInsets = if (showHeader) WindowInsets.safeDrawing else WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showHeader) TopAppBar(
                title = { Text(stringResource(R.string.app_access_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "mode") {
                Surface(color = modeColor, shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Rounded.Shield, null, Modifier.size(36.dp))
                            Text(stringResource(R.string.app_access_location_heading),
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Switch(checked = uiState.perAppLocationEnabled,
                                modifier = Modifier.semantics { contentDescription = modeLabel },
                                onCheckedChange = viewModel::setPerAppLocationEnabled,
                                thumbContent = {
                                    Icon(if (uiState.perAppLocationEnabled) Icons.Rounded.Check else Icons.Rounded.Close,
                                        null, Modifier.size(SwitchDefaults.IconSize))
                                })
                        }
                        Text(stringResource(R.string.app_access_location_desc), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item(key = "scope_explanation") {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.WifiTethering, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.app_access_rf_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = query, onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.app_access_search)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.clear_search))
                            }
                        }, shape = MaterialTheme.shapes.extraLarge,
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.app_access_result_count, filtered.size),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilterChip(selected = showSystemApps, onClick = { showSystemApps = !showSystemApps },
                            label = { Text(stringResource(R.string.app_access_system_apps)) },
                            leadingIcon = {
                                Icon(if (showSystemApps) Icons.Rounded.Check else Icons.Rounded.Android,
                                    null, Modifier.size(18.dp))
                            })
                    }
                }
            }
            items(filtered, key = { it.packageName }) { app ->
                val locationSelected = app.packageName in uiState.locationAllowedApps
                val rfSelected = app.packageName in rfPackages
                val appIcon = remember(app.packageName) {
                    runCatching { context.packageManager.getApplicationIcon(app.packageName)
                        .toBitmap(96, 96).asImageBitmap() }.getOrNull()
                }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().animateItem()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (appIcon != null) Image(appIcon, null, Modifier.size(44.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        // Official group supplies press expansion, shape morphing and accessible overflow.
                        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
                            modifier = Modifier.fillMaxWidth()) {
                            toggleableItem(checked = locationSelected, label = locationLabel, weight = 1f,
                                onCheckedChange = { viewModel.setLocationAppAllowed(app.packageName, it) },
                                icon = { Icon(Icons.Rounded.LocationOn, null) })
                            toggleableItem(checked = rfSelected, label = rfLabel, weight = 1f,
                                onCheckedChange = {
                                    if (!viewModel.setAppRfScope(app.packageName, it) { success ->
                                            if (!success) Toast.makeText(context, R.string.app_access_scope_failed, Toast.LENGTH_SHORT).show()
                                        }) Toast.makeText(context, R.string.app_access_scope_failed, Toast.LENGTH_SHORT).show()
                                }, icon = { Icon(Icons.Rounded.WifiTethering, null) })
                        }
                    }
                }
            }
            if (uiState.appScopeLoading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            } else if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.no_data_collected), Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
