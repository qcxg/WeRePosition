package com.shiraka.wereposition.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.draw.clipToBounds
import com.shiraka.wereposition.ui.components.horizontalPageTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shiraka.wereposition.R
import com.shiraka.wereposition.data.model.AppState
import com.shiraka.wereposition.viewmodel.MainViewModel

internal fun openSetupSettings(context: Context, action: String) {
    runCatching {
        context.startActivity(Intent(action, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, R.string.setup_open_failed, Toast.LENGTH_LONG).show()
    }
}

internal fun openModuleManager(context: Context) {
    val launch = listOf("org.lsposed.manager", "org.lsposed.lspd").firstNotNullOfOrNull {
        context.packageManager.getLaunchIntentForPackage(it)
    }
    runCatching {
        if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else Toast.makeText(context, R.string.setup_manager_hint, Toast.LENGTH_LONG).show()
    }.onFailure { Toast.makeText(context, R.string.setup_manager_hint, Toast.LENGTH_LONG).show() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(viewModel: MainViewModel, uiState: AppState, onClose: (() -> Unit)? = null, showHeader: Boolean = true) {
    // Completion and consent are separate from language; locale recreation must not skip consent.
    var permissionsPage by rememberSaveable { mutableStateOf(onClose != null) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    BackHandler(if (onClose == null) permissionsPage else !permissionsPage) {
        permissionsPage = !permissionsPage
    }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().then(if (showHeader) Modifier.safeDrawingPadding()
        else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))) {
        if (showHeader) TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(stringResource(if (onClose == null) R.string.setup_welcome else if (permissionsPage) R.string.setup_permissions else R.string.setup_read_information)) },
            navigationIcon = {
                if (permissionsPage || onClose != null) IconButton(onClick = {
                    if (onClose != null && permissionsPage) onClose() else permissionsPage = !permissionsPage
                }) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.back)) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        AnimatedContent(
            targetState = permissionsPage,
            transitionSpec = { horizontalPageTransition(if (onClose == null) targetState else !targetState) },
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            label = "setupStepSlide"
        ) { page ->
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (page) Icons.Rounded.VerifiedUser else Icons.Rounded.MyLocation,
                                null, Modifier.size(36.dp))
                            Text(stringResource(if (page) R.string.setup_permissions else R.string.setup_intro_title),
                                style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(if (page) R.string.setup_permissions_summary else R.string.setup_intro_summary),
                                style = MaterialTheme.typography.bodyMedium)
                            if (onClose == null) Text(if (page) "3 / 3" else "2 / 3",
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (page) {
                        PermissionSetupContent(viewModel, uiState) { permissionRevision++ }
                        if (onClose != null) TextButton(onClick = { permissionsPage = false }) {
                            Text(stringResource(R.string.setup_read_information))
                        }
                    } else {
                        SetupInfoCard(Icons.Rounded.Code, R.string.setup_project_title, R.string.setup_project_body)
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/qcxg/WeRePosition"))) }
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_source)) }
                        SetupInfoCard(Icons.Rounded.Gavel, R.string.setup_boundaries_title, R.string.setup_boundaries_body)
                        SetupInfoCard(Icons.Rounded.Info, R.string.setup_disclaimer_title, R.string.setup_disclaimer_body)
                        SetupInfoCard(Icons.Rounded.PrivacyTip, R.string.setup_data_title, R.string.setup_data_body)
                        if (onClose == null) Surface(onClick = { agreed = !agreed }, shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(agreed, { agreed = it })
                                Text(stringResource(R.string.setup_agree), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (onClose == null) {
                    val locationGranted = remember(permissionRevision) {
                        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    }
                    Button(onClick = {
                        if (page) viewModel.completeOnboarding() else permissionsPage = true
                    }, enabled = if (page) uiState.accessChecked && !uiState.isCheckingAccess &&
                        uiState.hasRootAccess && uiState.isLSPosedActive && locationGranted else agreed,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp)) {
                        Text(stringResource(if (page) R.string.setup_finish else R.string.setup_continue))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupInfoCard(icon: ImageVector, title: Int, body: Int) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionSetupContent(viewModel: MainViewModel, uiState: AppState, onPermissionsChanged: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
        onPermissionsChanged()
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                revision++
                onPermissionsChanged()
                viewModel.refreshAccessStatus()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.refreshAccessStatus() }
    // Re-read after either a permission result or returning from Android settings.
    val granted = remember(revision) {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS).associateWith {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
    val overlay = remember(revision) { Settings.canDrawOverlays(context) }
    PermissionCard(Icons.Rounded.Lock, R.string.setup_root, R.string.setup_root_body,
        uiState.accessChecked && uiState.hasRootAccess, uiState.isCheckingAccess) { viewModel.refreshAccessStatus() }
    PermissionCard(Icons.Rounded.Extension, R.string.setup_module, R.string.setup_module_body,
        uiState.isLSPosedActive, uiState.isCheckingAccess, extra = { openModuleManager(context) }) { viewModel.refreshAccessStatus() }
    PermissionCard(Icons.Rounded.LocationOn, R.string.setup_location, R.string.setup_location_body,
        granted[Manifest.permission.ACCESS_FINE_LOCATION] == true, settings = true) {
        permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    PermissionCard(Icons.Rounded.SportsEsports, R.string.setup_overlay, R.string.setup_overlay_body, overlay) {
        openSetupSettings(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    }
    PermissionCard(Icons.Rounded.Notifications, R.string.setup_notifications, R.string.setup_notifications_body,
        granted[Manifest.permission.POST_NOTIFICATIONS] == true, settings = true) {
        permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    PermissionCard(Icons.Rounded.CellTower, R.string.setup_phone, R.string.setup_phone_body,
        granted[Manifest.permission.READ_PHONE_STATE] == true, settings = true) {
        permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
    }
    PermissionCard(Icons.Rounded.Bluetooth, R.string.setup_bluetooth, R.string.setup_bluetooth_body,
        granted[Manifest.permission.BLUETOOTH_CONNECT] == true, settings = true) {
        permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
    }
    PermissionCard(Icons.Rounded.LocationSearching, R.string.setup_background, R.string.setup_background_body,
        granted[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true) {
        openSetupSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: Int, body: Int, granted: Boolean,
    checking: Boolean = false, settings: Boolean = false, extra: (() -> Unit)? = null, onRequest: () -> Unit) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (granted) Icon(Icons.Rounded.CheckCircle, stringResource(R.string.setup_granted),
                    tint = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!granted || extra != null) FilledTonalButton(onClick = onRequest, enabled = !checking) {
                    Text(stringResource(if (extra != null) R.string.setup_recheck else R.string.setup_grant))
                }
                if (extra != null) OutlinedButton(onClick = extra) { Text(stringResource(R.string.setup_open_manager)) }
                if (settings && !granted) TextButton(onClick = {
                    openSetupSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                }) { Text(stringResource(R.string.settings)) }
            }
        }
    }
}

/** Only called after a completed check. The main UI stays visible during cold-start verification. */
@Composable
fun AccessRecoveryDialog(uiState: AppState, onRetry: () -> Unit, onPermissions: () -> Unit) {
    val context = LocalContext.current
    val rootMissing = !uiState.hasRootAccess
    val moduleMissing = !uiState.isLSPosedActive
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(if (rootMissing) Icons.Rounded.Lock else if (moduleMissing) Icons.Rounded.Extension else Icons.Rounded.LocationOn, null) },
        title = { Text(stringResource(R.string.setup_recovery_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(when {
                    rootMissing -> R.string.setup_root_body
                    moduleMissing -> R.string.setup_module_body
                    else -> R.string.setup_location_body
                }))
                if (moduleMissing) FilledTonalButton(onClick = { openModuleManager(context) }) {
                    Text(stringResource(R.string.setup_open_manager))
                }
                TextButton(onClick = onPermissions) { Text(stringResource(R.string.setup_permissions)) }
            }
        },
        confirmButton = { TextButton(onClick = onRetry) { Text(stringResource(R.string.setup_recheck)) } }
    )
}
