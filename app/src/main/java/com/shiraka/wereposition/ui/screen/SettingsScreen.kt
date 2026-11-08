package com.shiraka.wereposition.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiraka.wereposition.R
import com.shiraka.wereposition.data.model.AppState
import com.shiraka.wereposition.utils.ExternalApiTester
import com.shiraka.wereposition.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    uiState: AppState
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
    var expandedSection by remember { mutableStateOf<String?>(null) }
    var googleApiTest by remember { mutableStateOf(ApiTestUiState()) }
    var wigleApiTest by remember { mutableStateOf(ApiTestUiState()) }
    var openCellIdApiTest by remember { mutableStateOf(ApiTestUiState()) }
    var showAppAccessPage by remember { mutableStateOf(false) }
    val apiTestScope = rememberCoroutineScope()
    val savedLanguage = uiState.currentLanguage.ifBlank { viewModel.getSavedLanguage() }
    val selectedLanguageName = LANGUAGES.firstOrNull { it.code == savedLanguage }?.nativeName ?: savedLanguage.ifBlank { "System" }

    fun toggleSection(key: String) {
        expandedSection = if (expandedSection == key) null else key
    }

    if (showAppAccessPage) {
        LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
        AppAccessScreen(viewModel = viewModel, uiState = uiState, onBack = { showAppAccessPage = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ExpandableSettingsRow(
                title = stringResource(R.string.theme_colors),
                summary = if (useDynamicColor) stringResource(R.string.theme_dynamic)
                    else stringResource(R.string.theme_custom),
                expanded = expandedSection == "theme",
                onClick = { toggleSection("theme") }
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

            ExpandableSettingsRow(
                title = stringResource(R.string.select_language),
                summary = selectedLanguageName,
                expanded = expandedSection == "language",
                onClick = { toggleSection("language") }
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

            ExpandableSettingsRow(
                title = stringResource(R.string.recent_locations),
                summary = stringResource(R.string.recent_display_count, uiState.recentLocationDisplayCount),
                expanded = expandedSection == "recent_count",
                onClick = { toggleSection("recent_count") }
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
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { showAppAccessPage = true },
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_access_title)) },
                    supportingContent = { Text(stringResource(R.string.app_access_summary)) },
                    trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                )
            }

            ExpandableSettingsRow(
                title = stringResource(R.string.app_identity),
                summary = context.packageName,
                expanded = expandedSection == "identity",
                onClick = { toggleSection("identity") }
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

            ExpandableSettingsRow(
                title = stringResource(R.string.synthetic_rf_title),
                summary = stringResource(if (uiState.syntheticRfEnabled) R.string.synthetic_rf_on else R.string.synthetic_rf_off),
                expanded = expandedSection == "synthetic_rf",
                onClick = { toggleSection("synthetic_rf") }
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
            ExpandableSettingsRow(
                title = stringResource(R.string.api_config),
                summary = stringResource(R.string.api_config_summary, configuredApiCount, 3),
                expanded = expandedSection == "apis",
                onClick = { toggleSection("apis") }
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

            ExpandableSettingsRow(
                title = stringResource(R.string.runtime_recovery),
                summary = stringResource(R.string.cleanup_runtime_env),
                expanded = expandedSection == "runtime",
                onClick = { toggleSection("runtime") }
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

            Spacer(Modifier.height(120.dp))
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
private fun ExpandableSettingsRow(
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
                headlineContent = { Text(title) },
                supportingContent = { Text(summary) },
                trailingContent = { Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null
                ) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            )
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    content = content
                )
            }
        }
    }
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
