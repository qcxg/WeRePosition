package com.shiraka.wereposition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.shiraka.wereposition.ui.screen.BlockingScreen
import com.shiraka.wereposition.ui.screen.FullScreenMapPage
import com.shiraka.wereposition.ui.screen.InitializingScreen
import com.shiraka.wereposition.ui.screen.JoystickSettingsScreen
import com.shiraka.wereposition.ui.screen.SpoofingScreen
import com.shiraka.wereposition.ui.screen.LanguageSelectionScreen
import com.shiraka.wereposition.ui.components.FloatingGlassNavigationBar
import com.shiraka.wereposition.ui.components.GlassNavigationItem
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.shiraka.wereposition.utils.LocaleUtils
import com.shiraka.wereposition.viewmodel.MainViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModel()

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val isLangSet = prefs.getBoolean("is_language_set", false)
        val savedLang = if (isLangSet) prefs.getString("language", "") ?: "" else ""
        val lang = savedLang.takeIf { it in SUPPORTED_LANGUAGE_TAGS }.orEmpty()
        
        val context = if (lang.isNotEmpty()) {
            LocaleUtils.wrap(newBase, lang)
        } else {
            newBase
        }
        super.attachBaseContext(context)
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 仅在初始启动或语言变更后同步系统 Locale
        // AppCompatDelegate.setApplicationLocales 会自动处理持久化
        val savedLang = viewModel.getSavedLanguage()
        val currentLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        if (savedLang.isNotEmpty() && (currentLocales.isEmpty || currentLocales.toLanguageTags() != savedLang)) {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(savedLang)
            )
        }

        checkAndRequestPermissions()

        // 拦截 Back 键：模拟定位开启时退到后台，由前台服务与通知保持运行
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.uiState.value.isSpoofingActive) {
                    moveTaskToBack(true)
                } else {
                    finish()
                }
            }
        })

        setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                val uiState by viewModel.uiState.collectAsState()
                val isDark = isSystemInDarkTheme()
                val appPrefs = remember { getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
                var themeRevision by remember { mutableIntStateOf(0) }
                DisposableEffect(appPrefs) {
                    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (key == "theme_dynamic_color" || key == "theme_tone") themeRevision++
                    }
                    appPrefs.registerOnSharedPreferenceChangeListener(listener)
                    onDispose { appPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
                }
                val colorScheme = remember(isDark, themeRevision) {
                    val dynamic = appPrefs.getBoolean("theme_dynamic_color", true)
                    val tone = appPrefs.getString("theme_tone", "blue") ?: "blue"
                    if (dynamic) {
                        if (isDark) dynamicDarkColorScheme(this@MainActivity)
                        else dynamicLightColorScheme(this@MainActivity)
                    } else {
                        com.shiraka.wereposition.ui.theme.tonedColorScheme(isDark, tone)
                    }
                }

                // 核心：在 Compose 层级内部通过 CompositionLocalProvider 动态刷新
                // 使用 remember(uiState.currentLanguage) 确保语言切换时重新计算 Context
                val context = LocalContext.current
                val wrappedContext = remember(uiState.currentLanguage) {
                    if (uiState.currentLanguage.isNotEmpty()) {
                        LocaleUtils.wrap(context, uiState.currentLanguage)
                    } else {
                        context
                    }
                }
                val configuration = wrappedContext.resources.configuration

                CompositionLocalProvider(
                    LocalContext provides wrappedContext,
                    LocalConfiguration provides configuration
                ) {
                    MaterialExpressiveTheme(colorScheme = colorScheme) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            MainScreen(viewModel = viewModel, uiState = uiState, isDark = isDark)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRuntimeStatus()
    }

    companion object {
        private val SUPPORTED_LANGUAGE_TAGS = setOf("en", "zh", "zh-TW")
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            requestPermissions(notGranted.toTypedArray(), 100)
        } else {
            checkBackgroundLocation()
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 101)
            }
        }
    }

    @Deprecated("Deprecated in Android framework; kept for permission flow compatibility.")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkBackgroundLocation()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
    }
}

private enum class MainTab(val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    FIXED(R.string.fixed_simulation, Icons.Outlined.LocationOn),
    JOYSTICK(R.string.nav_joystick, Icons.Outlined.SportsEsports),
    DATA(R.string.title_manage_data, Icons.Outlined.Storage),
    SETTINGS(R.string.settings, Icons.Outlined.Settings)
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    uiState: com.shiraka.wereposition.data.model.AppState,
    isDark: Boolean
) {
    var isFullScreenMap by remember { mutableStateOf(false) }
    var isScannerMap by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(MainTab.FIXED) }
    var settingsSubpage by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == MainTab.DATA) viewModel.loadManageData()
    }

    val closeMapAndResetRouteIfNeeded = {
        if (uiState.routePlanStage == com.shiraka.wereposition.data.model.RoutePlanStage.SELECTING ||
            uiState.routePlanStage == com.shiraka.wereposition.data.model.RoutePlanStage.READY) {
            viewModel.cancelRoutePlanning()
        }
        isFullScreenMap = false
    }

    BackHandler(enabled = isFullScreenMap || isScannerMap || selectedTab != MainTab.FIXED) {
        if (isScannerMap) {
            isScannerMap = false
        } else if (isFullScreenMap) {
            closeMapAndResetRouteIfNeeded()
        } else {
            selectedTab = MainTab.FIXED
        }
    }

    when {
        uiState.isInitializing -> InitializingScreen(isDark)
        !uiState.isLanguageSet -> LanguageSelectionScreen(viewModel)
        !uiState.hasRootAccess -> BlockingScreen(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.root_required),
            message = stringResource(R.string.root_message),
            isDark = isDark
        )
        !uiState.isLSPosedActive -> BlockingScreen(
            icon = Icons.Rounded.Extension,
            title = stringResource(R.string.lsposed_not_active),
            message = stringResource(R.string.lsposed_message),
            isDark = isDark
        )
        isFullScreenMap -> FullScreenMapPage(
            viewModel = viewModel, uiState = uiState, isDark = isDark,
            isInPipMode = false, onClose = closeMapAndResetRouteIfNeeded
        )
        isScannerMap -> com.shiraka.wereposition.ui.screen.ScannerMapScreen(
            viewModel = viewModel, uiState = uiState, isDark = isDark,
            onClose = { isScannerMap = false }
        )
        else -> {
            val hazeState = rememberHazeState()
            val navigationItems = MainTab.entries.map { GlassNavigationItem(stringResource(it.label), it.icon) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).hazeSource(hazeState)) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (slideInHorizontally(tween(260)) { it * direction } + fadeIn(tween(180)))
                                .togetherWith(slideOutHorizontally(tween(260)) { -it * direction } + fadeOut(tween(180)))
                        },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                        label = "mainTabSlide"
                    ) { tab ->
                    when (tab) {
                        MainTab.FIXED -> SpoofingScreen(
                            viewModel = viewModel, uiState = uiState, isDark = isDark,
                            onExpandMap = { isFullScreenMap = true }
                        )
                        MainTab.JOYSTICK -> JoystickSettingsScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            onPlanRoute = {
                                viewModel.enterRoutePlanning()
                                isFullScreenMap = true
                            }
                        )
                        MainTab.DATA -> com.shiraka.wereposition.ui.screen.ManageDataScreen(
                            viewModel = viewModel, uiState = uiState, isDark = isDark,
                            onOpenScanner = { isScannerMap = true },
                            onLocate = { lat, lng ->
                                viewModel.confirmMapPoint(lat, lng)
                                selectedTab = MainTab.FIXED
                            }
                        )
                        MainTab.SETTINGS -> com.shiraka.wereposition.ui.screen.SettingsScreen(
                            viewModel = viewModel, uiState = uiState,
                            onSubpageChanged = { settingsSubpage = it }
                        )
                    }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = selectedTab != MainTab.SETTINGS || !settingsSubpage,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) { FloatingGlassNavigationBar(
                    items = navigationItems,
                    selectedIndex = selectedTab.ordinal,
                    onDestinationSelected = { selectedTab = MainTab.entries[it] },
                    hazeState = hazeState
                ) }
            }
        }
    }
}
