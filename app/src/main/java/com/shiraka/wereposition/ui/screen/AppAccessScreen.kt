package com.shiraka.wereposition.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    val filtered = remember(uiState.installedApps, query, showSystemApps) {
        uiState.installedApps.filter { app ->
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(app.appName, style = MaterialTheme.typography.titleSmall)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.app_access_location), modifier = Modifier.weight(1f))
                            Checkbox(
                                checked = app.packageName in uiState.locationAllowedApps,
                                onCheckedChange = { viewModel.setLocationAppAllowed(app.packageName, it) }
                            )
                            Text(stringResource(R.string.app_access_rf), modifier = Modifier.weight(1f))
                            Checkbox(
                                checked = app.packageName in rfPackages,
                                onCheckedChange = {
                                    if (!viewModel.setAppRfScope(app.packageName, it) { success ->
                                            if (!success) Toast.makeText(context, R.string.app_access_scope_failed, Toast.LENGTH_SHORT).show()
                                        }) {
                                        Toast.makeText(context, R.string.app_access_scope_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Text(stringResource(R.string.no_data_collected)) }
            }
        }
    }
}
