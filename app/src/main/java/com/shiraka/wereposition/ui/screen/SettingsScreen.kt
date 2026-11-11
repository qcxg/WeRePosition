package com.shiraka.wereposition.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiraka.wereposition.R
import com.shiraka.wereposition.BuildConfig
import com.shiraka.wereposition.data.model.AppState
import com.shiraka.wereposition.utils.ExternalApiTester
import com.shiraka.wereposition.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    onSubpageChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val themePrefs = remember(context) { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    var useDynamicColor by remember { mutableStateOf(themePrefs.getBoolean("theme_dynamic_color", true)) }
    var selectedTone by remember { mutableStateOf(themePrefs.getString("theme_tone", "blue") ?: "blue") }
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val savedMessage = stringResource(R.string.settings_saved)
    val cleanupDoneMessage = stringResource(R.string.cleanup_runtime_env_done)
    val cleanupFailedMessage = stringResource(R.string.cleanup_runtime_env_failed)
    var localGoogleApiKey by remember(uiState.googleApiKey) { mutableStateOf(uiState.googleApiKey) }
    var localWigleToken by remember(uiState.wigleToken) { mutableStateOf(uiState.wigleToken) }
    var localOpencellidToken by remember(uiState.opencellidToken) { mutableStateOf(uiState.opencellidToken) }
    var syntheticEnabled by remember(uiState.syntheticRfEnabled) { mutableStateOf(uiState.syntheticRfEnabled) }
    var syntheticWifiCount by remember(uiState.syntheticWifiCount) { mutableFloatStateOf(uiState.syntheticWifiCount.toFloat()) }
    var syntheticCellCount by remember(uiState.syntheticCellCount) { mutableFloatStateOf(uiState.syntheticCellCount.toFloat()) }
    var recentDisplayCount by remember(uiState.recentLocationDisplayCount) { mutableFloatStateOf(uiState.recentLocationDisplayCount.toFloat()) }
    val clipboardManager = LocalClipboardManager.current
    var showCleanupDialog by remember { mutableStateOf(false) }
    var isCleaningRuntime by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf("home") }
    var googleApiTest by remember { mutableStateOf(ApiTestUiState()) }
    var wigleApiTest by remember { mutableStateOf(ApiTestUiState()) }
    var openCellIdApiTest by remember { mutableStateOf(ApiTestUiState()) }
    var showAppAccessPage by remember { mutableStateOf(false) }
    val apiTestScope = rememberCoroutineScope()
    var updateChecking by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    val updateAvailableText = stringResource(R.string.settings_update_available)
    val upToDateText = stringResource(R.string.settings_up_to_date)
    val updateFailedText = stringResource(R.string.settings_update_failed)
    val savedLanguage = uiState.currentLanguage.ifBlank { viewModel.getSavedLanguage() }
    val selectedLanguageName = LANGUAGES.firstOrNull { it.code == savedLanguage }?.nativeName ?: savedLanguage.ifBlank { "System" }

    LaunchedEffect(currentPage, showAppAccessPage) {
        onSubpageChanged(currentPage != "home" || showAppAccessPage)
    }

    if (showAppAccessPage) {
        LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
        AppAccessScreen(viewModel = viewModel, uiState = uiState, onBack = { showAppAccessPage = false })
        return
    }

    BackHandler(currentPage != "home") { currentPage = "home" }

    val pageTitle = when (currentPage) {
        "appearance" -> stringResource(R.string.settings_appearance)
        "location" -> stringResource(R.string.settings_location)
        "environment" -> stringResource(R.string.settings_environment)
        "connections" -> stringResource(R.string.specified_connections_title)
        "permissions" -> stringResource(R.string.setup_permissions)
        "about" -> stringResource(R.string.settings_about)
        else -> stringResource(R.string.settings)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text(pageTitle, style = MaterialTheme.typography.headlineMedium) },
            navigationIcon = {
                if (currentPage != "home") IconButton(onClick = { currentPage = "home" }) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        AnimatedContent(
            targetState = currentPage,
            transitionSpec = { com.shiraka.wereposition.ui.components.horizontalPageTransition(targetState != "home") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) { page ->
        if (page == "permissions") {
            SetupScreen(viewModel, uiState, onClose = { currentPage = "home" }, showHeader = false)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (page == "home") {
                    SettingsMenuGroup(title = stringResource(R.string.settings_category_preferences)) {
                        SettingsMenuItem(stringResource(R.string.settings_appearance),
                            "$selectedLanguageName · ${if (useDynamicColor) stringResource(R.string.theme_dynamic) else stringResource(R.string.theme_custom)}") {
                            currentPage = "appearance"
                        }
                    }
                    SettingsMenuGroup(title = stringResource(R.string.settings_category_simulation)) {
                        SettingsMenuItem(stringResource(R.string.settings_location),
                            stringResource(R.string.settings_location_summary)) { currentPage = "location" }
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp),
                            color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsMenuItem(stringResource(R.string.app_access_title),
                            stringResource(R.string.app_access_summary)) { showAppAccessPage = true }
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp),
                            color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsMenuItem(stringResource(R.string.specified_connections_title),
                            stringResource(R.string.specified_connections_menu_summary)) { currentPage = "connections" }
                    }
                    SettingsMenuGroup(title = stringResource(R.string.settings_category_environment_data)) {
                        SettingsMenuItem(stringResource(R.string.settings_environment),
                            stringResource(R.string.api_config)) { currentPage = "environment" }
                    }
                    SettingsMenuGroup(title = stringResource(R.string.settings_category_more)) {
                        SettingsMenuItem(stringResource(R.string.setup_permissions),
                            stringResource(R.string.setup_permissions_summary)) { currentPage = "permissions" }

                        SettingsMenuItem(stringResource(R.string.settings_about),
                            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) {
                            currentPage = "about"
                        }
                    }
                }
                if (page == "appearance") {
                SettingsDetailSection(
                    title = stringResource(R.string.theme_colors),
                    summary = if (useDynamicColor) stringResource(R.string.theme_dynamic)
                        else stringResource(R.string.theme_custom),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.theme_dynamic), modifier = Modifier.weight(1f))
                        Switch(checked = useDynamicColor, onCheckedChange = {
                            useDynamicColor = it
                            themePrefs.edit().putBoolean("theme_dynamic_color", it).apply()
                        })
                    }
                    Text(stringResource(R.string.theme_tone), style = MaterialTheme.typography.labelMedium)
                    listOf("blue", "teal", "violet", "amber", "rose").chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { tone ->
                                FilterChip(
                                    selected = selectedTone == tone,
                                    onClick = {
                                        selectedTone = tone
                                        themePrefs.edit().putString("theme_tone", tone).apply()
                                    },
                                    enabled = !useDynamicColor,
                                    label = { Text(tone.replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                    }
                    val hue = selectedTone.removePrefix("hue_").toFloatOrNull() ?: when (selectedTone) {
                        "teal" -> 175f
                        "violet" -> 270f
                        "amber" -> 38f
                        "rose" -> 338f
                        else -> 215f
                    }
                    Text(stringResource(R.string.theme_hue_value, hue.toInt()), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = hue,
                        onValueChange = {
                            selectedTone = "hue_${it.toInt()}"
                        },
                        onValueChangeFinished = {
                            themePrefs.edit().putString("theme_tone", selectedTone).apply()
                        },
                        valueRange = 0f..360f,
                        enabled = !useDynamicColor
                    )
                }

                SettingsDetailSection(
                    title = stringResource(R.string.select_language),
                    summary = selectedLanguageName,
                ) {
                    LANGUAGES.forEach { lang ->
                        LanguageItem(
                            option = lang,
                            isSelected = viewModel.getSavedLanguage() == lang.code,
                            onClick = {
                                viewModel.selectLanguage(lang.code)
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                    androidx.core.os.LocaleListCompat.forLanguageTags(lang.code)
                                )
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                }
                if (page == "location") {

                SettingsDetailSection(
                    title = stringResource(R.string.recent_locations),
                    summary = stringResource(R.string.recent_display_count, uiState.recentLocationDisplayCount),
                ) {
                    Text(stringResource(R.string.recent_display_count, recentDisplayCount.toInt()))
                    Slider(
                        value = recentDisplayCount,
                        onValueChange = { recentDisplayCount = it },
                        onValueChangeFinished = { viewModel.setRecentLocationDisplayCount(recentDisplayCount.toInt()) },
                        valueRange = 1f..20f,
                        steps = 18
                    )
                }

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.pure_mode_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(if (uiState.isSpoofingActive || uiState.isSavingConfig)
                                    R.string.pure_mode_locked else R.string.pure_mode_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.pureModeEnabled,
                            enabled = !uiState.isSpoofingActive && !uiState.isSavingConfig,
                            onCheckedChange = viewModel::setPureModeEnabled
                        )
                    }
                }

                }
                if (page == "about") {

                SettingsDetailSection(
                    title = stringResource(R.string.app_identity),
                    summary = context.packageName,
                ) {
                    CopyableReadOnlyField(
                        value = context.packageName,
                        label = stringResource(R.string.app_package_name),
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(context.packageName))
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    CopyableReadOnlyField(
                        value = uiState.appSha1,
                        label = stringResource(R.string.app_sha1),
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(uiState.appSha1))
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                }
                if (page == "connections") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(Modifier.padding(18.dp)) {
                        SpecifiedConnectionsSettings(viewModel, uiState)
                    }
                }
                }
                if (page == "environment") {

                SettingsDetailSection(
                    title = stringResource(R.string.synthetic_rf_title),
                    summary = stringResource(if (uiState.syntheticRfEnabled) R.string.synthetic_rf_on else R.string.synthetic_rf_off),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.synthetic_rf_warning),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.synthetic_rf_enable), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = syntheticEnabled, onCheckedChange = { syntheticEnabled = it })
                    }
                    Text(stringResource(R.string.synthetic_wifi_count, syntheticWifiCount.toInt()), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = syntheticWifiCount, onValueChange = { syntheticWifiCount = it }, valueRange = 0f..8f, steps = 7, enabled = syntheticEnabled)
                    Text(stringResource(R.string.synthetic_cell_count, syntheticCellCount.toInt()), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = syntheticCellCount, onValueChange = { syntheticCellCount = it }, valueRange = 0f..3f, steps = 2, enabled = syntheticEnabled)
                    SaveSettingsButton {
                        viewModel.setSyntheticRfConfig(
                            syntheticEnabled, syntheticWifiCount.toInt(),
                            syntheticCellCount.toInt()
                        )
                        Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                    }
                }

                val configuredApiCount = listOf(localGoogleApiKey, localWigleToken, localOpencellidToken).count { it.isNotBlank() }
                SettingsDetailSection(
                    title = stringResource(R.string.api_config),
                    summary = stringResource(R.string.api_config_summary, configuredApiCount, 3),
                ) {
                    ApiCredentialField(
                        value = localGoogleApiKey,
                        onValueChange = {
                            localGoogleApiKey = it
                            googleApiTest = ApiTestUiState()
                        },
                        label = stringResource(R.string.custom_google_key),
                        placeholder = stringResource(R.string.custom_google_key_hint),
                        state = googleApiTest,
                        onTest = {
                            val keyBeingTested = localGoogleApiKey
                            googleApiTest = ApiTestUiState(isTesting = true)
                            apiTestScope.launch {
                                val result = ExternalApiTester.testGooglePlaces(
                                    apiKey = keyBeingTested,
                                    context = context
                                )
                                googleApiTest = ApiTestUiState(result = result)
                            }
                        }
                    )
                    Text(
                        text = stringResource(R.string.maps_sdk_build_key_notice),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    ApiCredentialField(
                        value = localWigleToken,
                        onValueChange = {
                            localWigleToken = it
                            wigleApiTest = ApiTestUiState()
                        },
                        label = stringResource(R.string.custom_wigle_token),
                        placeholder = stringResource(R.string.custom_wigle_token_hint),
                        state = wigleApiTest,
                        onTest = {
                            val tokenBeingTested = localWigleToken
                            wigleApiTest = ApiTestUiState(isTesting = true)
                            apiTestScope.launch {
                                wigleApiTest = ApiTestUiState(
                                    result = ExternalApiTester.testWigle(tokenBeingTested)
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    ApiCredentialField(
                        value = localOpencellidToken,
                        onValueChange = {
                            localOpencellidToken = it
                            openCellIdApiTest = ApiTestUiState()
                        },
                        label = stringResource(R.string.custom_opencellid_token),
                        placeholder = stringResource(R.string.custom_opencellid_token_hint),
                        state = openCellIdApiTest,
                        onTest = {
                            val tokenBeingTested = localOpencellidToken
                            openCellIdApiTest = ApiTestUiState(isTesting = true)
                            apiTestScope.launch {
                                openCellIdApiTest = ApiTestUiState(
                                    result = ExternalApiTester.testOpenCellId(tokenBeingTested)
                                )
                            }
                        }
                    )
                    SaveSettingsButton {
                        viewModel.setGoogleApiKey(localGoogleApiKey)
                        viewModel.setWigleApiToken(localWigleToken)
                        viewModel.setOpencellidApiToken(localOpencellidToken)
                        Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                    }
                }

                }
                if (page == "about") {
                SettingsDetailSection(title = stringResource(R.string.settings_check_update)) {
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = {
                            updateChecking = true
                            updateMessage = ""
                            apiTestScope.launch {
                                val release = withContext(Dispatchers.IO) { runCatching { fetchLatestRelease() }.getOrNull() }
                                updateChecking = false
                                if (release == null) {
                                    updateMessage = updateFailedText
                                    updateUrl = null
                                } else {
                                    updateMessage = if (isVersionNewer(release.first, BuildConfig.VERSION_NAME))
                                        "$updateAvailableText ${release.first}" else upToDateText
                                    updateUrl = if (isVersionNewer(release.first, BuildConfig.VERSION_NAME)) release.second else null
                                }
                            }
                        },
                        enabled = !updateChecking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (updateChecking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.settings_check_update))
                    }
                    if (updateMessage.isNotBlank()) Text(updateMessage,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (updateUrl != null) TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                    }) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_open_release))
                    }
                }

                SettingsDetailSection(
                    title = stringResource(R.string.runtime_recovery),
                    summary = stringResource(R.string.cleanup_runtime_env),
                ) {
                    Text(
                        stringResource(R.string.runtime_recovery_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showCleanupDialog = true },
                        enabled = !isCleaningRuntime,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(
                            if (isCleaningRuntime) stringResource(R.string.cleaning_runtime_env) else stringResource(R.string.cleanup_runtime_env),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
                }

                Spacer(Modifier.height(120.dp))
            }
        }
        }
    }

    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCleaningRuntime) showCleanupDialog = false },
            title = { Text(stringResource(R.string.cleanup_runtime_env_confirm_title)) },
            text = { Text(stringResource(R.string.cleanup_runtime_env_confirm_msg)) },
            confirmButton = {
                TextButton(
                    enabled = !isCleaningRuntime,
                    onClick = {
                        isCleaningRuntime = true
                        viewModel.cleanupRuntimeEnvironment { ok ->
                            isCleaningRuntime = false
                            showCleanupDialog = false
                            Toast.makeText(
                                context,
                                if (ok) cleanupDoneMessage else cleanupFailedMessage,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isCleaningRuntime,
                    onClick = { showCleanupDialog = false }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsMenuGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp))
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) { Column(content = content) }
}

@Composable
private fun SettingsMenuItem(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(summary, maxLines = 2) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    )
}

@Composable
private fun SettingsDetailSection(
    title: String,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!summary.isNullOrBlank()) Text(summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            else Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private fun fetchLatestRelease(): Pair<String, String> {
    val connection = URL("https://api.github.com/repos/qcxg/WeRePosition/releases/latest")
        .openConnection() as HttpURLConnection
    connection.connectTimeout = 6_000
    connection.readTimeout = 6_000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", "WeRePosition-Android")
    return try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("GitHub HTTP ${connection.responseCode}")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        json.getString("tag_name") to json.getString("html_url")
    } finally {
        connection.disconnect()
    }
}

private fun isVersionNewer(remote: String, local: String): Boolean {
    val remoteParts = Regex("\\d+").findAll(remote).take(3).map { it.value.toInt() }.toList()
    val localParts = Regex("\\d+").findAll(local).take(3).map { it.value.toInt() }.toList()
    return (0..2).firstNotNullOfOrNull { index ->
        val comparison = (remoteParts.getOrNull(index) ?: 0).compareTo(localParts.getOrNull(index) ?: 0)
        comparison.takeIf { it != 0 }
    }?.let { it > 0 } ?: false
}

@Composable
private fun CopyableReadOnlyField(
    value: String,
    label: String,
    onCopy: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy))
            }
        },
        colors = settingsTextFieldColors()
    )
}

private data class ApiTestUiState(
    val isTesting: Boolean = false,
    val result: ExternalApiTester.Result? = null
)

@Composable
private fun ApiCredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    state: ApiTestUiState,
    onTest: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = !state.isTesting,
                label = { Text(label, maxLines = 1) },
                placeholder = {
                    Text(
                        placeholder,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = settingsTextFieldColors()
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onTest,
                enabled = !state.isTesting,
                modifier = Modifier.widthIn(min = 68.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.api_test_button))
                }
            }
        }

        val message = when {
            state.isTesting -> stringResource(R.string.api_test_testing)
            state.result != null -> apiTestResultMessage(state.result)
            else -> null
        }
        if (message != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 2.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = when {
                    state.isTesting -> MaterialTheme.colorScheme.primary
                    state.result?.isSuccess == true -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun apiTestResultMessage(result: ExternalApiTester.Result): String {
    return when (result.code) {
        ExternalApiTester.ResultCode.SUCCESS -> stringResource(R.string.api_test_success)
        ExternalApiTester.ResultCode.MISSING_CREDENTIAL -> stringResource(R.string.api_test_missing_credential)
        ExternalApiTester.ResultCode.INVALID_CREDENTIAL -> stringResource(R.string.api_test_invalid_credential)
        ExternalApiTester.ResultCode.INVALID_REQUEST -> stringResource(R.string.api_test_invalid_request)
        ExternalApiTester.ResultCode.ACCESS_RESTRICTED -> stringResource(R.string.api_test_access_restricted)
        ExternalApiTester.ResultCode.SERVICE_NOT_ENABLED -> stringResource(R.string.api_test_service_not_enabled)
        ExternalApiTester.ResultCode.BILLING_REQUIRED -> stringResource(R.string.api_test_billing_required)
        ExternalApiTester.ResultCode.QUOTA_EXCEEDED -> stringResource(R.string.api_test_quota_exceeded)
        ExternalApiTester.ResultCode.NETWORK_UNAVAILABLE -> stringResource(R.string.api_test_network_unavailable)
        ExternalApiTester.ResultCode.TIMEOUT -> stringResource(R.string.api_test_timeout)
        ExternalApiTester.ResultCode.SERVER_UNAVAILABLE -> result.httpStatus?.let {
            stringResource(R.string.api_test_server_unavailable_http, it)
        } ?: stringResource(R.string.api_test_server_unavailable)
        ExternalApiTester.ResultCode.INVALID_RESPONSE -> stringResource(R.string.api_test_invalid_response)
        ExternalApiTester.ResultCode.UNKNOWN_ERROR -> result.httpStatus?.let {
            stringResource(R.string.api_test_unknown_http, it)
        } ?: stringResource(R.string.api_test_unknown)
    }
}

@Composable
private fun SaveSettingsButton(onClick: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.save), modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
)
