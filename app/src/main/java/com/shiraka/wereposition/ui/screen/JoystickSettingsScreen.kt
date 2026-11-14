package com.shiraka.wereposition.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shiraka.wereposition.R
import com.shiraka.wereposition.data.model.AppState
import com.shiraka.wereposition.data.model.StartSpoofingPhase
import com.shiraka.wereposition.service.SpoofingService
import com.shiraka.wereposition.utils.SettingsManager
import com.shiraka.wereposition.viewmodel.MainViewModel
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun JoystickSettingsScreen(viewModel: MainViewModel, uiState: AppState, onPlanRoute: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { SettingsManager(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var directionMode by remember { mutableStateOf(settings.joystickDirectionMode) }
    var minSpeed by remember { mutableFloatStateOf(settings.joystickMinSpeedMs) }
    var maxSpeed by remember { mutableFloatStateOf(settings.joystickMaxSpeedMs) }
    var overlayVisible by remember { mutableStateOf(SpoofingService.isJoystickVisible) }
    var pendingStart by remember { mutableStateOf(false) }
    var linkedStartTapAt by remember { mutableLongStateOf(0L) }
    var pendingPermissionLinkedStart by remember { mutableStateOf(false) }

    fun showToast(message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun setOverlayVisible(show: Boolean) {
        context.startService(Intent(context, SpoofingService::class.java).apply {
            action = SpoofingService.ACTION_JOYSTICK
            putExtra(SpoofingService.EXTRA_JOYSTICK_VISIBLE, show)
        })
        overlayVisible = show
        if (show && (uiState.mockWifi || uiState.mockCell)) {
            showToast(R.string.joystick_rf_disabled)
        }
    }

    fun beginMode(linkedStart: Boolean = false) {
        if (uiState.isSpoofingActive && SpoofingService.isRunning) {
            setOverlayVisible(true)
        } else if (linkedStart) {
            viewModel.resetStartSpoofingProgress()
            pendingStart = true
            showToast(R.string.joystick_linked_start)
            viewModel.startSpoofing(allowPartial = true)
        } else {
            showToast(R.string.joystick_enable_location_first)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) beginMode(pendingPermissionLinkedStart)
        pendingPermissionLinkedStart = false
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) overlayVisible = SpoofingService.isJoystickVisible
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(uiState.isSpoofingActive, uiState.startSpoofingProgress.phase, pendingStart) {
        if (pendingStart && uiState.isSpoofingActive && SpoofingService.isRunning) {
            setOverlayVisible(true)
            pendingStart = false
        } else if (pendingStart && uiState.startSpoofingProgress.phase == StartSpoofingPhase.ERROR) {
            pendingStart = false
        } else if (!uiState.isSpoofingActive && !pendingStart) {
            overlayVisible = false
        }
    }
    LaunchedEffect(pendingStart) {
        if (pendingStart) {
            delay(25_000L)
            pendingStart = false
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(stringResource(R.string.nav_joystick), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.joystick_overlay_description), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.SportsEsports, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(if (overlayVisible) R.string.joystick_ready
                else R.string.joystick_start_hint), style = MaterialTheme.typography.titleMedium)
        }

        Text(stringResource(R.string.joystick_direction_title), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = directionMode == "NORTH",
                onClick = { directionMode = "NORTH"; settings.joystickDirectionMode = "NORTH" },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text(stringResource(R.string.joystick_direction_north)) }
            SegmentedButton(
                selected = directionMode == "DEVICE",
                onClick = { directionMode = "DEVICE"; settings.joystickDirectionMode = "DEVICE" },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text(stringResource(R.string.joystick_direction_device)) }
        }
        Text(stringResource(if (directionMode == "NORTH") R.string.joystick_north_hint
            else R.string.joystick_device_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text(stringResource(R.string.joystick_speed_bounds), style = MaterialTheme.typography.titleMedium)
        RangeSlider(
            value = minSpeed..maxSpeed,
            onValueChange = { range ->
                minSpeed = range.start.coerceIn(0.5f, 19.5f)
                maxSpeed = range.endInclusive.coerceIn(minSpeed + 0.5f, 20f)
                settings.setJoystickSpeedBounds(minSpeed, maxSpeed)
            },
            valueRange = 0.5f..20f,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(String.format(Locale.US, "%.1f m/s", minSpeed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(Locale.US, "%.1f m/s", maxSpeed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.joystick_speed_bounds_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Button(onClick = {
            if (overlayVisible) {
                pendingStart = false
                setOverlayVisible(false)
            } else {
                val linkedStart = if (uiState.isSpoofingActive && SpoofingService.isRunning) {
                    false
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (linkedStartTapAt == 0L || now - linkedStartTapAt > 5_000L) {
                        linkedStartTapAt = now
                        showToast(R.string.joystick_enable_location_first)
                        return@Button
                    }
                    linkedStartTapAt = 0L
                    true
                }
                if (Settings.canDrawOverlays(context)) beginMode(linkedStart)
                else {
                    pendingPermissionLinkedStart = linkedStart
                    permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")))
                }
            }
        }, enabled = !uiState.isSavingConfig, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.SportsEsports, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (overlayVisible) R.string.joystick_stop_mode else R.string.joystick_start_mode))
        }
        Text(stringResource(R.string.joystick_overlay_permission), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        FilledTonalButton(onClick = onPlanRoute, shapes = ButtonDefaults.shapesFor(56.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Outlined.Route, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.route_planning))
        }
    }
}
