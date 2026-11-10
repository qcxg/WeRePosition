package com.shiraka.wereposition.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.shiraka.wereposition.R
import com.shiraka.wereposition.data.model.AppState
import com.shiraka.wereposition.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAccessScreen(viewModel: MainViewModel, uiState: AppState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    val rfPackages = remember(uiState.hookedApps) { uiState.hookedApps.map { it.packageName }.toSet() }
    // Capture scope when the installed-app list arrives. Keep row positions stable while toggling.
    val orderedApps = remember(uiState.installedApps) {
        uiState.installedApps.sortedByDescending { app ->
            app.packageName in rfPackages || app.packageName in uiState.locationAllowedApps
        }
    }
    val filtered = remember(orderedApps, query, showSystemApps) {
        orderedApps.filter { app ->
            (showSystemApps || !app.isSystem) &&
                (query.isBlank() || app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_access_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "mode") {
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.app_access_location_mode),
                                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = uiState.perAppLocationEnabled,
                                onCheckedChange = viewModel::setPerAppLocationEnabled
                            )
                        }
                        Text(stringResource(R.string.app_access_location_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text(stringResource(R.string.app_access_rf_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "search") {
                Column {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text(stringResource(R.string.app_access_search)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_access_system_apps), modifier = Modifier.weight(1f))
                        Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
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
                ElevatedCard {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (appIcon != null) Image(appIcon, null, Modifier.size(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.appName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val locationTint = when {
                                !uiState.perAppLocationEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                locationSelected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            IconToggleButton(
                                checked = locationSelected,
                                onCheckedChange = { viewModel.setLocationAppAllowed(app.packageName, it) },
                                modifier = Modifier.size(40.dp).background(
                                    if (locationSelected && uiState.perAppLocationEnabled) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    CircleShape
                                )
                            ) {
                                Icon(
                                    if (locationSelected) Icons.Filled.LocationOn else Icons.Outlined.LocationOn,
                                    contentDescription = stringResource(R.string.app_access_location),
                                    tint = locationTint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconToggleButton(
                                checked = rfSelected,
                                onCheckedChange = {
                                    if (!viewModel.setAppRfScope(app.packageName, it) { success ->
                                            if (!success) Toast.makeText(context, R.string.app_access_scope_failed, Toast.LENGTH_SHORT).show()
                                        }) {
                                        Toast.makeText(context, R.string.app_access_scope_failed, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(40.dp).background(
                                    if (rfSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    CircleShape
                                )
                            ) {
                                Icon(
                                    if (rfSelected) Icons.Filled.WifiTethering else Icons.Outlined.WifiTethering,
                                    contentDescription = stringResource(R.string.app_access_rf),
                                    tint = if (rfSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (uiState.appScopeLoading) {
                item { CircularProgressIndicator() }
            } else if (filtered.isEmpty()) {
                item { Text(stringResource(R.string.no_data_collected)) }
            }
        }
    }
}
