package com.shiraka.wereposition.xposed

import android.net.Uri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONObject
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.shiraka.wereposition.utils.EnvironmentCoveragePolicy

class LocationHooker : XposedModule() {
    init {
        XposedHelpers.module = this
    }

    private val nmeaTimers = ConcurrentHashMap<Any, java.util.Timer>()
    private val nmeaListenerProxies = ConcurrentHashMap<Any, Any>()
    private val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()
    private val dynamicallyHookedMethods = ConcurrentHashMap<java.lang.reflect.Executable, Boolean>()
    @Volatile
    private var currentPackageName: String = ""
    private val processHookLock = Any()
    @Volatile
    private var processHooksInstalled = false

    private fun hookDynamicMethodOnce(
        clazz: Class<*>,
        methodName: String,
        parameterMatcher: (java.lang.reflect.Method) -> Boolean,
        callback: XC_MethodHook
    ) {
        val candidates = ArrayList<java.lang.reflect.Method>()
        var cursor: Class<*>? = clazz
        while (cursor != null && cursor != Any::class.java) {
            val current = cursor
            if (current.name == "android.telephony.PhoneStateListener" ||
                current.name == "android.telephony.TelephonyCallback"
            ) break
            val currentMatches = runCatching { current.declaredMethods }
                .getOrDefault(emptyArray())
                .filter { method ->
                    method.name == methodName &&
                        !java.lang.reflect.Modifier.isAbstract(method.modifiers) &&
                        runCatching { parameterMatcher(method) }.getOrDefault(false)
                }
            if (currentMatches.isNotEmpty()) {
                // Virtual dispatch enters the nearest concrete override. Hooking every parent
                // implementation as well only broadens the interception surface and can apply
                // the same rewrite twice when an override calls super.
                candidates += currentMatches
                break
            }
            cursor = current.superclass
        }
        for (method in candidates) {
            if (dynamicallyHookedMethods.putIfAbsent(method, true) == null) {
                try {
                    XposedHelpers.hookMethod(method, callback)
                } catch (error: Throwable) {
                    dynamicallyHookedMethods.remove(method, true)
                    XposedBridge.logOpenCellIdEvery(
                        "dynamic-hook:${method.declaringClass.name}.${method.name}:${error.javaClass.name}",
                        "Dynamic callback hook failed for ${method.declaringClass.name}.${method.name}: ${error.javaClass.simpleName}: ${error.message}",
                        60_000L
                    )
                }
            }
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // Nothing here for now
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        val classLoader = param.classLoader
        handleLoadPackage(pkg, classLoader)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (!claimProcessHookInstallation("android")) return
        XposedBridge.log("[WeRePosition] Hooking system_server")
        installProcessHooks(param.classLoader, "android")
    }
    
    companion object {

        private const val VERBOSE_CELL_BUILD_LOGS = false
        private const val DEFAULT_JITTER_RADIUS_METERS = LocationJitterEngine.DEFAULT_RADIUS_METERS
        private const val MIN_JITTER_RADIUS_METERS = LocationJitterEngine.MIN_RADIUS_METERS
        private const val MAX_JITTER_RADIUS_METERS = LocationJitterEngine.MAX_RADIUS_METERS
    }

    fun handleLoadPackage(pkg: String, classLoader: ClassLoader) {
        // 宿主App自报平安
        if (pkg == "com.shiraka.wereposition") {
            return // 宿主App不需要注入定位Hook
        }
        // LSPosed already limits this callback to the user's selected scope.
        // Never inject into location infrastructure, even if an old scope still
        // contains it; other selected client apps need their RF/GNSS hooks.
        if (isInfrastructurePackage(pkg) && pkg != "android" && pkg != "system") return
        if (!claimProcessHookInstallation(pkg)) return
        XposedBridge.log("[WeRePosition] Hooking package: $pkg")
        XposedBridge.logOpenCellId("handleLoadPackage pkg=$pkg classLoader=$classLoader")
        installProcessHooks(classLoader, pkg)
    }

    private fun claimProcessHookInstallation(pkg: String): Boolean {
        synchronized(processHookLock) {
            if (processHooksInstalled) {
                XposedBridge.logEvery(
                    "processHooksAlreadyInstalled:${android.os.Process.myPid()}",
                    "[WeRePosition] skipping duplicate hook installation for $pkg in pid=${android.os.Process.myPid()} (owner=$currentPackageName)"
                )
                return false
            }
            processHooksInstalled = true
            currentPackageName = pkg
            return true
        }
    }

    private fun installProcessHooks(
        classLoader: ClassLoader,
        pkg: String
    ) {
        // Framework/infrastructure processes must never receive client-side radio or
        // connectivity hooks. Those hooks cannot rewrite Binder results for unscoped apps,
        // but they can corrupt the framework's own Wi-Fi/telephony/network decisions.
        if (pkg == "android" || pkg == "system") {
            hookSystemLocationProviderPipeline(classLoader, pkg)
            SystemGnssStatusHook(
                currentConfig = ::readConfig,
                isSimulationActive = ::shouldBlockRealLocation,
                shouldDeliver = ::shouldDeliverLocation,
                locationAllowed = { config, packageName -> locationAllowedForCaller(config, packageName) },
                currentSatellites = {
                    updateSatelliteCacheIfNeeded()
                    cachedSatellites ?: emptyArray()
                }
            ).install(classLoader)
            SystemGnssRawDataHook(::readConfig, ::shouldBlockRealLocation).install(classLoader)
            return
        }

        // Infrastructure processes are never client-side RF hook targets. Play services and
        // Fused Location stay outside module scope; framework locations reach them normally.
        if (pkg == "com.android.phone" || pkg == "com.android.networkstack") return

        hookLocationAPIs(classLoader, pkg)
        clientWifiHook.install(classLoader)
        clientBluetoothHook.install(classLoader)
        hookCellEnvironment(classLoader)
        hookGnssStatus(classLoader)
        // Magnetic-field sensor rewriting is intentionally not part of the stable pipeline.
        // It can lower compass confidence when the physical sensor calibration disagrees with
        // the calculated geomagnetic model, so keep compass data untouched by default.
    }

    private val rng = Random()
    private val jitterEngine = LocationJitterEngine(
        elapsedRealtimeMs = android.os.SystemClock::elapsedRealtime,
        logProfile = { XposedBridge.log(it) }
    )
    private val systemLocationHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val systemProviderManagers = ConcurrentHashMap<String, Any>()
    private val lastRealSystemProviderReportAtMs = ConcurrentHashMap<String, Long>()
    private val lastSystemProviderElapsedRealtimeNanos = ConcurrentHashMap<String, Long>()
    private val lastSyntheticSystemProviderElapsedRealtimeNanos = ConcurrentHashMap<String, Long>()
    private val systemProviderTimestampLock = Any()
    private val cadenceReportProvider = ThreadLocal<String?>()
    private val systemInjectorLock = Any()
    @Volatile
    private var systemInjectorStarted = false
    @Volatile
    private var systemInjectorTick: Runnable? = null
    @Volatile
    private var systemInjectorStartedAtElapsedMs = 0L
    private var systemInjectedReportCount = 0L
    private val systemInjectorInitialGraceMs = 1_500L
    private val systemProviderQuietWindowMs = 2_500L
    private val syntheticProviderTimestampLagNanos = 750_000_000L
    private val systemProviderFallbackOrder = arrayOf(
        android.location.LocationManager.GPS_PROVIDER,
        android.location.LocationManager.NETWORK_PROVIDER,
        "fused"
    )
    // Active GnssStatus injector timers keyed by callback identity hash
    private val gnssStatusInjectors = ConcurrentHashMap<Int, java.util.Timer>()
    private data class RegisteredGnssStatusCallback(
        val callback: Any,
        val classLoader: ClassLoader
    )
    private val registeredGnssStatusCallbacks =
        ConcurrentHashMap<Int, RegisteredGnssStatusCallback>()
    @Volatile
    private var lastSystemLocationCheckAt = 0L
    @Volatile
    private var lastSystemLocationEnabled = false
    @Volatile
    private var cachedProcessContext: android.content.Context? = null
    private data class SpoofedTimestamps(
        val wallTimeMs: Long,
        val elapsedRealtimeNanos: Long
    )

    /** One complete fix. It is created once at the framework pre-filter boundary. */
    private data class SpoofedLocationSample(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val altitude: Double,
        val timestamps: SpoofedTimestamps,
        val provider: String,
        val hasMotion: Boolean,
        val speed: Float,
        val bearing: Float
    )

    private fun rememberSystemProviderTimestamp(provider: String, timestamps: SpoofedTimestamps) {
        if (timestamps.elapsedRealtimeNanos <= 0L) return
        synchronized(systemProviderTimestampLock) {
            val previous = lastSystemProviderElapsedRealtimeNanos[provider] ?: 0L
            if (timestamps.elapsedRealtimeNanos > previous) {
                lastSystemProviderElapsedRealtimeNanos[provider] = timestamps.elapsedRealtimeNanos
            }
        }
    }

    /**
     * Reserve a provider-local fallback timestamp behind the current clock. Keeping the
     * synthetic sample slightly old leaves room for a freshly recovered GNSS/GMS measurement,
     * whose sensor timestamp normally predates its arrival in system_server.
     */
    private fun nextSyntheticSystemProviderTimestamps(provider: String): SpoofedTimestamps? {
        val nowElapsed = android.os.SystemClock.elapsedRealtimeNanos()
        val nowWall = System.currentTimeMillis()
        synchronized(systemProviderTimestampLock) {
            val previous = lastSystemProviderElapsedRealtimeNanos[provider] ?: 0L
            // A future timestamp can only come from a broken provider. Do not make the
            // framework ordering worse by publishing another fallback ahead of the clock.
            if (previous >= nowElapsed) return null
            val laggedElapsed = (nowElapsed - syntheticProviderTimestampLagNanos).coerceAtLeast(1L)
            val elapsed = maxOf(laggedElapsed, previous + 1L)
            lastSystemProviderElapsedRealtimeNanos[provider] = elapsed
            val ageMs = ((nowElapsed - elapsed) / 1_000_000L).coerceAtLeast(0L)
            return SpoofedTimestamps(
                wallTimeMs = nowWall - ageMs,
                elapsedRealtimeNanos = elapsed
            )
        }
    }

    private fun isInfrastructurePackage(pkg: String = currentPackageName): Boolean {
        return pkg == "android" ||
            pkg == "system" ||
            pkg == "com.android.phone" ||
            pkg == "com.android.location.fused" ||
            pkg == "com.android.networkstack" ||
            pkg == "com.android.ons" ||
            pkg.startsWith("com.google.android.gms")
    }

    private fun shouldActivelyInjectClientGnss(pkg: String = currentPackageName): Boolean {
        return !isInfrastructurePackage(pkg)
    }

    private fun standardLocationCoordinates(config: JSONObject): Pair<Double, Double> {
        return Pair(config.optDouble("lat", 0.0), config.optDouble("lng", 0.0))
    }

    private fun createLocationSample(
        config: JSONObject,
        provider: String,
        timestamps: SpoofedTimestamps
    ): SpoofedLocationSample {
        val enableJitter = config.optBoolean("enable_jitter", true)
        // Google Maps selection, the RF database, and Android Location all use WGS-84.
        // Jitter in that same coordinate system so the delivered fix stays at the
        // selected point instead of acquiring an erroneous GCJ-02 offset.
        val appBase = Pair(
            config.optDouble("lat", 0.0),
            config.optDouble("lng", 0.0)
        )
        val jittered = jitterEngine.sample(
            LocationJitterEngine.Config(
                enabled = enableJitter,
                radiusMeters = config.optDouble("jitter_radius_meters", DEFAULT_JITTER_RADIUS_METERS),
                speed = config.optString("jitter_speed", "MEDIUM"),
                constrainToRfCell = config.optBoolean("mock_wifi", false) ||
                    config.optBoolean("mock_cell", false)
            ),
            appBase.first,
            appBase.second
        )
        val baseAltitude = config.optDouble("altitude", 0.0)
        val altitude = if (enableJitter && baseAltitude > 0.0) {
            baseAltitude + (rng.nextDouble() - 0.5)
        } else {
            baseAltitude
        }
        val hasMotion = hasSpoofedMotion(config)
        return SpoofedLocationSample(
            latitude = jittered.latitude,
            longitude = jittered.longitude,
            accuracy = jittered.accuracy,
            altitude = altitude,
            timestamps = timestamps,
            provider = provider,
            hasMotion = hasMotion,
            speed = if (hasMotion) spoofedSpeed(config) else 0f,
            bearing = if (hasMotion) spoofedBearing(config) else 0f
        )
    }

    private fun hookLocationAPIs(classLoader: ClassLoader, currentPkg: String) {
        try {
            hookSystemLocationProviderPipeline(classLoader, currentPkg)
            // Standard Location values are written once in system_server before framework
            // cache/filter/delivery. Public getters and Parcel are deliberately left untouched.
            // Keep the canonical framework sample free of Android mock-provider flags.
            val antiMockHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (shouldSpoofLocation(config)) {
                        param.result = false
                    }
                }
            }
            // Android 6~11: isFromMockProvider()
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "isFromMockProvider",
                antiMockHook
            )
            // Android 12+: isMock()
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.Location",
                    classLoader,
                    "isMock",
                    antiMockHook
                )
            } catch (e: Throwable) { /* API < 31 没有此方法 */
            }

            // Some clients inspect the legacy secure setting directly. Keep this narrow:
            // unrelated Settings.Secure traffic must never touch the config cache.
            try {
                val mockLocationSettingHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(1) as? String ?: return
                        if (key != "mock_location" && key != "allow_mock_location") return
                        val config = readConfig() ?: return
                        if (!shouldSpoofLocation(config)) return
                        param.result = if (param.method?.name == "getString") "0" else 0
                    }
                }
                val secureClass = XposedHelpers.findClass(
                    "android.provider.Settings\$Secure",
                    classLoader
                )
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        secureClass,
                        "getInt",
                        android.content.ContentResolver::class.java,
                        String::class.java,
                        mockLocationSettingHook
                    )
                }
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        secureClass,
                        "getInt",
                        android.content.ContentResolver::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        mockLocationSettingHook
                    )
                }
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        secureClass,
                        "getString",
                        android.content.ContentResolver::class.java,
                        String::class.java,
                        mockLocationSettingHook
                    )
                }
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // ★ 拦截 getProvider：将 "mock" / "test" 提供者名隐藏，换成 "gps"
            XposedHelpers.findAndHookMethod(
                "android.location.Location", classLoader, "getProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldDeliverLocation(config)) {
                            val provider = param.result as? String ?: return
                            if (provider.contains("mock", ignoreCase = true) ||
                                provider.contains("test", ignoreCase = true) ||
                                provider.contains("fake", ignoreCase = true)
                            ) {
                                param.result = android.location.LocationManager.GPS_PROVIDER
                            }
                        }
                    }
                })

            // ★ 拦截 LocationManager.getProviders() / getAllProviders()：移除 mock/test 提供者
            val providerListHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (shouldSpoofLocation(config)) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? MutableList<String> ?: return
                        val cleaned = list.filterNot {
                            it.contains("mock", ignoreCase = true) ||
                                    it.contains("test", ignoreCase = true) ||
                                    it.contains("fake", ignoreCase = true)
                        }.toMutableList()
                        if (!cleaned.contains(android.location.LocationManager.GPS_PROVIDER))
                            cleaned.add(android.location.LocationManager.GPS_PROVIDER)
                        param.result = cleaned
                    }
                }
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getProviders",
                    Boolean::class.javaPrimitiveType!!, providerListHook
                )
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getAllProviders",
                    providerListHook
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // Never intercept or fulfil LocationManager requests in a client process. The
            // original registration must reach system_server so provider demand, permissions,
            // cancellation and normal Binder delivery all remain authoritative.

            // ★ NMEA-0183 报文劫持
            try {
                val addNmeaListenerHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args
                        for (i in args.indices) {
                            val arg = args[i] ?: continue
                            
                            // Check if it implements OnNmeaMessageListener
                            val isOnNmea = try {
                                val clazz = classLoader.loadClass("android.location.OnNmeaMessageListener")
                                clazz.isInstance(arg)
                            } catch (e: Exception) {
                                false
                            }
                            
                            // Check if it implements GpsStatus.NmeaListener
                            val isGpsNmea = try {
                                val clazz = classLoader.loadClass("android.location.GpsStatus\$NmeaListener")
                                clazz.isInstance(arg)
                            } catch (e: Exception) {
                                false
                            }

                            if (isOnNmea) {
                                XposedBridge.logEvery(
                                    "nmea-listener-proxy:on-message",
                                    "[WeRePosition] active OnNmeaMessageListener proxy installed",
                                    60_000L
                                )
                                args[i] = createOnNmeaMessageListenerProxy(arg, classLoader)
                            } else if (isGpsNmea) {
                                XposedBridge.logEvery(
                                    "nmea-listener-proxy:legacy",
                                    "[WeRePosition] active legacy NMEA listener proxy installed",
                                    60_000L
                                )
                                args[i] = createGpsStatusNmeaListenerProxy(arg, classLoader)
                            }
                        }
                    }
                }
                val locationManagerClazz = XposedHelpers.findClass("android.location.LocationManager", classLoader)
                XposedBridge.hookAllMethods(locationManagerClazz, "addNmeaListener", addNmeaListenerHook)
                
                // Hook removeNmeaListener
                XposedBridge.hookAllMethods(locationManagerClazz, "removeNmeaListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (i in param.args.indices) {
                            val arg = param.args[i]
                            if (arg != null) {
                                val timer = nmeaTimers.remove(arg)
                                timer?.cancel()
                                val proxy = nmeaListenerProxies.remove(arg)
                                if (proxy != null) {
                                    param.args[i] = proxy
                                }
                                if (timer != null || proxy != null) {
                                    XposedBridge.logEvery(
                                        "nmea-listener-proxy:removed",
                                        "[WeRePosition] NMEA listener proxy removed",
                                        60_000L
                                    )
                                }
                            }
                        }
                    }
                })
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // SDK-specific map hooks are intentionally excluded from the stable pipeline.

        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

    }

    // ══════════════════════════════════════════════════════════════════════════
    // Wi-Fi 环境伪造 — 覆盖 WifiInfo / WifiManager / NetworkInfo
    // ══════════════════════════════════════════════════════════════════════════
    private val clientWifiHook = ClientWifiHook(::readConfig, ::shouldMockWifi, { lastConfig }, rng)
    private val clientBluetoothHook = ClientBluetoothHook(::readConfig, ::shouldDeliverLocation)

    // ══════════════════════════════════════════════════════════════════════════
    // 基站/蜂窝网络环境伪造 — 覆盖 TelephonyManager / PhoneStateListener
    // ══════════════════════════════════════════════════════════════════════════
    private fun hookCellEnvironment(classLoader: ClassLoader) {
        XposedBridge.logOpenCellId("Installing cell hooks classLoader=$classLoader")

        // ── 1. 基站信息伪造（CellLocation / AllCellInfo / NeighboringCellInfo）──
        val cellHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val methodName = param.method!!.name
                val config = readConfig() ?: return
                if (!shouldMockCell(config)) return
                val cellCountForLog = config.optJSONArray("cell_json")?.length() ?: 0
                XposedBridge.logOpenCellIdEvery(
                    "$methodName:called:$cellCountForLog",
                    "$methodName called with cell simulation active; cellJsonCount=$cellCountForLog"
                )

                when (methodName) {
                    "getCellLocation" -> {
                        param.result = buildFakeCellLocation(classLoader, config)
                    }

                    "getAllCellInfo" -> {
                        try {
                            val fakeCells = buildFakeCellInfoList(classLoader, config)
                            XposedBridge.logOpenCellIdEvery(
                                "getAllCellInfo:return:${fakeCells.size}",
                                "getAllCellInfo returning fakeCells=${fakeCells.size}"
                            )
                            param.result = fakeCells
                        } catch (e: Throwable) {
                            XposedBridge.logOpenCellId("getAllCellInfo build failed: $e")
                            param.result = java.util.ArrayList<Any>()
                        }
                    }

                    "getNeighboringCellInfo" -> {
                        XposedBridge.logOpenCellId("getNeighboringCellInfo returning empty list")
                        param.result = java.util.ArrayList<Any>()
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getAllCellInfo", cellHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getCellLocation", cellHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getNeighboringCellInfo", cellHook)
            XposedBridge.logOpenCellId("Installed TelephonyManager getAllCellInfo/getCellLocation/getNeighboringCellInfo hooks")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install basic TelephonyManager cell hooks failed: $e")
        }

        // ── 2. TelephonyManager 元数据 Hook ──
        // 防止 MCC/MNC/运营商名称/网络类型泄漏真实地理位置
        // 高德用 getNetworkOperator() 验证基站数据是否与 GPS 位置地理一致
        val telephonyMetaHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val methodName = param.method!!.name
                val config = readConfig() ?: return
                if (!shouldMockCell(config)) return
                val cellArray = config.optJSONArray("cell_json")
                val cell = cellPayload.firstServiceCell(cellArray)
                XposedBridge.logOpenCellIdEvery(
                    "$methodName:called:${cellArray?.length() ?: 0}",
                    "$methodName called with cell simulation active; cellJsonCount=${cellArray?.length() ?: 0}"
                )
                when (methodName) {
                    "getNetworkOperator", "getSimOperator" -> param.result = cellPayload.cellOperatorNumeric(cell) ?: ""
                    "getNetworkOperatorName", "getSimOperatorName" -> param.result = cellPayload.cellOperatorName(cell)
                    "getNetworkCountryIso", "getSimCountryIso" -> param.result = ""
                    "getNetworkType", "getDataNetworkType", "getVoiceNetworkType" ->
                        param.result = cellPayload.cellNetworkType(cell)
                    "getDataState" -> param.result = if (cell != null) 2 else 0
                    "isDataEnabled", "isDataConnectionAllowed" -> param.result = cell != null
                    "getPhoneType" -> param.result = cellPayload.cellPhoneType(cell)
                    "getServiceState", "getServiceStateForSlot" ->
                        param.result = buildFakeServiceState(classLoader, cellArray)
                    "getSignalStrength" -> param.result = buildFakeSignalStrength(classLoader, config)
                }
            }
        }

        val telephonyMetaMethods = listOf(
            "getNetworkOperator", "getNetworkOperatorName", "getNetworkCountryIso",
            "getSimOperator", "getSimOperatorName", "getSimCountryIso",
            "getNetworkType", "getDataNetworkType", "getVoiceNetworkType", "getPhoneType",
            "getServiceState", "getServiceStateForSlot", "getSignalStrength",
            "getDataState", "isDataEnabled", "isDataConnectionAllowed"
        )
        for (method in telephonyMetaMethods) {
            try {
                XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                    method,
                    telephonyMetaHook
                )
                XposedBridge.logOpenCellId("Installed TelephonyManager.$method hook")
            } catch (e: Throwable) {
                XposedBridge.logOpenCellId("Install TelephonyManager.$method hook failed: $e")
            }
        }

        // ── 3. PhoneStateListener 回调拦截 ──
        // 防止应用通过 TelephonyManager.listen() 的 LISTEN_CELL_INFO 回调
        // 绕过 getAllCellInfo() 的 Hook 获取真实基站数据
        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", classLoader, "listen",
                "android.telephony.PhoneStateListener",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args.firstOrNull() ?: return
                        // Keep the original registration intact. The callback method itself
                        // checks the latest config so an existing listener automatically
                        // returns to normal system data as soon as simulation stops.
                        installCellDeliveryHooks(listener, classLoader)
                    }
                }
            )
            XposedBridge.logOpenCellId("Installed TelephonyManager.listen hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.listen hook failed: $e")
        }

// ── 4. TelephonyManager.requestCellInfoUpdate 异步刷新拦截 (Android 10+) ──
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                "requestCellInfoUpdate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callback = param.args.firstOrNull { candidate ->
                            candidate != null && candidate.javaClass.methods.any {
                                it.name == "onCellInfo"
                            }
                        } ?: return
                        // Let the platform perform the request. The callback is rewritten only
                        // at delivery time. For the standard Executor overload, avoid a real
                        // modem refresh while simulation is active and dispatch from memory.
                        installCellDeliveryHooks(callback, classLoader)
                        val config = readConfig() ?: return
                        if (!shouldMockCell(config)) return
                        val executor = param.args.firstOrNull {
                            it is java.util.concurrent.Executor
                        } as? java.util.concurrent.Executor ?: return
                        param.result = null
                        executor.execute {
                            val freshConfig = readConfig()?.takeIf { shouldMockCell(it) }
                            val deliveredCells = if (freshConfig != null) {
                                buildFakeCellInfoList(classLoader, freshConfig)
                            } else {
                                // This request was already intercepted while simulation was
                                // active. Complete its callback contract even if stop/off wins
                                // the race before the Executor runs; never resurrect a modem
                                // refresh merely to service an outstanding simulated request.
                                java.util.ArrayList<Any>()
                            }
                            XposedHelpers.tryCallMethodDeep(callback, "onCellInfo", deliveredCells)
                        }
                    }
                }
            )
            XposedBridge.logOpenCellId("Installed TelephonyManager.requestCellInfoUpdate hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.requestCellInfoUpdate hook failed: $e")
        }

        // ── 5. TelephonyCallback 拦截 (Android 12+ / API 31+) ──
        // registerTelephonyCallback 替代了旧版 listen()，
        // 通过 TelephonyCallback.CellInfoListener 接收基站变化。
        // 需要 hook 注册过程，对每个 callback 实例的 onCellInfoChanged 进行拦截。
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                "registerTelephonyCallback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callbackBase = runCatching {
                            XposedHelpers.findClass("android.telephony.TelephonyCallback", classLoader)
                        }.getOrNull() ?: return
                        val callback = param.args.firstOrNull { candidate ->
                            candidate != null && callbackBase.isInstance(candidate)
                        } ?: return
                        installCellDeliveryHooks(callback, classLoader)
                    }
                }
            )
            XposedBridge.logOpenCellId("Installed TelephonyManager.registerTelephonyCallback hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.registerTelephonyCallback hook failed: $e")
        }

        XposedBridge.logOpenCellId("Cell environment hooks installed")
    }

    /**
     * 通过反射+Parcel机制构造CellInfoLte对象列表
     *
     * CellInfoLte/CellIdentityLte等类的构造器在Android各版本中签名不同,
     * 直接new会因API版本差异崩溃。通过反射调用内部构造器并设置字段值,
     * 兼容Android 7.0~14。
     *
     * 数据策略:
     * - Only consume cell_json populated from local scans or OpenCellID normalization.
     * - Do not generate coordinate-derived tower identities when cell_json is empty.
     * - Return an empty list in fail-closed mode so real base stations remain blocked.
     *
     * @param classLoader 目标App的ClassLoader
     * @return CellInfo objects built from cell_json only.
     */
    private fun buildFakeCellInfoList(
        classLoader: ClassLoader, config: org.json.JSONObject?
    ): java.util.ArrayList<Any> {
        val result = java.util.ArrayList<Any>()
        
        val cellArray = config?.optJSONArray("cell_json")
        XposedBridge.logOpenCellIdEvery(
            "buildFakeCellInfoList:called:${cellArray?.length() ?: 0}",
            "buildFakeCellInfoList called cellJsonCount=${cellArray?.length() ?: 0}"
        )
        if (cellArray != null && cellArray.length() > 0) {
            var gsmCount = 0
            var wcdmaCount = 0
            var lteCount = 0
            var nrCount = 0
            var firstSummary: String? = null
            for (i in 0 until cellArray.length()) {
                try {
                    val obj = cellArray.getJSONObject(i)
                    val type = cellPayload.explicitCellType(obj)
                    if (type == null) {
                        XposedBridge.logOpenCellId("Skipping cell_json[$i] because radio type is missing or unsupported")
                        continue
                    }
                    val isRegistered = obj.optBoolean("isRegistered", false)
                    val tacOrLac = cellPayload.cellAreaCode(obj, 0)
                    val ciOrCid = cellPayload.cellIdentityCode(obj, 0)
                    if (tacOrLac <= 0 || ciOrCid <= 0) {
                        XposedBridge.logOpenCellId("Skipping cell_json[$i] because area or identity is missing: $obj")
                        continue
                    }
                    val mcc = cellPayload.jsonIntValue(obj, "mcc")?.takeIf { it in 100..999 }
                    val mnc = cellPayload.jsonIntValue(obj, "mnc", "net")?.takeIf { it in 0..999 }
                    if (mcc == null || mnc == null) {
                        XposedBridge.logOpenCellId("Skipping cell_json[$i] because MCC/MNC is missing")
                        continue
                    }
                    when (type) {
                        "GSM" -> gsmCount++
                        "WCDMA", "UMTS" -> wcdmaCount++
                        "NR" -> nrCount++
                        else -> lteCount++
                    }
                    val pci = cellPayload.jsonIntValue(obj, "pci", "psc")?.takeIf { it >= 0 } ?: 0
                    val dbm = cellPayload.signalDbm(obj, i)
                    if (dbm == null) {
                        XposedBridge.logOpenCellIdEvery(
                            "cell-build-missing-signal:$i:$type",
                            "Skipping cell_json[$i] because signal strength is missing",
                            60_000L
                        )
                        continue
                    }
                    val rssi = cellPayload.signalRssi(obj, i, type, dbm)
                    val rsrq = cellPayload.resolvedSignalRsrq(obj, i, type, dbm)
                    val sinr = cellPayload.resolvedSignalSinr(obj, i, type, dbm)
                    if (firstSummary == null) {
                        firstSummary = "$type/$mcc-$mnc area=$tacOrLac identity=$ciOrCid dbm=$dbm registered=$isRegistered"
                    }
                    if (VERBOSE_CELL_BUILD_LOGS) {
                        XposedBridge.logOpenCellId(
                            "buildFakeCellInfoList source[$i] radio=${obj.optString("radio", "")} type=$type registered=$isRegistered mcc=$mcc mnc=$mnc area=$tacOrLac identity=$ciOrCid pci=$pci dbm=$dbm"
                        )
                    }
                    
                    // 1. 寻找并构造具体的 CellInfo 派生类
                    val cellInfoClass = when (type) {
                        "GSM" -> XposedHelpers.findClass("android.telephony.CellInfoGsm", classLoader)
                        "WCDMA", "UMTS" -> XposedHelpers.findClass("android.telephony.CellInfoWcdma", classLoader)
                        "NR" -> XposedHelpers.findClass("android.telephony.CellInfoNr", classLoader)
                        else -> XposedHelpers.findClass("android.telephony.CellInfoLte", classLoader)
                    }
                    val cellInfo = XposedHelpers.newInstance(cellInfoClass)
                    
                    // 设置注册标志（Android 9 及以下用 mRegistered；Android 10+ 用 mCellConnectionStatus）
                    // CONNECTION_NONE=0, CONNECTION_PRIMARY_SERVING=1, CONNECTION_SECONDARY_SERVING=2
                    val connectionStatus = if (isRegistered) 1 else 0
                    try { XposedHelpers.setBooleanField(cellInfo, "mRegistered", isRegistered) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(cellInfo, "mCellConnectionStatus", connectionStatus) } catch (_: Throwable) {}
                    try { if (isRegistered) XposedHelpers.callMethod(cellInfo, "setRegistered", true) } catch (_: Throwable) {}

                    try { XposedHelpers.setLongField(cellInfo, "mTimeStamp", android.os.SystemClock.elapsedRealtimeNanos()) } catch (e: Throwable) {}

                    // 2. 构造 CellIdentity（尝试多种有参构造器，避免 final 字段反射问题）
                    val cellIdentityClass = when (type) {
                        "GSM" -> XposedHelpers.findClass("android.telephony.CellIdentityGsm", classLoader)
                        "WCDMA", "UMTS" -> XposedHelpers.findClass("android.telephony.CellIdentityWcdma", classLoader)
                        "NR" -> XposedHelpers.findClass("android.telephony.CellIdentityNr", classLoader)
                        else -> XposedHelpers.findClass("android.telephony.CellIdentityLte", classLoader)
                    }
                    val mccStr = mcc.toString()
                    val mncStr = if (mnc < 10) "0$mnc" else mnc.toString()
                    val cellIdentity = constructCellIdentityByType(
                        type, cellIdentityClass, mcc, mccStr, mnc, mncStr, tacOrLac, ciOrCid, pci
                    )
                    if (VERBOSE_CELL_BUILD_LOGS) {
                        XposedBridge.logOpenCellId("Built $type identity: MCC=$mcc MNC=$mnc TAC/LAC=$tacOrLac CI/CID=$ciOrCid PCI=$pci -> ${cellIdentity.javaClass.simpleName}")
                    }

                    // 验证注入是否成功（如果 getCi()/getLac() 返回 Integer.MAX_VALUE 说明注入失败）
                    try {
                        val verifyMethod = when (type) {
                            "LTE"        -> "getCi"
                            "GSM"        -> "getLac"
                            "WCDMA", "UMTS" -> "getLac"
                            "NR"         -> "getPci"
                            else         -> "getCi"
                        }
                        val readBack = XposedHelpers.callMethod(cellIdentity, verifyMethod) as? Int
                        if (readBack == Int.MAX_VALUE || readBack == -1) {
                            XposedBridge.logOpenCellId("WARNING: $type.$verifyMethod()=$readBack, identity injection may have failed")
                        } else if (VERBOSE_CELL_BUILD_LOGS) {
                            XposedBridge.logOpenCellId("VERIFY OK: $type.$verifyMethod()=$readBack")
                        }
                    } catch (_: Throwable) {}

                    // 将 CellIdentity 存入 CellInfo (兼容新老版本字段名)
                    val identityField = when (type) {
                        "GSM" -> "mCellIdentityGsm"
                        "WCDMA", "UMTS" -> "mCellIdentityWcdma"
                        "NR" -> "mCellIdentityNr"
                        else -> "mCellIdentityLte"
                    }
                    try { XposedHelpers.setObjectField(cellInfo, identityField, cellIdentity) } catch (e: Throwable) {}
                    try { XposedHelpers.setObjectField(cellInfo, "mCellIdentity", cellIdentity) } catch (e: Throwable) {}

                    // 3. 构造并配置对应的 CellSignalStrength
                    val cssClass = when (type) {
                        "GSM" -> XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", classLoader)
                        "WCDMA", "UMTS" -> XposedHelpers.findClass("android.telephony.CellSignalStrengthWcdma", classLoader)
                        "NR" -> XposedHelpers.findClass("android.telephony.CellSignalStrengthNr", classLoader)
                        else -> XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", classLoader)
                    }
                    val css = XposedHelpers.newInstance(cssClass)
                    
                    when (type) {
                        "GSM" -> {
                            val asu = ((dbm + 113) / 2).coerceIn(0, 31)
                            try { XposedHelpers.setIntField(css, "mRssi", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mGsmSignalStrength", asu) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSignalStrength", asu) } catch (e: Throwable) {}
                        }
                        "WCDMA", "UMTS" -> {
                            try { XposedHelpers.setIntField(css, "mRssi", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mRscp", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSignalStrength", dbm) } catch (e: Throwable) {}
                        }
                        "NR" -> {
                            try { XposedHelpers.setIntField(css, "mCsiRsrp", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSsRsrp", dbm) } catch (e: Throwable) {}
                            if (rsrq != null) {
                                try { XposedHelpers.setIntField(css, "mCsiRsrq", rsrq) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(css, "mSsRsrq", rsrq) } catch (e: Throwable) {}
                            }
                            if (sinr != null) {
                                try { XposedHelpers.setIntField(css, "mCsiSinr", sinr.coerceIn(-10, 35)) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(css, "mSsSinr", sinr.coerceIn(-10, 35)) } catch (e: Throwable) {}
                            }
                        }
                        else -> { // LTE
                            if (rssi != null) try { XposedHelpers.setIntField(css, "mRssi", rssi) } catch (_: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mRsrp", dbm) } catch (e: Throwable) {}
                            if (rsrq != null) try { XposedHelpers.setIntField(css, "mRsrq", rsrq) } catch (e: Throwable) {}
                            if (sinr != null) try { XposedHelpers.setIntField(css, "mRssnr", sinr) } catch (e: Throwable) {}
                            if (rssi != null) {
                                val rssiAsu = ((rssi + 113) / 2).coerceIn(0, 31)
                                try { XposedHelpers.setIntField(css, "mSignalStrength", rssiAsu) } catch (_: Throwable) {}
                            }
                        }
                    }

                    // 将 CellSignalStrength 存入 CellInfo (兼容新老版本字段名)
                    val cssField = when (type) {
                        "GSM" -> "mCellSignalStrengthGsm"
                        "WCDMA", "UMTS" -> "mCellSignalStrengthWcdma"
                        "NR" -> "mCellSignalStrengthNr"
                        else -> "mCellSignalStrengthLte"
                    }
                    try { XposedHelpers.setObjectField(cellInfo, cssField, css) } catch (e: Throwable) {}
                    try { XposedHelpers.setObjectField(cellInfo, "mCellSignalStrength", css) } catch (e: Throwable) {}

                    result.add(cellInfo)
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellIdEvery(
                        "cell-build:$i:${e.javaClass.name}",
                        "buildFakeCellInfoList failed at cell_json[$i]: ${e.javaClass.simpleName}: ${e.message}",
                        60_000L
                    )
                }
            }
            XposedBridge.logOpenCellIdEvery(
                "buildFakeCellInfoList:return:$lteCount:$nrCount:$wcdmaCount:$gsmCount:${result.size}",
                "buildFakeCellInfoList returning ${result.size} cells from cell_json types=LTE:$lteCount NR:$nrCount WCDMA:$wcdmaCount GSM:$gsmCount first=$firstSummary"
            )
            return result
        }

        XposedBridge.logOpenCellIdEvery(
            "buildFakeCellInfoList:empty",
            "buildFakeCellInfoList has no cell_json; returning empty list",
            60_000L
        )
        return result
    }

    private val cellPayload = CellPayloadReader { lastConfig }

    private fun buildFakeCellLocation(
        classLoader: ClassLoader,
        config: org.json.JSONObject?
    ): Any? {
        val cell = cellPayload.firstCell(config) ?: return null
        val area = cellPayload.cellAreaCode(cell, 0)
        val identity = cellPayload.cellIdentityCode(cell, 0)
        if (area <= 0 || identity <= 0) return null
        return runCatching {
            val clazz = XposedHelpers.findClass(
                "android.telephony.gsm.GsmCellLocation",
                classLoader
            )
            XposedHelpers.newInstance(clazz).also {
                XposedHelpers.callMethod(it, "setLacAndCid", area, identity)
            }
        }.getOrNull()
    }

    private fun installCellDeliveryHooks(callback: Any, classLoader: ClassLoader) {
        val callbackClass = callback.javaClass
        fun freshConfig(): org.json.JSONObject? = readConfig()?.takeIf { shouldMockCell(it) }
        fun hasParameters(method: java.lang.reflect.Method, vararg typeNames: String): Boolean =
            method.parameterTypes.map { it.name } == typeNames.toList()

        fun installCellInfoMethod(methodName: String) {
            hookDynamicMethodOnce(
                callbackClass,
                methodName,
                { hasParameters(it, "java.util.List") },
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = freshConfig() ?: return
                        if (param.args.isNotEmpty()) {
                            param.args[0] = buildFakeCellInfoList(classLoader, config)
                        } else {
                            param.result = null
                        }
                    }
                }
            )
        }
        installCellInfoMethod("onCellInfoChanged")
        installCellInfoMethod("onCellInfo")

        hookDynamicMethodOnce(
            callbackClass,
            "onCellLocationChanged",
            { hasParameters(it, "android.telephony.CellLocation") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = freshConfig() ?: return
                    val fake = buildFakeCellLocation(classLoader, config)
                    if (fake != null && param.args.isNotEmpty()) param.args[0] = fake else param.result = null
                }
            }
        )
        hookDynamicMethodOnce(
            callbackClass,
            "onServiceStateChanged",
            { hasParameters(it, "android.telephony.ServiceState") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = freshConfig() ?: return
                    val fake = buildFakeServiceState(classLoader, config.optJSONArray("cell_json"))
                    if (fake != null && param.args.isNotEmpty()) param.args[0] = fake else param.result = null
                }
            }
        )
        hookDynamicMethodOnce(
            callbackClass,
            "onSignalStrengthsChanged",
            { hasParameters(it, "android.telephony.SignalStrength") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = freshConfig() ?: return
                    val fake = buildFakeSignalStrength(classLoader, config)
                    if (fake != null && param.args.isNotEmpty()) param.args[0] = fake else param.result = null
                }
            }
        )
        hookDynamicMethodOnce(
            callbackClass,
            "onSignalStrengthChanged",
            { hasParameters(it, "int") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = freshConfig() ?: return
                    val cell = cellPayload.firstSignalCell(config.optJSONArray("cell_json"))
                    val dbm = cell?.let { cellPayload.signalDbm(it, 0) }
                    if (dbm != null && param.args.isNotEmpty()) {
                        param.args[0] = ((dbm + 113) / 2).coerceIn(0, 31)
                    } else {
                        param.result = null
                    }
                }
            }
        )

        fun suppressSensitiveCallback(methodName: String, vararg parameterTypes: String) {
            hookDynamicMethodOnce(
                callbackClass,
                methodName,
                { hasParameters(it, *parameterTypes) },
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (freshConfig() != null) param.result = null
                    }
                }
            )
        }
        suppressSensitiveCallback(
            "onRegistrationFailed",
            "android.telephony.CellIdentity",
            "java.lang.String",
            "int",
            "int",
            "int"
        )
        suppressSensitiveCallback("onBarringInfoChanged", "android.telephony.BarringInfo")
        hookDynamicMethodOnce(
            callbackClass,
            "onPhysicalChannelConfigChanged",
            { hasParameters(it, "java.util.List") },
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (freshConfig() != null && param.args.isNotEmpty()) {
                        param.args[0] = java.util.ArrayList<Any>()
                    }
                }
            }
        )
        XposedBridge.logOpenCellIdEvery(
            "cell-delivery-hooks:${callbackClass.name}",
            "Installed delivery-time cell hooks on ${callbackClass.name}",
            60_000L
        )
    }

    private fun buildFakeServiceState(classLoader: ClassLoader, cellArray: org.json.JSONArray?): Any? {
        val cell = cellPayload.firstServiceCell(cellArray) ?: return null
        val operator = cellPayload.cellOperatorNumeric(cell) ?: return null
        val operatorName = cellPayload.cellOperatorName(cell).ifBlank { operator }
        val networkType = cellPayload.cellNetworkType(cell).takeIf { it != 0 } ?: return null
        XposedBridge.logOpenCellIdEvery(
            "buildFakeServiceState:called:${cellArray?.length() ?: 0}",
            "buildFakeServiceState called cellJsonCount=${cellArray?.length() ?: 0}"
        )
        return try {
            val clazz = XposedHelpers.findClass("android.telephony.ServiceState", classLoader)
            val state = XposedHelpers.newInstance(clazz)

            try { XposedHelpers.callMethod(state, "setState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setVoiceRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setDataRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setOperatorName", operatorName, operatorName, operator) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setRoaming", false) } catch (_: Throwable) {}
            try { XposedHelpers.setBooleanField(state, "mIsManualNetworkSelection", false) } catch (_: Throwable) {}
            try {
                val nriBuilderClass = XposedHelpers.findClass("android.telephony.NetworkRegistrationInfo" + "$" + "Builder", classLoader)
                
                val psBuilder = XposedHelpers.newInstance(nriBuilderClass)
                XposedHelpers.callMethod(psBuilder, "setDomain", 2) // DOMAIN_PS = 2
                XposedHelpers.callMethod(psBuilder, "setTransportType", 1) // TRANSPORT_TYPE_WWAN = 1
                XposedHelpers.callMethod(psBuilder, "setRegistrationState", 1) // REGISTRATION_STATE_HOME = 1
                XposedHelpers.callMethod(psBuilder, "setAccessNetworkTechnology", networkType)
                val psNri = XposedHelpers.callMethod(psBuilder, "build")
                
                val csBuilder = XposedHelpers.newInstance(nriBuilderClass)
                XposedHelpers.callMethod(csBuilder, "setDomain", 1) // DOMAIN_CS = 1
                XposedHelpers.callMethod(csBuilder, "setTransportType", 1) // TRANSPORT_TYPE_WWAN = 1
                XposedHelpers.callMethod(csBuilder, "setRegistrationState", 1) // REGISTRATION_STATE_HOME = 1
                XposedHelpers.callMethod(csBuilder, "setAccessNetworkTechnology", networkType)
                val csNri = XposedHelpers.callMethod(csBuilder, "build")

                XposedHelpers.callMethod(state, "addNetworkRegistrationInfo", psNri)
                XposedHelpers.callMethod(state, "addNetworkRegistrationInfo", csNri)
            } catch (e: Throwable) {
                XposedBridge.logOpenCellIdEvery(
                    "service-state-registration:${e.javaClass.name}",
                    "ServiceState registration build failed: ${e.javaClass.simpleName}: ${e.message}",
                    60_000L
                )
            }
            try { XposedHelpers.setIntField(state, "mVoiceRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(state, "mDataRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaLong", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaShort", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mVoiceOperatorNumeric", operator) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mDataOperatorAlphaLong", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mDataOperatorAlphaShort", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mDataOperatorNumeric", operator) } catch (_: Throwable) {}
            XposedBridge.logOpenCellIdEvery(
                "buildFakeServiceState:success:$operator:$operatorName",
                "buildFakeServiceState success operator=$operator operatorName=$operatorName"
            )
            state
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("buildFakeServiceState failed", e)
            null
        }
    }

    private fun buildFakeSignalStrength(classLoader: ClassLoader, config: org.json.JSONObject?): Any? {
        val cellArray = config?.optJSONArray("cell_json")
        val first = cellPayload.firstSignalCell(cellArray) ?: return null
        val type = cellPayload.explicitCellType(first) ?: return null
        val dbm = cellPayload.signalDbm(first, 0) ?: return null
        val rssi = cellPayload.signalRssi(first, 0, type, dbm)
        val rsrq = cellPayload.resolvedSignalRsrq(first, 0, type, dbm)
        val sinr = cellPayload.resolvedSignalSinr(first, 0, type, dbm)
        val cellCount = cellArray?.length() ?: 0
        XposedBridge.logOpenCellIdEvery(
            "buildFakeSignalStrength:called:$type:$cellCount",
            "buildFakeSignalStrength called type=$type cellJsonCount=$cellCount"
        )
        return try {
            val clazz = XposedHelpers.findClass("android.telephony.SignalStrength", classLoader)
            val signalStrength = XposedHelpers.newInstance(clazz)
            val componentClassName = when (type) {
                "GSM" -> "android.telephony.CellSignalStrengthGsm"
                "WCDMA" -> "android.telephony.CellSignalStrengthWcdma"
                "NR" -> "android.telephony.CellSignalStrengthNr"
                else -> "android.telephony.CellSignalStrengthLte"
            }
            val component = XposedHelpers.newInstance(
                XposedHelpers.findClass(componentClassName, classLoader)
            )
            when (type) {
                "GSM" -> {
                    val gsmDbm = dbm.coerceIn(-113, -51)
                    val asu = ((gsmDbm + 113) / 2).coerceIn(0, 31)
                    try { XposedHelpers.setIntField(component, "mRssi", gsmDbm) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mGsmSignalStrength", asu) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mSignalStrength", asu) } catch (_: Throwable) {}
                }
                "WCDMA" -> {
                    try { XposedHelpers.setIntField(component, "mRssi", dbm) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mRscp", dbm) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mSignalStrength", dbm) } catch (_: Throwable) {}
                }
                "NR" -> {
                    try { XposedHelpers.setIntField(component, "mCsiRsrp", dbm) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mSsRsrp", dbm) } catch (_: Throwable) {}
                    if (rsrq != null) {
                        try { XposedHelpers.setIntField(component, "mCsiRsrq", rsrq) } catch (_: Throwable) {}
                        try { XposedHelpers.setIntField(component, "mSsRsrq", rsrq) } catch (_: Throwable) {}
                    }
                    if (sinr != null) {
                        val nrSinr = sinr.coerceIn(-10, 35)
                        try { XposedHelpers.setIntField(component, "mCsiSinr", nrSinr) } catch (_: Throwable) {}
                        try { XposedHelpers.setIntField(component, "mSsSinr", nrSinr) } catch (_: Throwable) {}
                    }
                }
                else -> {
                    if (rssi != null) try { XposedHelpers.setIntField(component, "mRssi", rssi) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(component, "mRsrp", dbm) } catch (_: Throwable) {}
                    if (rsrq != null) try { XposedHelpers.setIntField(component, "mRsrq", rsrq) } catch (_: Throwable) {}
                    if (sinr != null) try { XposedHelpers.setIntField(component, "mRssnr", sinr) } catch (_: Throwable) {}
                    if (rssi != null) {
                        val rssiAsu = ((rssi + 113) / 2).coerceIn(0, 31)
                        try { XposedHelpers.setIntField(component, "mSignalStrength", rssiAsu) } catch (_: Throwable) {}
                    }
                }
            }
            val signalField = when (type) {
                "GSM" -> "mGsm"
                "WCDMA" -> "mWcdma"
                "NR" -> "mNr"
                else -> "mLte"
            }
            var attached = runCatching {
                XposedHelpers.setObjectField(signalStrength, signalField, component)
                true
            }.getOrDefault(false)
            if (!attached) {
                attached = runCatching {
                    XposedHelpers.setObjectField(signalStrength, "mCellSignalStrengths", listOf(component))
                    true
                }.getOrDefault(false)
            }
            if (!attached) return null
            XposedBridge.logOpenCellIdEvery(
                "buildFakeSignalStrength:success:$type",
                "buildFakeSignalStrength success type=$type rssi=$rssi dbm=$dbm rsrq=$rsrq sinrDb=$sinr"
            )
            signalStrength
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "signal-strength-build:${e.javaClass.name}",
                "buildFakeSignalStrength failed: ${e.javaClass.simpleName}: ${e.message}",
                60_000L
            )
            null
        }
    }

    /**
     * 构造 CellIdentity 派生类，完整兼容 Android 9 ~ Android 14+。
     * 按以下优先级尝试：
     *   1. 各 Android 版本已知的有参构造器（最优，字段由构造器写入）
     *   2. sun.misc.Unsafe.allocateInstance + 反射写字段
     *      （字段初始值为 0 而非 MAX_VALUE，避免 JIT 内联问题）
     *   3. 最小参数构造器 + 默认值填充（最后手段）
     */
    private fun constructCellIdentityByType(
        type: String,
        clazz: Class<*>,
        mcc: Int, mccStr: String,
        mnc: Int, mncStr: String,
        tacOrLac: Int, ciOrCid: Int, pci: Int
    ): Any {
        val ctors = clazz.declaredConstructors.onEach { it.isAccessible = true }

        // 按参数个数匹配构造器，调用失败则返回 null
        fun tryNewInstance(vararg args: Any?): Any? = ctors
            .firstOrNull { it.parameterCount == args.size }
            ?.runCatching { newInstance(*args) }
            ?.getOrNull()

        // ── 阶段一：尝试各版本有参构造器 ──
        val identity: Any? = when (type) {
            "LTE" -> {
                // Android 9 / API 28: (int mcc, int mnc, int ci, int pci, int tac) — 5 参数
                tryNewInstance(mcc, mnc, ciOrCid, pci, tacOrLac)
                // Android 10 / API 29: (int ci, int pci, int tac, int earfcn, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort) — 9 参数
                    ?: tryNewInstance(ciOrCid, pci, tacOrLac, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: (int ci, int pci, int tac, int earfcn, int[] bands, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort, Collection, ClosedSubscriberGroupInfo) — 12 参数
                    ?: tryNewInstance(ciOrCid, pci, tacOrLac, 0, IntArray(0), 0, mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            "GSM" -> {
                // Android 9 / API 28: (int mcc, int mnc, int lac, int cid) — 4 参数
                tryNewInstance(mcc, mnc, tacOrLac, ciOrCid)
                // Android 9 / API 28: (int mcc, int mnc, int lac, int cid, int arfcn, int bsic) — 6 参数
                    ?: tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, 0, 0)
                // Android 10 / API 29: (int lac, int cid, int arfcn, int bsic, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: 10 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, 0, 0, mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            "WCDMA", "UMTS" -> {
                // Android 9: (int mcc, int mnc, int lac, int cid, int psc, int uarfcn) — 6 参数
                tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, pci, 0)
                // Android 10: (int lac, int cid, int psc, int uarfcn, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, pci, 0, mccStr, mncStr, "", "")
                // Android 11+: 10 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, pci, 0, mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            "NR" -> {
                // Android 11+: (int pci, int tac, long nci, int[] bands, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                tryNewInstance(pci, tacOrLac, ciOrCid.toLong(), IntArray(0), mccStr, mncStr, "", "")
                // Android 12+: 10 参数
                    ?: tryNewInstance(pci, tacOrLac, ciOrCid.toLong(), IntArray(0), mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            else -> null
        }

        if (identity != null) return identity

        // ── 阶段二：Unsafe.allocateInstance + 反射写字段 ──
        // 字段初始值为 0（非 MAX_VALUE），避免 JIT 内联问题
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val obj = allocate.invoke(unsafe, clazz) as Any

            // 设置类型标识（CellIdentity.mType）
            val typeInt = when (type) { "GSM" -> 1; "LTE" -> 3; "WCDMA", "UMTS" -> 4; "NR" -> 6; else -> 3 }
            try { XposedHelpers.setIntField(obj, "mType", typeInt) } catch (_: Throwable) {}
            // MCC/MNC（Int 版 Android 9，String 版 Android 10+）
            try { XposedHelpers.setIntField(obj, "mMcc", mcc) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(obj, "mMnc", mnc) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mMccStr", mccStr) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mMncStr", mncStr) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mAlphaLong", "") } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mAlphaShort", "") } catch (_: Throwable) {}

            when (type) {
                "LTE" -> {
                    try { XposedHelpers.setIntField(obj, "mCi", ciOrCid) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mTac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mPci", pci) } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(obj, "mBands", IntArray(0)) } catch (_: Throwable) {}
                }
                "GSM" -> {
                    try { XposedHelpers.setIntField(obj, "mLac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mCid", ciOrCid) } catch (_: Throwable) {}
                }
                "WCDMA", "UMTS" -> {
                    try { XposedHelpers.setIntField(obj, "mLac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mCid", ciOrCid) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mPsc", pci) } catch (_: Throwable) {}
                }
                "NR" -> {
                    try { XposedHelpers.setIntField(obj, "mTac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setLongField(obj, "mNci", ciOrCid.toLong()) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mPci", pci) } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(obj, "mBands", IntArray(0)) } catch (_: Throwable) {}
                }
            }
            XposedBridge.logEvery(
                "cellMockUnsafe:$type",
                "[WeRePosition][CellMock] Unsafe.allocateInstance active for $type"
            )
            return obj
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "cell-identity-unsafe:$type:${e.javaClass.name}",
                "CellIdentity Unsafe fallback failed type=$type: ${e.javaClass.simpleName}: ${e.message}",
                60_000L
            )
        }

        // ── 阶段三：最小参数构造器 + 安全默认值填充（绝对保底）──
        val minCtor = ctors.minByOrNull { it.parameterCount }
            ?: throw IllegalStateException("No constructors for ${clazz.name}")
        val safeArgs = minCtor.parameterTypes.map { t ->
            when {
                t == Int::class.javaPrimitiveType    -> 0
                t == Long::class.javaPrimitiveType   -> 0L
                t == Boolean::class.javaPrimitiveType -> false
                t == Float::class.javaPrimitiveType  -> 0f
                t == Double::class.javaPrimitiveType -> 0.0
                t == IntArray::class.java            -> IntArray(0)
                t == java.util.Collection::class.java || t.isAssignableFrom(java.util.ArrayList::class.java) -> emptyList<Any>()
                else -> null
            }
        }.toTypedArray()
        val fallbackObj = try {
            minCtor.newInstance(*safeArgs)
        } catch (e: Throwable) {
            throw IllegalStateException("Cannot construct ${clazz.name}: $e")
        }
        // 写字段
        try { XposedHelpers.setIntField(fallbackObj, "mMcc", mcc) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(fallbackObj, "mMnc", mnc) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(fallbackObj, "mMccStr", mccStr) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(fallbackObj, "mMncStr", mncStr) } catch (_: Throwable) {}
        when (type) {
            "LTE" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mCi", ciOrCid) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mPci", pci) } catch (_: Throwable) {}
            }
            "GSM" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid) } catch (_: Throwable) {}
            }
            "WCDMA", "UMTS" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mPsc", pci) } catch (_: Throwable) {}
            }
            "NR" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setLongField(fallbackObj, "mNci", ciOrCid.toLong()) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mPci", pci) } catch (_: Throwable) {}
            }
        }
        XposedBridge.logEvery(
            "cellMockMinCtor:$type",
            "[WeRePosition][CellMock] MinCtor fallback used for $type identity"
        )
        return fallbackObj
    }

    private var cachedSatellites: Array<SatelliteData>? = null
    private var lastSatelliteUpdate: Long = 0L
    private var isSpoofingActiveCache: Boolean = false
    private var spoofingCountCache: Int = 0

    private fun updateSatelliteCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (cachedSatellites == null || now - lastSatelliteUpdate > 1000) {
            val config = readConfig()
            isSpoofingActiveCache = shouldDeliverLocation(config) &&
                locationAllowedForCaller(config, currentPackageName)
            if (isSpoofingActiveCache) {
                val count = (config?.optInt("satellite_count", 20) ?: 20).coerceIn(0, 64)
                spoofingCountCache = count
                val timeSec = now / 1000.0
                val enableJitter = config?.optBoolean("enable_jitter", true) ?: true

                val visible = ArrayList<SatelliteData>(count)
                var candidate = 0
                val maxCandidates = maxOf(240, count * 24)
                while (visible.size < count && candidate < maxCandidates) {
                    val sat = SatelliteSkyGenerator.generateSatelliteData(candidate, config ?: JSONObject(), enableJitter, timeSec)
                    if (sat.elevation >= 3f) {
                        visible.add(sat)
                    }
                    candidate++
                }
                while (visible.size < count) {
                    visible.add(SatelliteSkyGenerator.generateFallbackSkySatellite(visible.size, config ?: JSONObject(), enableJitter, timeSec))
                }

                val usedCount = when {
                    count <= 0 -> 0
                    count <= 4 -> count
                    else -> maxOf(4, (count * 0.65f).toInt()).coerceAtMost(count)
                }
                val sorted = visible.sortedWith(compareByDescending<SatelliteData> { it.cn0 }.thenByDescending { it.elevation })
                val usedIndices = chooseUsedSatelliteIndices(sorted, usedCount, now)
                val newCache = sorted
                    .mapIndexed { index, sat -> sat.copy(usedInFix = index in usedIndices) }
                    .toTypedArray()
                cachedSatellites = newCache
            } else {
                spoofingCountCache = 0
                cachedSatellites = emptyArray()
            }
            lastSatelliteUpdate = now
        }
    }

    private fun chooseUsedSatelliteIndices(sats: List<SatelliteData>, usedCount: Int, now: Long): Set<Int> {
        if (usedCount <= 0 || sats.isEmpty()) return emptySet()
        if (usedCount >= sats.size) return sats.indices.toSet()
        val selected = linkedSetOf<Int>()
        val rng = java.util.Random((now / 20_000L) + (lastConfig?.optLong("start_timestamp", 0L) ?: 0L))
        selected.add(0)
        if (sats.size > 1) selected.add(1)
        var guard = 0
        while (selected.size < usedCount && guard < sats.size * 12) {
            guard++
            val candidate = rng.nextInt(sats.size)
            val sat = sats[candidate]
            val quality = ((sat.cn0 - 18f) / 28f).coerceIn(0.15f, 1f)
            val lateBoost = if (candidate > usedCount) 0.18 else 0.0
            if (rng.nextDouble() < (quality * 0.72 + lateBoost).coerceIn(0.05, 0.95)) {
                selected.add(candidate)
            }
        }
        var fallback = 0
        while (selected.size < usedCount && fallback < sats.size) {
            selected.add(fallback++)
        }
        return selected
    }

    private fun getCachedSatellite(satIndex: Int): SatelliteData? {
        if (!isSpoofingActiveCache || cachedSatellites == null || cachedSatellites!!.isEmpty()) return null
        val safeIndex = satIndex % cachedSatellites!!.size
        return cachedSatellites!![safeIndex]
    }

    private fun getApiSatellite(satIndex: Int): SatelliteData? {
        updateSatelliteCacheIfNeeded()
        val sats = cachedSatellites ?: return null
        if (!isSpoofingActiveCache || satIndex !in sats.indices) return null
        return sats[satIndex]
    }

    private fun normalizeDegrees(value: Double): Double = SatelliteSkyGenerator.normalizeDegrees(value)

    private fun spoofedBearing(config: JSONObject): Float {
        val base = config.optDouble("sim_bearing", 0.0)
        val enableJitter = config.optBoolean("enable_jitter", true)
        val noise = if (enableJitter) {
            val tick = System.currentTimeMillis() / 1500L
            val rng = java.util.Random(tick + 0xBEEFL)
            (rng.nextDouble() - 0.5) * 2.0
        } else {
            0.0
        }
        return normalizeDegrees(base + noise).toFloat()
    }

    private fun spoofedSpeed(config: JSONObject): Float {
        val base = config.optDouble("sim_speed", 0.0).coerceAtLeast(0.0)
        if (base <= 0.05) return 0f
        val enableJitter = config.optBoolean("enable_jitter", true)
        val noise = if (enableJitter) {
            val tick = System.currentTimeMillis() / 1200L
            val rng = java.util.Random(tick + 0x5EEDL)
            (rng.nextDouble() - 0.5) * minOf(0.4, base * 0.08)
        } else {
            0.0
        }
        return (base + noise).coerceAtLeast(0.05).toFloat()
    }

    private fun hasSpoofedMotion(config: JSONObject): Boolean {
        return spoofedSpeed(config) > 0.1f
    }

    /**
     * 拦截GnssStatus回调,注入伪造的卫星星座数据
     *
     * 反作弊SDK通过registerGnssStatusCallback获取卫星可见数和信噪比(C/N0),
     * 若Location坐标正常但卫星数为0或信噪比全为0,则判定为模拟位置。
     *
     * 伪造策略:
     * - 可见卫星数: 12-18颗(真实室外环境的典型值)
     * - 信噪比(C/N0): 15-40 dB-Hz(真实GPS信号的典型范围)
     * - 卫星类型: GPS(1) + GLONASS(3) + BDS(5)混合星座
     */
    private fun hookGnssStatus(classLoader: ClassLoader) {
        try {
            val locationManagerClazz = XposedHelpers.findClass("android.location.LocationManager", classLoader)
            installGnssStatusGetterHooks(classLoader)
            
            // Hook addGpsStatusListener
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "addGpsStatusListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0]
                        if (listener != null) {
                            val clazz = listener.javaClass
                            if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
                                try {
                                    XposedBridge.hookAllMethods(clazz, "onGpsStatusChanged", object : XC_MethodHook() {
                                        private var lastCallTime = 0L
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            val config = readConfig()
                                            if (!shouldDeliverLocation(config)) return
                                            val event = param.args[0] as? Int
                                            if (event == 4) { // GPS_EVENT_SATELLITE_STATUS
                                                val now = android.os.SystemClock.elapsedRealtime()
                                                if (now - lastCallTime < 1000) {
                                                    param.result = null // Throttle
                                                } else {
                                                    lastCallTime = now
                                                }
                                            }
                                        }
                                    })
                                } catch (e: Throwable) {}
                            }
                        }
                    }
                })
            } catch (e: Throwable) { XposedBridge.log(e) }

            // Hook removeGpsStatusListener
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "removeGpsStatusListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // No longer needed
                    }
                })
            } catch (e: Throwable) {}

            // Hook registerGnssStatusCallback
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "registerGnssStatusCallback", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        var callbackObj: Any? = null
                        val cbClass = try {
                            classLoader.loadClass("android.location.GnssStatus\$Callback")
                        } catch (e: Exception) { null }

                        for (arg in param.args) {
                            if (arg != null && cbClass != null && cbClass.isInstance(arg)) {
                                callbackObj = arg
                                break
                            }
                        }
                        if (callbackObj != null) {
                            if (param.throwable != null || param.result == false) return
                            val clazz = callbackObj.javaClass
                            if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
                                try {
                                    XposedBridge.hookAllMethods(clazz, "onSatelliteStatusChanged", object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            val config = readConfig()
                                            if (!shouldBlockRealLocation(config)) return
                                            updateSatelliteCacheIfNeeded()
                                            val satellites = if (shouldDeliverLocation(config) &&
                                                locationAllowedForCaller(config, currentPackageName)) {
                                                cachedSatellites ?: emptyArray()
                                            } else emptyArray()
                                            val replacement = GnssStatusFactory.build(
                                                classLoader, satellites, satellites.size, currentPackageName
                                            )
                                            if (replacement == null || param.args.isEmpty()) {
                                                param.result = null
                                            } else {
                                                param.args[0] = replacement
                                            }
                                        }
                                    })
                                } catch (e: Throwable) {}
                            }
                            if (shouldActivelyInjectClientGnss()) {
                                // Keep the registration while idle. A later config activation must
                                // start delivery without requiring the target app to register again.
                                registeredGnssStatusCallbacks[System.identityHashCode(callbackObj)] =
                                    RegisteredGnssStatusCallback(callbackObj, classLoader)
                                // Some client processes cannot register either lifecycle
                                // observer. Keep their idle poll responsive while a satellite
                                // callback is listening, then return to the long idle interval.
                                requestConfigRefresh()
                                val config = readConfig()
                                if (shouldDeliverLocation(config) &&
                                    locationAllowedForCaller(config, currentPackageName)) {
                                    startGnssStatusInjector(callbackObj, classLoader)
                                }
                            }
                        }
                    }
                })
            } catch (e: Throwable) { XposedBridge.log(e) }

            // Hook unregisterGnssStatusCallback — cancel any active injector
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "unregisterGnssStatusCallback", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (arg in param.args) {
                            if (arg != null) {
                                val key = System.identityHashCode(arg)
                                registeredGnssStatusCallbacks.remove(key)
                                gnssStatusInjectors.remove(key)?.cancel()
                            }
                        }
                        if (registeredGnssStatusCallbacks.isEmpty()) requestConfigRefresh()
                    }
                })
            } catch (e: Throwable) {}

            // Hook GpsStatus.getSatellites() for legacy Apps like DevCheck
            try {
                XposedHelpers.findAndHookMethod("android.location.GpsStatus", classLoader, "getSatellites", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldBlockRealLocation(config)) {
                            param.result = createSpoofedGpsSatellites(classLoader)
                        }
                    }
                })
            } catch (e: Throwable) { XposedBridge.log(e) }


            XposedBridge.log("[WeRePosition] GnssStatus hooks installed")
        } catch (e: Throwable) {
            XposedBridge.log("[WeRePosition] GnssStatus hook failed: $e")
        }
    }

    /**
     * Actively drives GnssStatus callbacks so satellite view apps see a live spoofed sky
     * even when the real GPS hardware has no signal (indoor / airplane mode).
     *
     * Strategy:
     * - Every 1 second, build a minimal GnssStatus via Builder (Android 7+) or reflection constructor.
     * - Call onSatelliteStatusChanged(gnssStatus) on the registered callback object directly.
     * - The getter hooks installed by installGnssStatusGetterHooks() rewrite the returned values
     *   (getSatelliteCount, getCn0DbHz, etc.) so the app sees spoofed satellite data.
     * - Do NOT mutate GnssStatus parallel arrays; only construct a trivially valid shell.
     */
    private fun startGnssStatusInjector(callbackObj: Any, classLoader: ClassLoader) {
        val key = System.identityHashCode(callbackObj)
        val timer = java.util.Timer("WeRePosition_GnssSat_${key}", true)
        if (gnssStatusInjectors.putIfAbsent(key, timer) != null) {
            timer.cancel()
            return
        }
        XposedBridge.logEvery(
            "gnssInjectorStart:$currentPackageName:${callbackObj.javaClass.simpleName}",
            "[WeRePosition] GnssStatus active injector started pkg=$currentPackageName cb=${callbackObj.javaClass.simpleName}"
        )
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                try {
                    val config = readConfig()
                    if (!shouldSpoofLocation(config)) {
                        gnssStatusInjectors.remove(key)?.cancel()
                        return
                    }
                    if (!isSystemLocationSwitchEnabled() ||
                        !locationAllowedForCaller(config, currentPackageName)) return
                    updateSatelliteCacheIfNeeded()
                    if (!isSpoofingActiveCache) return
                    val satCount = cachedSatellites?.size ?: 0
                    if (satCount == 0) return

                    // Build the smallest valid GnssStatus that the getter hooks can wrap.
                    // We need to call onSatelliteStatusChanged; the argument just needs to
                    // be a GnssStatus instance — actual field values are overridden by hooks.
                    val gnssStatus = GnssStatusFactory.build(classLoader, cachedSatellites ?: return, satCount, currentPackageName) ?: return

                    // Directly invoke the callback — this is how the satellite view refreshes.
                    XposedHelpers.tryCallMethodDeep(callbackObj, "onSatelliteStatusChanged", gnssStatus)
                } catch (_: Throwable) {}
            }
        }, 200L, 1000L)
    }

    private fun startRegisteredGnssStatusInjectors() {
        if (!shouldActivelyInjectClientGnss()) return
        registeredGnssStatusCallbacks.values.forEach { registration ->
            startGnssStatusInjector(registration.callback, registration.classLoader)
        }
    }

    private fun installGnssStatusGetterHooks(classLoader: ClassLoader) {
        val gnssStatusClazz = XposedHelpers.findClassIfExists("android.location.GnssStatus", classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(
                gnssStatusClazz,
                "getSatelliteCount",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        updateSatelliteCacheIfNeeded()
                        if (shouldBlockRealLocation(readConfig())) {
                            val count = cachedSatellites?.size ?: 0
                            param.result = count
                            XposedBridge.logEvery(
                                "gnssStatusCount:$currentPackageName:$count",
                                "[WeRePosition] GnssStatus.getSatelliteCount spoofed count=$count package=$currentPackageName"
                            )
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        fun hookIndexedInt(methodName: String, value: (SatelliteData) -> Int) {
            try {
                XposedHelpers.findAndHookMethod(
                    gnssStatusClazz,
                    methodName,
                    Int::class.javaPrimitiveType!!,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldBlockRealLocation(readConfig())) return
                            val sat = getApiSatellite(param.args.getOrNull(0) as? Int ?: return)
                            param.result = sat?.let(value) ?: 0
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        fun hookIndexedFloat(methodName: String, value: (SatelliteData) -> Float) {
            try {
                XposedHelpers.findAndHookMethod(
                    gnssStatusClazz,
                    methodName,
                    Int::class.javaPrimitiveType!!,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldBlockRealLocation(readConfig())) return
                            val sat = getApiSatellite(param.args.getOrNull(0) as? Int ?: return)
                            param.result = sat?.let(value) ?: 0f
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        fun hookIndexedBoolean(methodName: String, value: (SatelliteData) -> Boolean) {
            try {
                XposedHelpers.findAndHookMethod(
                    gnssStatusClazz,
                    methodName,
                    Int::class.javaPrimitiveType!!,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!shouldBlockRealLocation(readConfig())) return
                            val sat = getApiSatellite(param.args.getOrNull(0) as? Int ?: return)
                            param.result = sat?.let(value) ?: false
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        hookIndexedInt("getSvid") { it.svid }
        hookIndexedInt("getConstellationType") { it.type }
        hookIndexedFloat("getCn0DbHz") { it.cn0 }
        hookIndexedFloat("getElevationDegrees") { it.elevation }
        hookIndexedFloat("getAzimuthDegrees") { it.azimuth }
        hookIndexedBoolean("hasEphemerisData") { true }
        hookIndexedBoolean("hasAlmanacData") { true }
        hookIndexedBoolean("usedInFix") { it.usedInFix }
        hookIndexedBoolean("hasCarrierFrequencyHz") { true }
        hookIndexedFloat("getCarrierFrequencyHz") { sat ->
            when (sat.type) {
                3 -> 1_602_000_000f
                5 -> 1_561_098_000f
                else -> 1_575_420_000f
            }
        }
        hookIndexedBoolean("hasBasebandCn0DbHz") { true }
        hookIndexedFloat("getBasebandCn0DbHz") { (it.cn0 - 1.5f).coerceAtLeast(0f) }

        try {
            XposedHelpers.findAndHookMethod(
                gnssStatusClazz,
                "getCodeType",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldBlockRealLocation(readConfig())) return
                        val sat = getApiSatellite(param.args.getOrNull(0) as? Int ?: return)
                        param.result = if (sat == null) "" else if (sat.type == 5) "I" else "C"
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    @Volatile
    private var lastConfig: JSONObject? = null
    @Volatile
    private var lastOpenCellConfigLogKey: String? = null
    @Volatile
    private var lastOpenCellConfigReadFailureLogTime = 0L
    private val providerConfigDebugLastTimes = ConcurrentHashMap<String, Long>()
    private val configPollingClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var configPollIntervalMs = 1_000L
    private val configPollWake = java.util.concurrent.Semaphore(0)
    @Volatile
    private var configChangeCallbackInstalled = false
    private var configChangeCallback: Runnable? = null
    @Volatile
    private var configContentObserverInstalled = false
    private val configContentObserverLock = Any()
    private var configContentObserver: android.database.ContentObserver? = null
    private val lastConfigContentObserverAttemptAt = AtomicLong(0L)
    @Volatile
    private var lastObservedRuntimeFrame: String? = null
    @Volatile
    private var lastObservedStopGeneration: String? = null
    private val localConfigPath = "/data/local/tmp/wereposition_config.json"
    private val systemConfigPath = "/data/system/wereposition_config.json"
    private val activeConfigTtlMs = 30_000L
    private val inactiveConfigPollIntervalMs = 60_000L
    private val locationDisabledPollIntervalMs = 10_000L
    @Volatile
    private var cachedBootId: String? = null
    @Volatile
    private var cachedBootIdInitialized = false
    private val bootIdLock = Any()
    @Volatile
    private var configDiskReadDisabled = false
    @Volatile
    private var cachedSystemPropertyGetMethod: java.lang.reflect.Method? = null
    private val systemPropertyMethodLock = Any()
    private val highestObservedStopGeneration = AtomicLong(0L)
    private val lastHotStopGenerationReadAt = AtomicLong(0L)
    private val configContentUri = Uri.parse("content://com.shiraka.wereposition.provider")
    private val configContentObserverRetryIntervalMs = 10_000L
    private val hotStopGenerationReadIntervalMs = 250L

    private data class ConfigCandidate(
        val source: String,
        val config: JSONObject,
        val priority: Int
    ) {
        private val semanticFreshness: Long = maxOf(
            config.optLong("heartbeat_at", 0L),
            config.optLong("config_updated_at", 0L)
        )
        val freshness: Long = semanticFreshness.takeIf { it > 0L }
            ?: config.optLong("_file_modified_at", 0L)
        val generation: Long = config.optLong("config_generation", 0L)
        val inactive: Boolean = !config.optBoolean("active", false)
    }

    private data class RuntimeFrame(
        val active: Boolean,
        val snapshotGeneration: Long,
        val heartbeatUptimeMs: Long,
        val latitude: Double,
        val longitude: Double,
        val bearing: Double,
        val speed: Double
    )

    private data class TrustedJoystickFix(
        val generation: Long,
        val heartbeatUptimeMs: Long,
        val latitude: Double,
        val longitude: Double,
        val bearing: Double
    )

    @Volatile private var lastTrustedJoystickFix: TrustedJoystickFix? = null

    private fun parseRuntimeFrame(raw: String): RuntimeFrame? {
        val parts = raw.split('|')
        if (parts.size != 8 || parts[0] != "1") return null
        val active = when (parts[1]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val generation = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val heartbeatUptimeMs = parts[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val latitudeE7 = parts[4].toLongOrNull() ?: return null
        val longitudeE7 = parts[5].toLongOrNull() ?: return null
        val bearingCentiDegrees = parts[6].toIntOrNull() ?: return null
        val speedCentimetersPerSecond = parts[7].toIntOrNull() ?: return null
        val nowUptime = android.os.SystemClock.uptimeMillis()
        if (heartbeatUptimeMs > nowUptime + 5_000L) return null
        // Current writers always emit a real generation for both active and inactive
        // frames. Reject legacy generation zero so it cannot mask a newer snapshot on
        // a cold process start where no in-memory generation is available for comparison.
        if (generation == 0L || (active && heartbeatUptimeMs == 0L)) return null
        if (latitudeE7 !in -900_000_000L..900_000_000L ||
            longitudeE7 !in -1_800_000_000L..1_800_000_000L ||
            bearingCentiDegrees !in 0..35_999 ||
            speedCentimetersPerSecond !in 0..100_000
        ) return null
        return RuntimeFrame(
            active = active,
            snapshotGeneration = generation,
            heartbeatUptimeMs = heartbeatUptimeMs,
            latitude = latitudeE7 / 10_000_000.0,
            longitude = longitudeE7 / 10_000_000.0,
            bearing = bearingCentiDegrees / 100.0,
            speed = speedCentimetersPerSecond / 100.0
        )
    }

    private fun readStopGeneration(): Long {
        val observed = getSystemProperty("gsm.wrp.stop_generation", "0")
            .toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
        while (true) {
            val previous = highestObservedStopGeneration.get()
            if (observed <= previous) return previous
            if (highestObservedStopGeneration.compareAndSet(previous, observed)) return observed
        }
    }

    /**
     * A SystemProperties callback is process-local and may miss writes made by the root
     * helper. While an active snapshot is cached, sample only the tiny stop fence at a
     * bounded rate so STOP becomes pass-through promptly without restoring disk polling.
     * Inactive configs short-circuit before this method, so normal idle has no extra reads.
     */
    private fun readStopGenerationHotPath(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastHotStopGenerationReadAt.get()
            if (previous != 0L && now - previous < hotStopGenerationReadIntervalMs) {
                return highestObservedStopGeneration.get()
            }
            if (lastHotStopGenerationReadAt.compareAndSet(previous, now)) {
                return readStopGeneration()
            }
        }
    }

    private fun isStoppedGeneration(generation: Long, stopGeneration: Long): Boolean {
        return stopGeneration > 0L &&
            (generation <= 0L || generation <= stopGeneration)
    }

    private fun normalizedCandidate(
        source: String,
        rawConfig: JSONObject,
        priority: Int,
        runtimeFrame: RuntimeFrame? = null,
        stopGeneration: Long = readStopGeneration()
    ): ConfigCandidate? {
        return try {
            ConfigCandidate(
                source,
                normalizeConfig(rawConfig, runtimeFrame, stopGeneration),
                priority
            )
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "config:normalize:$source:${e.javaClass.name}",
                "readConfig ignored invalid $source config: ${e.javaClass.simpleName}: ${e.message}",
                30_000L
            )
            null
        }
    }

    private fun selectFreshestConfig(candidates: List<ConfigCandidate>): ConfigCandidate? {
        return candidates.maxWithOrNull(
            compareBy<ConfigCandidate> { it.freshness }
                // At an exact freshness tie, an explicit stop is safer and represents the
                // terminal lifecycle transition. This also covers a stop written in the same
                // millisecond as the last active heartbeat.
                .thenBy { if (it.inactive) 1 else 0 }
                .thenBy { it.generation }
                .thenBy { it.priority }
        )
    }

    private fun publishConfigCandidate(
        requestSource: String,
        candidate: ConfigCandidate,
        stopGeneration: Long = readStopGeneration(),
        runtimeFrame: RuntimeFrame? = null
    ): JSONObject {
        var selectedCandidate = candidate
        val cached = lastConfig
        val cachedGeneration = cached?.optLong("config_generation", 0L) ?: 0L
        if (candidate.inactive &&
            cached?.optBoolean("active", false) == true &&
            cachedGeneration > candidate.generation &&
            (stopGeneration == 0L || cachedGeneration > stopGeneration)
        ) {
            // A timed-out older tombstone may land after a newer run. Refresh the
            // newer in-memory snapshot (including TTL validation) before deciding;
            // never let the stale inactive generation overwrite it blindly.
            shallowCopyConfig(cached)?.let { cachedCopy ->
                normalizedCandidate(
                    "memory-newer-than-${candidate.source}",
                    cachedCopy,
                    candidate.priority,
                    runtimeFrame,
                    stopGeneration
                )?.let { selectedCandidate = it }
            }
        }
        val activeChanged = lastConfig?.optBoolean("active", false) !=
            selectedCandidate.config.optBoolean("active", false)
        lastConfig = selectedCandidate.config
        configPollIntervalMs = when {
            !selectedCandidate.config.optBoolean("active", false) ->
                if (registeredGnssStatusCallbacks.isNotEmpty()) 2_000L else inactiveConfigPollIntervalMs
            // Property callbacks are not guaranteed to fire across processes. The
            // joystick publishes a new frame every 250 ms, so system_server must
            // observe it independently of a lifecycle/content notification.
            currentPackageName == "android" &&
                selectedCandidate.config.optBoolean("joystick_mode", false) -> 250L
            currentPackageName == "android" -> 1_000L
            registeredGnssStatusCallbacks.isNotEmpty() -> 2_000L
            !isSystemLocationSwitchEnabled() -> if (
                configChangeCallbackInstalled || configContentObserverInstalled
            ) {
                inactiveConfigPollIntervalMs
            } else {
                locationDisabledPollIntervalMs
            }
            // Runtime frames normally wake the poller. This fallback exists only to
            // enforce the 30-second TTL after an unexpected writer death.
            configChangeCallbackInstalled || configContentObserverInstalled ->
                activeConfigTtlMs + 1_000L
            else -> 1_000L
        }
        logOpenCellConfigLoaded(
            "$requestSource:${selectedCandidate.source}",
            selectedCandidate.config
        )
        if (activeChanged ||
            (selectedCandidate.config.optBoolean("active", false) && !systemInjectorStarted)
        ) {
            wakeSystemLocationInjector()
        }
        if (activeChanged && shouldDeliverLocation(selectedCandidate.config)) {
            startRegisteredGnssStatusInjectors()
        }
        return selectedCandidate.config
    }

    private fun logOpenCellConfigLoaded(source: String, config: JSONObject) {
        val cellArray = config.optJSONArray("cell_json")
        val cellCount = cellArray?.length() ?: 0
        val wifiCount = config.optJSONObject("wifi_json")?.optJSONArray("nearbyWifi")?.length() ?: 0
        val logKey = "${config.optBoolean("active", false)}|${config.optBoolean("fail_closed", false)}|${config.optBoolean("mock_cell", false)}|$cellCount|$wifiCount"
        if (logKey == lastOpenCellConfigLogKey) return
        lastOpenCellConfigLogKey = logKey
        val firstCell = if (cellArray != null && cellArray.length() > 0) cellArray.optJSONObject(0) else null
        val firstSummary = if (firstCell != null) {
            val type = cellPayload.normalizeCellType(firstCell.optString("type", firstCell.optString("radio", "LTE")))
            val area = cellPayload.cellAreaCode(firstCell, 0)
            val identity = cellPayload.cellIdentityCode(firstCell, 0)
            "$type/${cellPayload.cellOperatorNumeric(firstCell) ?: "unknown"} area=$area identity=$identity"
        } else {
            "none"
        }
        XposedBridge.logOpenCellId(
            "readConfig[$source] active=${config.optBoolean("active", false)} failClosed=${config.optBoolean("fail_closed", false)} mockCell=${config.optBoolean("mock_cell", false)} wifiCount=$wifiCount cellJsonCount=$cellCount firstCell=$firstSummary"
        )
    }

    private fun configReadPaths(): Array<String> {
        return if (android.os.Process.myUid() == 1000) {
            arrayOf(systemConfigPath)
        } else {
            arrayOf(localConfigPath)
        }
    }

    private fun logConfigDebugEvery(key: String, msg: String, intervalMs: Long = 60_000L) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = providerConfigDebugLastTimes[key]
        if (last == null || now - last >= intervalMs) {
            if (providerConfigDebugLastTimes.size >= 128 && last == null) {
                providerConfigDebugLastTimes.clear()
            }
            providerConfigDebugLastTimes[key] = now
            android.util.Log.d("WeRePosition_Debug", msg)
        }
    }

    private fun shouldSpoofLocation(config: JSONObject?): Boolean {
        if (config?.optBoolean("active", false) != true) return false
        val stopped = isStoppedGeneration(
            config.optLong("config_generation", 0L),
            readStopGenerationHotPath()
        )
        if (stopped) requestConfigRefresh()
        return !stopped
    }

    private fun shouldDeliverLocation(config: JSONObject?): Boolean {
        return shouldSpoofLocation(config) &&
            config?.optBoolean("fail_closed", false) != true &&
            hasValidTargetCoordinates(config) && isSystemLocationSwitchEnabled()
    }

    private fun locationAllowedForCaller(config: JSONObject?, callerPackage: String?): Boolean {
        if (config?.optBoolean("location_allowlist_enabled", false) != true) return true
        if (callerPackage == null) return false
        if (isInfrastructurePackage(callerPackage)) return true
        val allowed = config.optJSONArray("location_allowlist") ?: return false
        return (0 until allowed.length()).any { allowed.optString(it) == callerPackage }
    }

    private fun callerPackageFromIdentity(identity: Any?): String? = identity?.let {
        runCatching { XposedHelpers.callMethod(it, "getPackageName") as? String }.getOrNull()
    }

    private fun locationAllowedForRequest(
        config: JSONObject?, callerPackage: String?, request: Any?
    ): Boolean {
        if (!locationAllowedForCaller(config, callerPackage)) return false
        if (config?.optBoolean("location_allowlist_enabled", false) != true) return true
        // GMS can make a framework request on behalf of an app. Its CallerIdentity
        // remains com.google.android.gms, while LocationRequest.WorkSource names the
        // actual consumer. Never let that delegation bypass the app selection.
        val workSource = runCatching {
            request?.let { XposedHelpers.callMethod(it, "getWorkSource") }
        }.getOrNull() ?: return true
        val size = runCatching { XposedHelpers.callMethod(workSource, "size") as Int }.getOrDefault(0)
        for (index in 0 until size) {
            val packageName = runCatching {
                XposedHelpers.callMethod(workSource, "getName", index) as? String
            }.getOrNull() ?: return false
            if (!locationAllowedForCaller(config, packageName)) return false
        }
        return true
    }

    private fun callerPackageFromRegistration(registration: Any?): String? = registration?.let {
        val identity = runCatching { XposedHelpers.callMethod(it, "getIdentity") }.getOrNull()
        callerPackageFromIdentity(identity)
    }

    private fun locationAllowedForRegistration(config: JSONObject?, registration: Any?): Boolean {
        val request = registration?.let {
            runCatching { XposedHelpers.callMethod(it, "getRequest") }.getOrNull()
        }
        return locationAllowedForRequest(config, callerPackageFromRegistration(registration), request)
    }

    private fun shouldBlockRealLocation(config: JSONObject?): Boolean {
        // Pure mode arms this guard before the Android location switch is enabled.
        // Keep it active through stop until the switch is off and the tombstone is saved.
        if (getSystemProperty("gsm.wrp.pure_armed", "false") == "true") return true
        if (shouldSpoofLocation(config)) return true
        // The compact runtime property is published before the full snapshot can be
        // reloaded by every hooked process. Block that activation window as well.
        return config == null && getSystemProperty("gsm.wrp.active", "false") == "true"
    }

    private fun hasValidTargetCoordinates(config: JSONObject?): Boolean {
        if (config == null || !config.has("lat") || !config.has("lng")) return false
        val lat = config.optDouble("lat", Double.NaN)
        val lng = config.optDouble("lng", Double.NaN)
        return lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
    }

    private fun shouldMockWifi(config: JSONObject?): Boolean {
        return (config?.optBoolean("mock_wifi", false) == true ||
            config?.optBoolean("specified_wifi_enabled", false) == true) &&
            shouldDeliverLocation(config)
    }

    private fun shouldMockCell(config: JSONObject?): Boolean {
        return config?.optBoolean("mock_cell", false) == true &&
            shouldDeliverLocation(config)
    }

    private fun isSystemLocationSwitchEnabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSystemLocationCheckAt < 1_000L) {
            return lastSystemLocationEnabled
        }
        lastSystemLocationCheckAt = now
        val context = currentProcessContext()
        if (context == null) {
            lastSystemLocationEnabled = false
            return false
        }
        val managerState = runCatching {
            val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as?
                android.location.LocationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                manager?.isLocationEnabled
            } else {
                null
            }
        }.getOrNull()
        val settingsState = runCatching {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF
            ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
        }.getOrNull()
        // Unknown state means do not interfere. Returning false here is fail-open
        // for normal Android behaviour because all hooks simply pass through.
        lastSystemLocationEnabled = managerState ?: settingsState ?: false
        return lastSystemLocationEnabled
    }

    private fun currentProcessContext(): android.content.Context? {
        cachedProcessContext?.let { return it }
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (_: Throwable) {
            null
        } ?: try {
            val activityThread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread")
                .invoke(null)
            activityThread?.let { XposedHelpers.callMethod(it, "getSystemContext") as? android.content.Context }
        } catch (_: Throwable) {
            null
        }
        if (context != null) cachedProcessContext = context
        return context
    }

    private fun normalizeConfig(
        config: JSONObject,
        runtimeFrame: RuntimeFrame? = null,
        stopGeneration: Long = readStopGeneration()
    ): JSONObject {
        applyRuntimePropertyOverlay(config, runtimeFrame, stopGeneration)
        if (config.optBoolean("active", false) &&
            isStoppedGeneration(config.optLong("config_generation", 0L), stopGeneration)
        ) {
            markExpiredConfigInactive(config)
        }
        if (config.opt("wifi_json") !is org.json.JSONObject) {
            config.put("wifi_json", emptyWifiPayload())
        }
        if (config.opt("cell_json") !is org.json.JSONArray) {
            config.put("cell_json", org.json.JSONArray())
        }
        config.put("bluetooth_json", org.json.JSONArray())
        if (config.optBoolean("active", false)) {
            val now = System.currentTimeMillis()
            val configBootId = config.optString("boot_id", "")
            val currentBootId = readBootId()
            if (configBootId.isNotEmpty() && currentBootId.isNotEmpty() && configBootId != currentBootId) {
                // Android can restore its location master switch before our BOOT_COMPLETED
                // receiver runs. Keep the previous active snapshot as a blocking fence
                // until that receiver disables the switch and writes an explicit stop.
                markActiveConfigUnavailable(config)
                XposedBridge.logOpenCellIdEvery(
                    "config:boot-expired",
                    "readConfig blocked real location from previous-boot active config",
                    30_000L
                )
            }
            val heartbeatAt = maxOf(
                config.optLong("heartbeat_at", 0L),
                config.optLong("config_updated_at", 0L)
            )
            val heartbeatUptime = config.optLong("heartbeat_uptime_ms", 0L)
            val heartbeatAgeMs = if (heartbeatUptime > 0L) {
                val currentUptime = android.os.SystemClock.uptimeMillis()
                if (heartbeatUptime <= currentUptime) currentUptime - heartbeatUptime else Long.MAX_VALUE
            } else if (heartbeatAt > 0L) {
                (now - heartbeatAt).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
            if (config.optBoolean("active", false) && heartbeatAgeMs == Long.MAX_VALUE) {
                markActiveConfigUnavailable(config)
                XposedBridge.logOpenCellIdEvery(
                    "config:heartbeat-missing",
                    "readConfig blocked real data because active config has no trusted heartbeat",
                    30_000L
                )
            } else if (
                config.optBoolean("active", false) &&
                heartbeatAgeMs > activeConfigTtlMs
            ) {
                markActiveConfigUnavailable(config)
                XposedBridge.logOpenCellIdEvery(
                    "config:expired",
                    "readConfig retained the last safe simulation state after stale active config age=${heartbeatAgeMs}ms",
                    30_000L
                )
            } else if (config.optBoolean("joystick_mode", false) &&
                hasValidTargetCoordinates(config)
            ) {
                // A recovered writer resumes live movement after a frozen interval.
                config.put("fail_closed", false)
            }
        } else if (!config.has("fail_closed")) {
            config.put("fail_closed", false)
        }
        val lat = config.optDouble("lat", 0.0)
        val lng = config.optDouble("lng", 0.0)
        // Runtime lat/lng are canonical WGS-84. External map adapters convert at their edge.
        config.put(
            "jitter_radius_meters",
            config.optDouble("jitter_radius_meters", DEFAULT_JITTER_RADIUS_METERS)
                .coerceIn(MIN_JITTER_RADIUS_METERS, MAX_JITTER_RADIUS_METERS)
        )
        val jitterSpeed = config.optString("jitter_speed", "MEDIUM").uppercase()
        config.put("jitter_speed", if (jitterSpeed in setOf("SLOW", "MEDIUM", "FAST")) jitterSpeed else "MEDIUM")
        config.put("sim_speed", config.optDouble("sim_speed", 0.0).coerceAtLeast(0.0))
        config.put("satellite_count", config.optInt("satellite_count", 20).coerceIn(0, 64))
        config.put("mock_wifi", config.optBoolean("mock_wifi", false))
        config.put("mock_cell", config.optBoolean("mock_cell", false))
        config.put("mock_bluetooth", false)
        config.put("signal_jitter_enabled", config.optBoolean("signal_jitter_enabled", true))
        // Joystick frames define movement; stale fallback settings must not add drift.
        if (config.optBoolean("joystick_mode", false)) config.put("enable_jitter", false)
        config.put("signal_jitter_level", config.optInt("signal_jitter_level", 40).coerceIn(0, 100))
        val wifiMode = config.optString("wifi_connection_mode", "FIXED").uppercase()
        config.put("wifi_connection_mode", if (wifiMode == "RANDOM") "RANDOM" else "FIXED")
        return config
    }

    private fun applyRuntimePropertyOverlay(
        config: JSONObject,
        suppliedRuntimeFrame: RuntimeFrame? = null,
        stopGeneration: Long = readStopGeneration()
    ) {
        val runtimeFrame = suppliedRuntimeFrame ?: parseRuntimeFrame(
            getSystemProperty("gsm.wrp.frame", "")
        )
        if (runtimeFrame != null) {
            val configGeneration = config.optLong("config_generation", 0L)
            if (!runtimeFrame.active) {
                // A delayed stop from an older generation must not turn off a newer
                // active snapshot. Its stop fence still blocks every older/equal run.
                if (runtimeFrame.snapshotGeneration < configGeneration &&
                    !isStoppedGeneration(configGeneration, stopGeneration)
                ) return
                markExpiredConfigInactive(config)
                return
            }
            // Likewise, ignore a delayed active frame when the loaded snapshot is
            // already newer. If both are fenced, fail open to normal platform data.
            if (runtimeFrame.snapshotGeneration < configGeneration &&
                !isStoppedGeneration(configGeneration, stopGeneration)
            ) return
            if (isStoppedGeneration(runtimeFrame.snapshotGeneration, stopGeneration)) {
                markExpiredConfigInactive(config)
                return
            }
            if (!config.optBoolean("active", false)) return
            if (configGeneration <= 0L ||
                runtimeFrame.snapshotGeneration != configGeneration
            ) return
            val previousUptime = config.optLong("heartbeat_uptime_ms", 0L)
            if (previousUptime > runtimeFrame.heartbeatUptimeMs) return
            if (config.optBoolean("joystick_mode", false)) {
                val previousFix = lastTrustedJoystickFix
                if (previousFix == null || previousFix.generation != configGeneration ||
                    runtimeFrame.heartbeatUptimeMs >= previousFix.heartbeatUptimeMs
                ) {
                    lastTrustedJoystickFix = TrustedJoystickFix(
                        configGeneration, runtimeFrame.heartbeatUptimeMs,
                        runtimeFrame.latitude, runtimeFrame.longitude, runtimeFrame.bearing
                    )
                }
            }
            config.put("lat", runtimeFrame.latitude)
            config.put("lng", runtimeFrame.longitude)
            config.put("sim_bearing", runtimeFrame.bearing)
            config.put("sim_speed", runtimeFrame.speed)
            config.put("heartbeat_uptime_ms", runtimeFrame.heartbeatUptimeMs)
            return
        }

        // Legacy property overlay remains as a compatibility fallback for snapshots
        // created before the compact runtime-frame protocol was introduced.
        if (!config.optBoolean("active", false)) return
        if (getSystemProperty("gsm.wrp.active", "false").lowercase() != "true") return
        val propertyHeartbeat = getSystemProperty("gsm.wrp.heartbeat", "0")
            .toLongOrNull() ?: return
        val configHeartbeat = maxOf(
            config.optLong("heartbeat_at", 0L),
            config.optLong("config_updated_at", 0L)
        )
        if (propertyHeartbeat <= configHeartbeat) return
        val propertyBootId = getSystemProperty("gsm.wrp.boot_id", "")
        val configBootId = config.optString("boot_id", "")
        if (propertyBootId.isNotEmpty() && configBootId.isNotEmpty() &&
            propertyBootId != configBootId
        ) return
        val lat = getSystemProperty("gsm.wrp.lat", "").toDoubleOrNull() ?: return
        val lng = getSystemProperty("gsm.wrp.lng", "").toDoubleOrNull() ?: return
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return
        config.put("lat", lat)
        config.put("lng", lng)
        config.put(
            "sim_bearing",
            getSystemProperty("gsm.wrp.bearing", "0").toDoubleOrNull() ?: 0.0
        )
        config.put(
            "sim_speed",
            (getSystemProperty("gsm.wrp.speed", "0").toDoubleOrNull() ?: 0.0)
                .coerceAtLeast(0.0)
        )
        config.put("heartbeat_at", propertyHeartbeat)
        config.put(
            "heartbeat_uptime_ms",
            getSystemProperty("gsm.wrp.heartbeat_uptime", "0").toLongOrNull() ?: 0L
        )
        if (propertyBootId.isNotEmpty()) config.put("boot_id", propertyBootId)
    }

    private fun emptyWifiPayload(): org.json.JSONObject = org.json.JSONObject().apply {
        put("isConnected", false)
        put("connectedWifi", org.json.JSONObject.NULL)
        put("nearbyWifi", org.json.JSONArray())
    }

    private fun safeWifiPayload(raw: String?): org.json.JSONObject = runCatching {
        org.json.JSONObject(raw ?: "")
    }.getOrElse { emptyWifiPayload() }

    private fun safeArrayPayload(raw: String?): org.json.JSONArray = runCatching {
        org.json.JSONArray(raw ?: "")
    }.getOrElse { org.json.JSONArray() }

    private fun markExpiredConfigInactive(config: JSONObject) {
        config.put("active", false)
        config.put("fail_closed", false)
        config.put(
            "wifi_json",
            org.json.JSONObject().apply {
                put("isConnected", false)
                put("connectedWifi", org.json.JSONObject.NULL)
                put("nearbyWifi", org.json.JSONArray())
            }
        )
        config.put("cell_json", org.json.JSONArray())
        config.put("bluetooth_json", org.json.JSONArray())
        // A dead writer is not an active simulation. Restore normal platform data
        // instead of keeping location/radio hooks engaged indefinitely.
        config.put("mock_wifi", false)
        config.put("mock_cell", false)
        config.put("mock_bluetooth", false)
        config.put("satellite_count", 0)
    }

    private fun markActiveConfigUnavailable(config: JSONObject) {
        // Only an explicit stop fence or a new boot may end an active simulation.
        // A dead writer must never expose a real position while the UI is still armed.
        config.put("active", true)
        config.put("wifi_json", emptyWifiPayload())
        config.put("cell_json", org.json.JSONArray())
        if (config.optBoolean("joystick_mode", false)) {
            val generation = config.optLong("config_generation", 0L)
            val trusted = lastTrustedJoystickFix?.takeIf { it.generation == generation }
            if (trusted != null) {
                config.put("lat", trusted.latitude)
                config.put("lng", trusted.longitude)
                config.put("sim_bearing", trusted.bearing)
            }
            if (hasValidTargetCoordinates(config)) {
                // Keep a stationary synthetic fix when live joystick frames stop.
                // Returning null here makes one-shot requests and Maps lose their
                // location even though a trusted fake point is still available.
                config.put("sim_speed", 0.0)
                config.put("enable_jitter", false)
                config.put("fail_closed", false)
                return
            }
        }
        config.put("fail_closed", true)
        config.put("satellite_count", 0)
    }

    private fun readBootId(): String {
        if (cachedBootIdInitialized) return cachedBootId.orEmpty()
        synchronized(bootIdLock) {
            if (cachedBootIdInitialized) return cachedBootId.orEmpty()
            val value = try {
                File("/proc/sys/kernel/random/boot_id").readText().trim()
            } catch (_: Throwable) {
                ""
            }
            cachedBootId = value
            // Cache an empty result too. A domain denied by SELinux must not probe
            // /proc again on every 1 Hz runtime frame.
            cachedBootIdInitialized = true
            return value
        }
    }

    private fun shallowCopyConfig(config: JSONObject): JSONObject? {
        return try {
            val names = ArrayList<String>()
            val keys = config.keys()
            while (keys.hasNext()) names.add(keys.next())
            if (names.isEmpty()) JSONObject() else JSONObject(config, names.toTypedArray())
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "config:shallow-copy:${e.javaClass.name}",
                "readConfig could not copy cached config: ${e.javaClass.simpleName}: ${e.message}",
                30_000L
            )
            null
        }
    }

    private fun loadConfigFromDisk(source: String): JSONObject? {
        val candidates = ArrayList<ConfigCandidate>()
        val runtimeFrame = parseRuntimeFrame(getSystemProperty("gsm.wrp.frame", ""))
        val stopGeneration = readStopGeneration()
        val lifecycleState = getSystemProperty(
            "gsm.wrp.active",
            "__wereposition_property_missing__"
        ).lowercase()
        val hasNewerActiveFrame = runtimeFrame?.active == true &&
            !isStoppedGeneration(runtimeFrame.snapshotGeneration, stopGeneration)
        val publishedSnapshotGeneration = if (runtimeFrame?.active != true) {
            getSystemProperty("gsm.wrp.snapshot_generation", "0")
                .toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } else {
            0L
        }
        val inactiveFrameSuperseded = runtimeFrame?.active == false &&
            publishedSnapshotGeneration > runtimeFrame.snapshotGeneration &&
            publishedSnapshotGeneration > stopGeneration
        // Before the stop-fence protocol existed, STOP could leave active=false and a
        // generation-zero/missing frame beside a newer active snapshot. Do not let that
        // legacy property hide the snapshot before disk/provider freshness is compared.
        // Current STOP writers publish snapshot_generation == stop_generation, so they
        // never satisfy this strictly-newer test.
        val legacyLifecycleSuperseded = runtimeFrame == null &&
            lifecycleState == "false" &&
            publishedSnapshotGeneration > 0L &&
            publishedSnapshotGeneration > stopGeneration
        val hasNewerActiveEvidence = hasNewerActiveFrame ||
            inactiveFrameSuperseded ||
            legacyLifecycleSuperseded

        // The lifecycle property is the cheap stop tombstone. It wins without touching
        // files/provider unless a runtime frame proves that a strictly newer run is active.
        // This keeps idle scoped processes passive without letting a delayed old stop kill
        // a newer generation.
        if (runtimeFrame?.active == false &&
            stopGeneration > 0L &&
            !inactiveFrameSuperseded
        ) {
            val inactiveBase = lastConfig?.let(::shallowCopyConfig) ?: JSONObject()
            inactiveBase.put("active", false)
            inactiveBase.put("config_generation", runtimeFrame.snapshotGeneration)
            normalizedCandidate(
                "runtime-frame-stop",
                inactiveBase,
                500,
                runtimeFrame,
                stopGeneration
            )?.let {
                return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
            }
        }
        if (lifecycleState == "false" && !hasNewerActiveEvidence) {
            loadConfigFromProperties(stopGeneration)?.let { propertyConfig ->
                normalizedCandidate(
                    "system-properties-stop",
                    propertyConfig,
                    400,
                    runtimeFrame,
                    stopGeneration
                )?.let {
                    return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
                }
            }
        }

        if ((lifecycleState == "true" || hasNewerActiveEvidence) &&
            runtimeFrame?.active == true
        ) {
            val cached = lastConfig
            if (cached?.optBoolean("active", false) == true &&
                cached.optLong("config_generation", 0L) == runtimeFrame.snapshotGeneration
            ) {
                val cachedCopy = shallowCopyConfig(cached)
                if (cachedCopy != null) {
                    normalizedCandidate(
                        "runtime-frame",
                        cachedCopy,
                        450,
                        runtimeFrame,
                        stopGeneration
                    )?.let {
                        return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
                    }
                }
            }
        }

        val errors = ArrayList<String>()
        if (!configDiskReadDisabled) {
            for ((index, path) in configReadPaths().withIndex()) {
                try {
                    val file = File(path)
                    if (!file.exists()) {
                        errors.add("$path missing")
                        continue
                    }
                    val rawConfig = JSONObject(file.readText())
                    if (!rawConfig.has("active")) {
                        errors.add("$path missing active state")
                        continue
                    }
                    val modifiedAt = file.lastModified()
                    rawConfig.put("_file_modified_at", modifiedAt)
                    normalizedCandidate(
                        "disk:$path",
                        rawConfig,
                        200 - index,
                        runtimeFrame,
                        stopGeneration
                    )?.let(candidates::add)
                    // Each SELinux domain has one known-readable path. Never probe the other
                    // domain after a valid primary snapshot; doing so caused thousands of AVCs.
                    if (candidates.isNotEmpty()) break
                } catch (e: Throwable) {
                    val detail = "${e.javaClass.simpleName}: ${e.message}"
                    errors.add("$path $detail")
                    if (e is SecurityException ||
                        detail.contains("EACCES", ignoreCase = true) ||
                        detail.contains("Permission denied", ignoreCase = true)
                    ) {
                        // One denied open is enough evidence for this process/domain.
                        // Future generations use the provider cache instead of producing AVCs.
                        configDiskReadDisabled = true
                        break
                    }
                }
            }
        } else {
            errors.add("disk source disabled for this SELinux domain")
        }

        selectFreshestConfig(candidates)?.let { diskCandidate ->
            val shadowedByNewerActiveFrame = hasNewerActiveFrame &&
                diskCandidate.inactive &&
                diskCandidate.generation < runtimeFrame.snapshotGeneration
            if (!shadowedByNewerActiveFrame) {
                return publishConfigCandidate(
                    source,
                    diskCandidate,
                    stopGeneration,
                    runtimeFrame
                )
            }
        }

        // When no disk source is readable, a live provider snapshot is authoritative. In
        // particular, provider active=false must not be mistaken for a missing source and then
        // replaced by an older active preference/cache value.
        if (lifecycleState == "true" || hasNewerActiveEvidence) {
            loadConfigFromContentProvider()?.let { providerConfig ->
                normalizedCandidate(
                    "content-provider",
                    providerConfig,
                    300,
                    runtimeFrame,
                    stopGeneration
                )?.let {
                    return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
                }
            }
        }

        // Disk/provider absence is exceptional. Do not touch XSharedPreferences here:
        // cross-app data probing can itself create SELinux AVCs. Properties are the
        // only reduced fallback and never carry rich RF payloads.
        loadConfigFromProperties(stopGeneration)?.let { propertyConfig ->
            normalizedCandidate(
                "system-properties",
                propertyConfig,
                90,
                runtimeFrame,
                stopGeneration
            )?.let(candidates::add)
        }

        selectFreshestConfig(candidates)?.let {
            return publishConfigCandidate(source, it, stopGeneration, runtimeFrame)
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val isPermissionDenied = errors.any { it.contains("EACCES") || it.contains("Permission denied") }
        val logIntervalMs = if (isPermissionDenied) 60_000L else 10_000L
        configPollIntervalMs = 60_000L
        if (now - lastOpenCellConfigReadFailureLogTime > logIntervalMs) {
            lastOpenCellConfigReadFailureLogTime = now
            XposedBridge.logOpenCellId("readConfig[$source] no readable config (${errors.joinToString(" | ")})")
        }

        return publishCachedConfigIfNoSource(source, stopGeneration, runtimeFrame)
    }

    private fun publishCachedConfigIfNoSource(
        source: String,
        stopGeneration: Long = readStopGeneration(),
        runtimeFrame: RuntimeFrame? = null
    ): JSONObject? {
        val cached = lastConfig ?: return null
        // normalizeConfig mutates only top-level fields. Preserve the immutable nested
        // Wi-Fi/cell payload references and avoid serializing/parsing them every frame.
        val cachedCopy = shallowCopyConfig(cached) ?: return null
        val candidate = normalizedCandidate(
            "memory-cache",
            cachedCopy,
            0,
            runtimeFrame,
            stopGeneration
        ) ?: return null
        return publishConfigCandidate(source, candidate, stopGeneration, runtimeFrame)
    }

    private fun getSystemProperty(key: String, default: String): String {
        return try {
            val method = cachedSystemPropertyGetMethod ?: synchronized(systemPropertyMethodLock) {
                cachedSystemPropertyGetMethod ?: Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .also { cachedSystemPropertyGetMethod = it }
            }
            method.invoke(null, key, default) as String
        } catch (e: Throwable) {
            default
        }
    }

    private fun requestConfigRefresh() {
        if (configPollWake.availablePermits() == 0) {
            configPollWake.release()
        }
    }

    private fun requestConfigRefreshIfRuntimeStateChanged() {
        val runtimeFrame = getSystemProperty("gsm.wrp.frame", "")
        val stopGeneration = getSystemProperty("gsm.wrp.stop_generation", "0")
        if (runtimeFrame == lastObservedRuntimeFrame &&
            stopGeneration == lastObservedStopGeneration
        ) return
        lastObservedRuntimeFrame = runtimeFrame
        lastObservedStopGeneration = stopGeneration
        requestConfigRefresh()
    }

    /**
     * Lifecycle commits notify this exported, read-only provider URI. ContentService keeps
     * the observer dormant in system_server while idle and wakes the existing background
     * config poller only for an actual start/stop/full-snapshot commit. This closes the
     * cross-process notification gap without shortening the 31/60-second safety fallback.
     */
    private fun installConfigContentObserver() {
        if (configContentObserverInstalled) return
        val context = currentProcessContext() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastConfigContentObserverAttemptAt.get()
            if (previous != 0L && now - previous < configContentObserverRetryIntervalMs) return
            if (lastConfigContentObserverAttemptAt.compareAndSet(previous, now)) break
        }
        synchronized(configContentObserverLock) {
            if (configContentObserverInstalled) return
            try {
                val observer = object : android.database.ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        requestConfigRefreshIfRuntimeStateChanged()
                    }

                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        requestConfigRefreshIfRuntimeStateChanged()
                    }
                }
                context.contentResolver.registerContentObserver(
                    configContentUri,
                    true,
                    observer
                )
                configContentObserver = observer
                configContentObserverInstalled = true
                // Cover a commit racing between the initial load and observer registration.
                requestConfigRefresh()
            } catch (e: Throwable) {
                XposedBridge.logEvery(
                    "configContentObserverUnavailable:${e.javaClass.name}",
                    "[WeRePosition] config content observer unavailable; using fallback poll"
                )
            }
        }
    }

    private fun installConfigChangeCallback() {
        if (configChangeCallbackInstalled) return
        try {
            lastObservedRuntimeFrame = getSystemProperty("gsm.wrp.frame", "")
            lastObservedStopGeneration = getSystemProperty("gsm.wrp.stop_generation", "0")
            val callback = Runnable {
                // SystemProperties callbacks are global, not key-specific. Keep the
                // hot callback O(1): two memory-backed reads and equality comparisons.
                requestConfigRefreshIfRuntimeStateChanged()
            }
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("addChangeCallback", Runnable::class.java)
            method.isAccessible = true
            method.invoke(null, callback)
            configChangeCallback = callback
            configChangeCallbackInstalled = true
            // Close the tiny read-before-register race: if the frame changed between
            // initial load and callback registration, this one coalesced refresh sees it.
            requestConfigRefresh()
        } catch (e: Throwable) {
            // Hidden-API availability varies by Android release. The long idle fallback poll
            // remains in place; this transition log is emitted once per process only.
            XposedBridge.logEvery(
                "configChangeCallbackUnavailable:${e.javaClass.name}",
                "[WeRePosition] property change callback unavailable; using idle fallback poll"
            )
        }
    }


    private fun loadConfigFromContentProvider(): JSONObject? {
        return try {
            val context = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: return null
            
            val uri = Uri.parse("content://com.shiraka.wereposition.provider")
            val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
            cursor.use { c ->
                if (c.moveToFirst()) {
                    val activeInt = c.getInt(c.getColumnIndexOrThrow("active"))
                    val heartbeatAt = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("heartbeat_at"))
                    }.getOrDefault(0L)
                    val heartbeatUptimeMs = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("heartbeat_uptime_ms"))
                    }.getOrDefault(0L)
                    val configUpdatedAt = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("config_updated_at"))
                    }.getOrDefault(heartbeatAt)
                    val configGeneration = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("config_generation"))
                    }.getOrDefault(0L)
                    val lifecycleToken = runCatching {
                        c.getLong(c.getColumnIndexOrThrow("runtime_lifecycle_token"))
                    }.getOrDefault(0L)
                    val bootId = runCatching {
                        c.getString(c.getColumnIndexOrThrow("boot_id"))
                    }.getOrDefault("")
                    if (activeInt != 1) {
                        return JSONObject().apply {
                            put("active", false)
                            put("fail_closed", false)
                            put("heartbeat_at", 0L)
                            put("heartbeat_uptime_ms", 0L)
                            put("config_updated_at", configUpdatedAt)
                            put("config_generation", configGeneration)
                            put("runtime_lifecycle_token", lifecycleToken)
                            put("boot_id", bootId)
                            put("wifi_json", emptyWifiPayload())
                            put("cell_json", org.json.JSONArray())
                            put("bluetooth_json", org.json.JSONArray())
                        }
                    }
                    
                    val lat = c.getDouble(c.getColumnIndexOrThrow("lat"))
                    val lng = c.getDouble(c.getColumnIndexOrThrow("lng"))
                    val wifiJson = c.getString(c.getColumnIndexOrThrow("wifi_json"))
                    val cellJson = c.getString(c.getColumnIndexOrThrow("cell_json"))
                    val simBearing = c.getFloat(c.getColumnIndexOrThrow("sim_bearing"))
                    val simSpeed = runCatching {
                        c.getFloat(c.getColumnIndexOrThrow("sim_speed"))
                    }.getOrDefault(0f)
                    val altitude = c.getDouble(c.getColumnIndexOrThrow("altitude"))
                    val satelliteCount = c.getInt(c.getColumnIndexOrThrow("satellite_count"))
                    val enableJitter = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("enable_jitter")) == 1
                    }.getOrDefault(true)
                    val jitterRadius = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("jitter_radius_meters"))
                    }.getOrDefault(DEFAULT_JITTER_RADIUS_METERS.toInt())
                    val jitterSpeed = runCatching {
                        c.getString(c.getColumnIndexOrThrow("jitter_speed"))
                    }.getOrDefault("MEDIUM")
                    val signalJitterEnabled = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("signal_jitter_enabled")) == 1
                    }.getOrDefault(true)
                    val signalJitterLevel = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("signal_jitter_level"))
                    }.getOrDefault(40)
                    val wifiConnectionMode = runCatching {
                        c.getString(c.getColumnIndexOrThrow("wifi_connection_mode"))
                    }.getOrDefault("FIXED")
                    val mockWifi = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("mock_wifi")) == 1
                    }.getOrDefault(false)
                    val mockCell = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("mock_cell")) == 1
                    }.getOrDefault(false)
                    val specifiedWifiEnabled = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("specified_wifi_enabled")) == 1
                    }.getOrDefault(false)
                    val specifiedWifiJson = runCatching {
                        JSONObject(c.getString(c.getColumnIndexOrThrow("specified_wifi_json")))
                    }.getOrDefault(JSONObject())
                    val specifiedBluetoothEnabled = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("specified_bluetooth_enabled")) == 1
                    }.getOrDefault(false)
                    val specifiedBluetoothJson = runCatching {
                        org.json.JSONArray(c.getString(c.getColumnIndexOrThrow("specified_bluetooth_json")))
                    }.getOrDefault(org.json.JSONArray())
                    val locationAllowlistEnabled = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("location_allowlist_enabled")) == 1
                    }.getOrDefault(false)
                    val locationAllowlist = runCatching {
                        safeArrayPayload(c.getString(c.getColumnIndexOrThrow("location_allowlist")))
                    }.getOrDefault(org.json.JSONArray())
                    val config = JSONObject().apply {
                        put("active", true)
                        put("lat", lat)
                        put("lng", lng)
                        put("altitude", altitude)
                        put("sim_bearing", simBearing.toDouble())
                        put("sim_speed", simSpeed.toDouble())
                        put("satellite_count", satelliteCount)
                        // These values are emitted by the host's sole runtime writer. The reader
                        // must never renew liveness merely because a ContentProvider query ran.
                        put("heartbeat_at", heartbeatAt)
                        put("heartbeat_uptime_ms", heartbeatUptimeMs)
                        put("config_updated_at", configUpdatedAt)
                        put("config_generation", configGeneration)
                        put("runtime_lifecycle_token", lifecycleToken)
                        put("boot_id", bootId)
                        put("mock_cell", mockCell)
                        put("mock_wifi", mockWifi)
                        put("mock_bluetooth", false)
                        put("specified_wifi_enabled", specifiedWifiEnabled)
                        put("specified_wifi_json", specifiedWifiJson)
                        put("specified_bluetooth_enabled", specifiedBluetoothEnabled)
                        put("specified_bluetooth_json", specifiedBluetoothJson)
                        put("location_allowlist_enabled", locationAllowlistEnabled)
                        put("location_allowlist", locationAllowlist)
                        put("enable_jitter", enableJitter)
                        put("jitter_radius_meters", jitterRadius.coerceIn(1, 80))
                        put("jitter_speed", jitterSpeed)
                        put("signal_jitter_enabled", signalJitterEnabled)
                        put("signal_jitter_level", signalJitterLevel.coerceIn(0, 100))
                        put("wifi_connection_mode", wifiConnectionMode)
                        put("cell_json", safeArrayPayload(cellJson))
                        put("wifi_json", safeWifiPayload(wifiJson))
                        put("bluetooth_json", org.json.JSONArray())
                    }
                    val wifiCount = config.optJSONObject("wifi_json")
                        ?.optJSONArray("nearbyWifi")
                        ?.length() ?: 0
                    val cellCount = config.optJSONArray("cell_json")?.length() ?: 0
                    logConfigDebugEvery(
                        "provider:$currentPackageName:$wifiCount:$cellCount",
                        "loadConfigFromContentProvider in $currentPackageName: active=true wifi=$wifiCount cell=$cellCount"
                    )
                    config
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "config:provider:$currentPackageName:${e.javaClass.name}",
                "loadConfigFromContentProvider failed in $currentPackageName: ${e.javaClass.simpleName}: ${e.message}",
                60_000L
            )
            null
        }
    }

    private fun loadConfigFromProperties(
        stopGeneration: Long = readStopGeneration()
    ): JSONObject? {
        val missing = "__wereposition_property_missing__"
        val activeStr = getSystemProperty("gsm.wrp.active", missing).lowercase()
        logConfigDebugEvery(
            "properties:$currentPackageName:$activeStr",
            "loadConfigFromProperties in $currentPackageName: activeStr=$activeStr"
        )
        if (activeStr != "true" && activeStr != "false") {
            return null
        }
        return try {
            val heartbeatStr = getSystemProperty("gsm.wrp.heartbeat", "0")
            val heartbeat = heartbeatStr.toLongOrNull() ?: 0L
            val heartbeatUptime = getSystemProperty("gsm.wrp.heartbeat_uptime", "0")
                .toLongOrNull() ?: 0L
            val bootId = getSystemProperty("gsm.wrp.boot_id", "")
            val snapshotGeneration = getSystemProperty("gsm.wrp.snapshot_generation", "0")
                .toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            if (activeStr == "false") {
                return JSONObject().apply {
                    put("active", false)
                    put(
                        "config_generation",
                        stopGeneration.takeIf { it > 0L } ?: snapshotGeneration
                    )
                    put("fail_closed", false)
                    put("heartbeat_at", 0L)
                    put("config_updated_at", heartbeat)
                    put("boot_id", bootId)
                    put("wifi_json", emptyWifiPayload())
                    put("cell_json", org.json.JSONArray())
                    put("bluetooth_json", org.json.JSONArray())
                }
            }

            val latStr = getSystemProperty("gsm.wrp.lat", "0.0")
            val lngStr = getSystemProperty("gsm.wrp.lng", "0.0")
            val altStr = getSystemProperty("gsm.wrp.alt", "0.0")
            val bearingStr = getSystemProperty("gsm.wrp.bearing", "0.0")
            val speedStr = getSystemProperty("gsm.wrp.speed", "0.0")
            val satCountStr = getSystemProperty("gsm.wrp.sat_count", "10")
            val mockWifiStr = getSystemProperty("gsm.wrp.mock_wifi", "false")
            val mockCellStr = getSystemProperty("gsm.wrp.mock_cell", "false")
            val locationAllowlistEnabled =
                getSystemProperty("gsm.wrp.location_allowlist_enabled", "false") == "true"
            val enableJitterStr = getSystemProperty("gsm.wrp.enable_jitter", "true")
            val joystickMode = getSystemProperty("gsm.wrp.joystick_mode", "false") == "true"
            val jitterRadiusStr = getSystemProperty("gsm.wrp.jitter_radius", DEFAULT_JITTER_RADIUS_METERS.toInt().toString())
            val jitterSpeed = getSystemProperty("gsm.wrp.jitter_speed", "MEDIUM")
            val signalJitterStr = getSystemProperty("gsm.wrp.signal_jitter", "true")
            val signalJitterLevelStr = getSystemProperty("gsm.wrp.signal_jitter_level", "40")
            val wifiMode = getSystemProperty("gsm.wrp.wifi_mode", "FIXED")

            JSONObject().apply {
                put("active", true)
                put("config_generation", snapshotGeneration)
                put("lat", latStr.toDoubleOrNull() ?: 0.0)
                put("lng", lngStr.toDoubleOrNull() ?: 0.0)
                put("altitude", altStr.toDoubleOrNull() ?: 0.0)
                put("sim_bearing", bearingStr.toDoubleOrNull() ?: 0.0)
                put("sim_speed", speedStr.toDoubleOrNull() ?: 0.0)
                put("satellite_count", satCountStr.toIntOrNull() ?: 10)
                put("heartbeat_at", heartbeat)
                put("heartbeat_uptime_ms", heartbeatUptime)
                put("config_updated_at", heartbeat)
                put("boot_id", bootId)
                put("mock_cell", mockCellStr == "true")
                put("mock_wifi", mockWifiStr == "true")
                put("mock_bluetooth", false)
                put("location_allowlist_enabled", locationAllowlistEnabled)
                put("location_allowlist", org.json.JSONArray())
                put("enable_jitter", enableJitterStr == "true")
                put("joystick_mode", joystickMode)
                put("jitter_radius_meters", (jitterRadiusStr.toIntOrNull() ?: DEFAULT_JITTER_RADIUS_METERS.toInt()).coerceIn(1, 80))
                put("jitter_speed", jitterSpeed)
                put("signal_jitter_enabled", signalJitterStr == "true")
                put("signal_jitter_level", (signalJitterLevelStr.toIntOrNull() ?: 40).coerceIn(0, 100))
                put("wifi_connection_mode", wifiMode)
                put("cell_json", org.json.JSONArray())
            }
        } catch (e: Throwable) {
            XposedBridge.logOpenCellIdEvery(
                "config:properties:$currentPackageName:${e.javaClass.name}",
                "loadConfigFromProperties failed in $currentPackageName: ${e.javaClass.simpleName}: ${e.message}",
                60_000L
            )
            null
        }
    }

    /**
     * 从本地文件读取模拟配置(纯文件方案,无ContentProvider跨进程调用)
     *
     * 架构优化:
     *    由于此方法会被各种 Hook 在主线程极其高频地调用（例如每秒数百次），
     *    任何在主线程进行的文件 IO（哪怕是偶尔一次）都会导致严重的丢帧卡顿（Stutter）。
     *    因此重构为：在首次调用时启动一个后台守护线程（Daemon Thread），
     *    active 时在后台更新 Volatile 的 lastConfig；inactive 时由 system property
     *    change callback 唤醒，并仅保留一分钟一次的兼容性 fallback。
     *    主线程的 readConfig() 永远只返回内存中的 lastConfig，实现真正的 0 IO 延迟。
     */
    private fun readConfig(): JSONObject? {
        // Claim before the synchronous initial read. Hooks reached re-entrantly from that read
        // fail open against the current cache instead of starting a second permanent poller.
        if (configPollingClaimed.compareAndSet(false, true)) {
            try {
                loadConfigFromDisk("initial")
            } catch (e: Throwable) {
                XposedBridge.logOpenCellIdEvery(
                    "config:initial-load:${e.javaClass.name}",
                    "readConfig initial load failed: ${e.javaClass.simpleName}: ${e.message}",
                    30_000L
                )
            }
            installConfigChangeCallback()
            installConfigContentObserver()

            // Start one process-local watcher. It performs no file/provider I/O while
            // idle unless a lifecycle transition wakes it or the long fallback expires.
            Thread {
                while (true) {
                    try {
                        configPollWake.tryAcquire(
                            configPollIntervalMs,
                            java.util.concurrent.TimeUnit.MILLISECONDS
                        )
                        // Retry observer registration only on this existing low-frequency
                        // watcher. A transient early-boot/context failure therefore recovers
                        // without adding a timer or work to hot location/radio hooks.
                        installConfigContentObserver()
                        loadConfigFromDisk("poll")
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        XposedBridge.logOpenCellId("WeRePosition config poller interrupted")
                        break
                    } catch (e: Throwable) {
                        // A malformed/transient source must not permanently freeze the
                        // volatile cache. Log this iteration and retry on the next tick.
                        XposedBridge.logOpenCellIdEvery(
                            "config:poll-load:${e.javaClass.name}",
                            "readConfig poll failed: ${e.javaClass.simpleName}: ${e.message}",
                            30_000L
                        )
                    }
                }
            }.apply {
                isDaemon = true
                name = "WeRePosition_ConfigPoller"
                start()
            }
        }
        return lastConfig
    }

    private var cachedGpsSatellitesList: Iterable<Any>? = null
    private var lastGpsSatellitesUpdate = 0L

    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    private fun createSpoofedGpsSatellites(classLoader: ClassLoader): Iterable<Any> {
        val now = System.currentTimeMillis()
        if (cachedGpsSatellitesList == null || now - lastGpsSatellitesUpdate > 1000) {
            val list = ArrayList<Any>()
            try {
                updateSatelliteCacheIfNeeded()
                if (!isSpoofingActiveCache || cachedSatellites == null) return list

                val satelliteClass = classLoader.loadClass("android.location.GpsSatellite")
                val constructor = satelliteClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                constructor.isAccessible = true

                for (i in 0 until spoofingCountCache) {
                    val data = getCachedSatellite(i) ?: continue
                    val prn = if (data.type == 3) data.svid + 64 else data.svid
                    val sat = constructor.newInstance(prn)
                    try { XposedHelpers.setBooleanField(sat, "mValid", true) } catch (e: Throwable) {}
                    try { XposedHelpers.setBooleanField(sat, "mHasEphemeris", true) } catch (e: Throwable) {}
                    try { XposedHelpers.setBooleanField(sat, "mHasAlmanac", true) } catch (e: Throwable) {}
                    try { XposedHelpers.setBooleanField(sat, "mUsedInFix", data.usedInFix) } catch (e: Throwable) {}
                    try { 
                        val f = satelliteClass.getDeclaredField("mSnr")
                        f.isAccessible = true
                        f.setFloat(sat, data.cn0)
                    } catch (e: Throwable) {}
                    try { 
                        val f = satelliteClass.getDeclaredField("mElevation")
                        f.isAccessible = true
                        f.setFloat(sat, data.elevation)
                    } catch (e: Throwable) {}
                    try { 
                        val f = satelliteClass.getDeclaredField("mAzimuth")
                        f.isAccessible = true
                        f.setFloat(sat, data.azimuth)
                    } catch (e: Throwable) {}
                    list.add(sat)
                }
                cachedGpsSatellitesList = list
                lastGpsSatellitesUpdate = now
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }

        return cachedGpsSatellitesList ?: ArrayList()
    }

    private fun frameworkResultLocations(result: Any?): List<android.location.Location> {
        if (result == null) return emptyList()
        val listed = runCatching {
            @Suppress("UNCHECKED_CAST")
            (XposedHelpers.callMethod(result, "asList") as? List<*>)
                ?.filterIsInstance<android.location.Location>()
        }.getOrNull()
        if (!listed.isNullOrEmpty()) return listed

        val size = runCatching {
            (XposedHelpers.callMethod(result, "size") as? Number)?.toInt()
        }.getOrNull() ?: return emptyList()
        if (size <= 0) return emptyList()
        return ArrayList<android.location.Location>(size).also { locations ->
            for (index in 0 until size) {
                val location = runCatching {
                    XposedHelpers.callMethod(result, "get", index) as? android.location.Location
                }.getOrNull() ?: continue
                locations.add(location)
            }
        }
    }

    /**
     * Rewrite only location contents at the post-validation framework boundary. The provider's
     * original wall/elapsed timestamps stay intact, so LocationProviderManager's last-location
     * cache cannot be advanced beyond the next real sensor measurement.
     */
    private fun rewriteFrameworkLocationResultInPlace(
        result: Any?,
        config: JSONObject,
        provider: String
    ): Boolean {
        val locations = frameworkResultLocations(result)
        if (locations.isEmpty()) return false
        var rewritten = false
        for (location in locations) {
            val timestamps = SpoofedTimestamps(
                wallTimeMs = location.time,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos
            )
            if (timestamps.wallTimeMs <= 0L || timestamps.elapsedRealtimeNanos <= 0L) continue
            rememberSystemProviderTimestamp(provider, timestamps)
            if (applySpoofedLocationToObject(location, config, provider, timestamps)) {
                rewritten = true
            } else {
                return false
            }
        }
        return rewritten
    }

    private fun isSyntheticSystemProviderFallback(result: Any?, provider: String): Boolean {
        if (cadenceReportProvider.get() == provider) return true
        val expectedTimestamp =
            lastSyntheticSystemProviderElapsedRealtimeNanos[provider] ?: return false
        val matched = frameworkResultLocations(result).any {
            it.elapsedRealtimeNanos == expectedTimestamp
        }
        if (matched) {
            lastSyntheticSystemProviderElapsedRealtimeNanos.remove(provider, expectedTimestamp)
        }
        return matched
    }

    private fun hookSystemLocationProviderPipeline(classLoader: ClassLoader, pkg: String) {
        if (pkg != "android" && pkg != "system") return

        val managerClassName = "com.android.server.location.provider.LocationProviderManager"
        val managerClass = XposedHelpers.findClassIfExists(managerClassName, classLoader) ?: return

        try {
            // This is the first framework boundary for every provider report. Never let a
            // real result enter the manager's cache or listener fan-out while armed.
            XposedBridge.hookAllMethods(managerClass, "onReportLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (!shouldBlockRealLocation(config)) return
                    val manager = param.thisObject
                    val provider = manager?.let(::systemProviderName)
                    val result = param.args.getOrNull(0)
                    if (provider == null || !isSyntheticSystemProvider(provider) ||
                        !shouldDeliverLocation(config)
                    ) {
                        param.result = null // void onReportLocation: suppress the entire report
                        XposedBridge.logEvery(
                            "systemFixSuppressed:${provider ?: "unknown"}",
                            "[WeRePosition] blocked real provider report while simulation is active",
                            10_000L
                        )
                        return
                    }
                    val rewritten = result != null &&
                        rewriteFrameworkLocationResultInPlace(result, config!!, provider)
                    val deliveredResult = if (rewritten) result else {
                        val synthetic = buildCompleteRawLocation(config!!, provider)
                            ?.let { createFrameworkLocationResult(
                                manager?.javaClass?.classLoader ?: ClassLoader.getSystemClassLoader(), it
                            ) }
                        if (synthetic == null || param.args.isEmpty()) {
                            param.result = null
                            XposedBridge.logEvery(
                                "systemFixSuppressed:$provider",
                                "[WeRePosition] blocked a provider report without a safe synthetic replacement",
                                10_000L
                            )
                            return
                        }
                        param.args[0] = synthetic
                        synthetic
                    }
                    if (!isSyntheticSystemProviderFallback(deliveredResult, provider)) {
                        lastRealSystemProviderReportAtMs[provider] =
                            android.os.SystemClock.elapsedRealtime()
                    }
                    XposedBridge.logEvery(
                        "systemCanonicalFix:$provider",
                        "[WeRePosition] canonical $provider fix rewritten before framework delivery",
                        10_000L
                    )
                }
            })

            XposedBridge.hookAllMethods(managerClass, "startManager", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let(::captureSystemProviderManager)
                }
            })

            val lateCaptureHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let(::captureSystemProviderManager)
                }
            }
            for (methodName in listOf("registerLocationRequest", "getCurrentLocation")) {
                XposedBridge.hookAllMethods(managerClass, methodName, lateCaptureHook)
            }
            XposedBridge.hookAllMethods(managerClass, "getLastLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let(::captureSystemProviderManager)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (!shouldBlockRealLocation(config)) return
                    val identity = param.args.firstOrNull {
                        it?.javaClass?.name == "android.location.util.identity.CallerIdentity"
                    }
                    val request = param.args.firstOrNull {
                        it?.javaClass?.name == "android.location.LocationRequest"
                    }
                    if (!locationAllowedForRequest(config, callerPackageFromIdentity(identity), request)) {
                        param.result = null
                        return
                    }
                    val provider = param.thisObject?.let(::systemProviderName)
                    if (provider == null || !isSyntheticSystemProvider(provider) ||
                        !shouldDeliverLocation(config)
                    ) {
                        param.result = null
                        return
                    }
                    // A provider can have no cached fix when the joystick starts or
                    // after a quiet GNSS interval. Return the trusted synthetic point
                    // to one-shot readers instead of a null result.
                    val location = (param.result as? android.location.Location)
                        ?: android.location.Location(provider)
                    val timestamps = SpoofedTimestamps(
                        System.currentTimeMillis(),
                        android.os.SystemClock.elapsedRealtimeNanos()
                    )
                    param.result = if (applySpoofedLocationToObject(
                        location, config!!, provider, timestamps
                    )) location else null
                }
            })

            // Filter at delivery, not only registration, so a running listener reacts
            // immediately when the selected-app list changes. The provider cache stays
            // synthetic for every client, including Play services.
            XposedHelpers.findClassIfExists(
                "$managerClassName\$LocationRegistration", classLoader
            )?.let { registrationClass ->
                XposedBridge.hookAllMethods(registrationClass, "acceptLocationChange", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldBlockRealLocation(config) &&
                            !locationAllowedForRegistration(config, param.thisObject)
                        ) param.result = null
                    }
                })
            }
            XposedHelpers.findClassIfExists(
                "$managerClassName\$GetCurrentLocationListenerRegistration", classLoader
            )?.let { registrationClass ->
                XposedBridge.hookAllMethods(registrationClass, "acceptLocationChange", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (shouldBlockRealLocation(config) &&
                            !locationAllowedForRegistration(config, param.thisObject)
                        ) param.args[0] = null
                    }
                })
            }

            XposedBridge.log(
                "[WeRePosition] system framework fix hook installed at " +
                    "onReportLocation-before"
            )
        } catch (e: Throwable) {
            XposedBridge.log("[WeRePosition] system framework fix hook failed: $e")
        }
    }

    private fun systemProviderName(manager: Any): String? {
        return try {
            XposedHelpers.getObjectField(manager, "mName") as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun isSyntheticSystemProvider(provider: String): Boolean {
        return provider == android.location.LocationManager.GPS_PROVIDER ||
            provider == android.location.LocationManager.NETWORK_PROVIDER ||
            provider == "fused"
    }

    private fun captureSystemProviderManager(manager: Any) {
        val provider = systemProviderName(manager) ?: return
        if (!isSyntheticSystemProvider(provider)) return
        systemProviderManagers[provider] = manager
        XposedBridge.logEvery(
            "systemProviderCaptured:$provider",
            "[WeRePosition] captured real $provider provider manager for framework cadence"
        )
        // system_server installs only the provider-pipeline hook group, so it may not
        // encounter any other callback that calls readConfig() before the first client
        // asks for a location. Seed the passive config watcher at the exact point where
        // a usable real provider manager becomes available. Without this, lastConfig
        // remains null and the cadence never starts on devices with no physical fix.
        // Subsequent calls are memory-only; inactive polling still sleeps for 60 seconds
        // and lifecycle commits wake it through the ContentService observer.
        readConfig()
        installConfigContentObserver()
        ensureSystemLocationInjector()
    }

    private fun ensureSystemLocationInjector() {
        synchronized(systemInjectorLock) {
            if (!shouldSpoofLocation(lastConfig)) return
            if (systemInjectorStarted) return
            systemInjectorStarted = true
            systemInjectorStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            lateinit var tick: Runnable
            tick = Runnable {
                try {
                    injectSystemProviderFixes()
                } catch (e: Throwable) {
                    XposedBridge.logEvery(
                        "systemProviderTickFailed",
                        "[WeRePosition] system provider cadence failed: $e"
                    )
                } finally {
                    val shouldContinue = shouldDeliverLocation(lastConfig)
                    synchronized(systemInjectorLock) {
                        if (systemInjectorStarted && systemInjectorTick === tick && shouldContinue) {
                            val intervalMs = if (lastConfig?.optBoolean("joystick_mode", false) == true) {
                                500L
                            } else {
                                1_000L
                            }
                            systemLocationHandler.postDelayed(tick, intervalMs)
                        } else if (systemInjectorTick === tick) {
                            systemInjectorStarted = false
                            systemInjectorTick = null
                            systemInjectorStartedAtElapsedMs = 0L
                        }
                        Unit
                    }
                }
            }
            systemInjectorTick = tick
            systemLocationHandler.post(tick)
        }
    }

    private fun wakeSystemLocationInjector() {
        if (shouldSpoofLocation(lastConfig)) {
            ensureSystemLocationInjector()
            return
        }
        synchronized(systemInjectorLock) {
            systemInjectorTick?.let(systemLocationHandler::removeCallbacks)
            systemInjectorTick = null
            systemInjectorStarted = false
            systemInjectorStartedAtElapsedMs = 0L
        }
    }

    private fun shouldInjectSystemProviderFallback(provider: String, nowElapsedMs: Long): Boolean {
        val startedAt = systemInjectorStartedAtElapsedMs
        val joystickMode = lastConfig?.optBoolean("joystick_mode", false) == true
        val initialGraceMs = if (joystickMode) 500L else systemInjectorInitialGraceMs
        val quietWindowMs = if (joystickMode) 1_200L else systemProviderQuietWindowMs
        if (startedAt <= 0L || nowElapsedMs - startedAt < initialGraceMs) return false
        val lastRealReport = lastRealSystemProviderReportAtMs[provider] ?: return true
        return nowElapsedMs - lastRealReport >= quietWindowMs
    }

    private fun injectSystemProviderFixes() {
        val config = readConfig() ?: return
        if (!shouldDeliverLocation(config)) return

        val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
        var delivered = 0
        var upstreamFallbackDelivered = false
        for (provider in systemProviderFallbackOrder) {
            val manager = systemProviderManagers[provider] ?: continue
            if (!shouldInjectSystemProviderFallback(provider, nowElapsedMs)) continue
            // GPS/network normally feed GMS fused. Give that derived stream one tick to arrive
            // before directly falling back on fused as well, avoiding duplicate provider flows.
            if (provider == "fused" && upstreamFallbackDelivered) continue
            val rawLocation = buildCompleteRawLocation(config, provider) ?: continue
            val managerClassLoader = manager.javaClass.classLoader ?: ClassLoader.getSystemClassLoader()
            val result = createFrameworkLocationResult(managerClassLoader, rawLocation) ?: continue
            if (reportThroughRealProvider(manager, result, provider)) {
                delivered++
                if (provider != "fused") upstreamFallbackDelivered = true
            }
        }
        if (delivered > 0) {
            systemInjectedReportCount += delivered.toLong()
            XposedBridge.logEvery(
                "systemProviderCadence",
                "[WeRePosition] silent-provider fallback delivered $delivered reports (total=$systemInjectedReportCount)",
                10_000L
            )
        }
    }

    private fun buildCompleteRawLocation(
        config: JSONObject,
        provider: String
    ): android.location.Location? {
        val timestamps = nextSyntheticSystemProviderTimestamps(provider) ?: return null
        lastSyntheticSystemProviderElapsedRealtimeNanos[provider] =
            timestamps.elapsedRealtimeNanos
        val base = standardLocationCoordinates(config)
        return android.location.Location(provider).also { location ->
            location.latitude = base.first
            location.longitude = base.second
            location.accuracy = 8f
            location.time = timestamps.wallTimeMs
            location.elapsedRealtimeNanos = timestamps.elapsedRealtimeNanos
        }
    }

    private fun reportThroughRealProvider(manager: Any, result: Any, providerName: String): Boolean {
        return try {
            val mockable = XposedHelpers.getObjectField(manager, "mProvider") ?: return false
            val realProvider = try {
                XposedHelpers.getObjectField(mockable, "mRealProvider")
            } catch (_: Throwable) {
                null
            }
            val provider = realProvider ?: XposedHelpers.callMethod(mockable, "getProvider") ?: return false
            // Never route through Android's MockLocationProvider. The real provider abstraction
            // owns the listener/lock that enters LocationProviderManager normally.
            if (provider.javaClass.name.endsWith(".MockLocationProvider")) return false
            val previousProvider = cadenceReportProvider.get()
            cadenceReportProvider.set(providerName)
            try {
                XposedHelpers.callMethod(provider, "reportLocation", result)
            } finally {
                if (previousProvider == null) {
                    cadenceReportProvider.remove()
                } else {
                    cadenceReportProvider.set(previousProvider)
                }
            }
            true
        } catch (e: Throwable) {
            XposedBridge.logEvery(
                "systemProviderReportFailed:${systemProviderName(manager)}",
                "[WeRePosition] unable to report through real ${systemProviderName(manager)} provider: $e",
                10_000L
            )
            false
        }
    }

    private fun createFrameworkLocationResult(
        classLoader: ClassLoader,
        location: android.location.Location
    ): Any? {
        val resultClass = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
            ?: return null
        val locations = arrayListOf(location)
        val candidateNames = setOf("wrap", "create", "of", "from")
        for (method in resultClass.declaredMethods) {
            if (!java.lang.reflect.Modifier.isStatic(method.modifiers) || method.parameterCount != 1) continue
            if (method.name !in candidateNames) continue
            val parameter = method.parameterTypes[0]
            val argument: Any = when {
                java.util.List::class.java.isAssignableFrom(parameter) ||
                    java.util.Collection::class.java.isAssignableFrom(parameter) -> locations
                parameter.isArray &&
                    parameter.componentType?.isAssignableFrom(android.location.Location::class.java) == true ->
                    arrayOf(location)
                else -> continue
            }
            try {
                method.isAccessible = true
                return method.invoke(null, argument)
            } catch (_: Throwable) {
            }
        }
        for (constructor in resultClass.declaredConstructors) {
            if (constructor.parameterCount != 1) continue
            val parameter = constructor.parameterTypes[0]
            val argument: Any = when {
                java.util.List::class.java.isAssignableFrom(parameter) ||
                    java.util.Collection::class.java.isAssignableFrom(parameter) -> locations
                parameter.isArray &&
                    parameter.componentType?.isAssignableFrom(android.location.Location::class.java) == true ->
                    arrayOf(location)
                else -> continue
            }
            try {
                constructor.isAccessible = true
                return constructor.newInstance(argument)
            } catch (_: Throwable) {
            }
        }
        XposedBridge.logEvery(
            "frameworkLocationResultFactoryMissing",
            "[WeRePosition] no compatible android.location.LocationResult factory"
        )
        return null
    }

    private fun applySpoofedLocationToObject(
        loc: Any,
        config: JSONObject,
        provider: String,
        timestamps: SpoofedTimestamps
    ): Boolean {
        val sample = createLocationSample(
            config = config,
            provider = provider,
            timestamps = timestamps
        )

        try { XposedHelpers.callMethod(loc, "setLatitude", sample.latitude) } catch (_: Throwable) {
            try { XposedHelpers.setDoubleField(loc, "mLatitude", sample.latitude) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setLongitude", sample.longitude) } catch (_: Throwable) {
            try { XposedHelpers.setDoubleField(loc, "mLongitude", sample.longitude) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setAccuracy", sample.accuracy) } catch (_: Throwable) {
            try { XposedHelpers.setFloatField(loc, "mHorizontalAccuracyMeters", sample.accuracy) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setTime", sample.timestamps.wallTimeMs) } catch (_: Throwable) {
            try { XposedHelpers.setLongField(loc, "mTimeMs", sample.timestamps.wallTimeMs) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setElapsedRealtimeNanos", sample.timestamps.elapsedRealtimeNanos) } catch (_: Throwable) {
            try { XposedHelpers.setLongField(loc, "mElapsedRealtimeNs", sample.timestamps.elapsedRealtimeNanos) } catch (_: Throwable) {}
        }
        try { XposedHelpers.callMethod(loc, "setProvider", sample.provider) } catch (_: Throwable) {}

        if (sample.altitude > 0.0) {
            try { XposedHelpers.callMethod(loc, "setAltitude", sample.altitude) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "setVerticalAccuracyMeters", (sample.accuracy * 0.6f).coerceAtLeast(2f)) } catch (_: Throwable) {}
        } else {
            try { XposedHelpers.callMethod(loc, "removeAltitude") } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "removeVerticalAccuracy") } catch (_: Throwable) {}
        }
        // MSL values belong to the original real fix and must not survive coordinate replacement.
        try { XposedHelpers.callMethod(loc, "removeMslAltitude") } catch (_: Throwable) {}
        try { XposedHelpers.callMethod(loc, "removeMslAltitudeAccuracy") } catch (_: Throwable) {}
        if (sample.hasMotion) {
            try { XposedHelpers.callMethod(loc, "setSpeed", sample.speed) } catch (_: Throwable) {
                try { XposedHelpers.setFloatField(loc, "mSpeed", sample.speed) } catch (_: Throwable) {}
            }
            try { XposedHelpers.callMethod(loc, "setSpeedAccuracyMetersPerSecond", 0.35f) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "setBearing", sample.bearing) } catch (_: Throwable) {
                try { XposedHelpers.setFloatField(loc, "mBearing", sample.bearing) } catch (_: Throwable) {}
            }
            try { XposedHelpers.callMethod(loc, "setBearingAccuracyDegrees", 4.5f) } catch (_: Throwable) {
                try { XposedHelpers.setFloatField(loc, "mBearingAccuracyDegrees", 4.5f) } catch (_: Throwable) {}
            }
        } else {
            try { XposedHelpers.callMethod(loc, "removeSpeed") } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "removeSpeedAccuracy") } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "removeBearing") } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(loc, "removeBearingAccuracy") } catch (_: Throwable) {}
        }

        try { XposedHelpers.callMethod(loc, "setMock", false) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(loc, "mMock", false) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false) } catch (_: Throwable) {}
        try {
            val extras = XposedHelpers.callMethod(loc, "getExtras") as? android.os.Bundle
            val bundle = extras ?: android.os.Bundle()
            bundle.remove("mockLocation")
            bundle.remove("isMock")
            bundle.putInt("satellites", config.optInt("satellite_count", 20).coerceIn(0, 64))
            if (extras == null) {
                XposedHelpers.callMethod(loc, "setExtras", bundle)
            }
        } catch (_: Throwable) {}
        // Reflection failures must never be reported as a successful rewrite.
        val delivered = loc as? android.location.Location ?: return false
        return delivered.latitude.isFinite() && delivered.longitude.isFinite() &&
            kotlin.math.abs(delivered.latitude - sample.latitude) < 1e-7 &&
            kotlin.math.abs(delivered.longitude - sample.longitude) < 1e-7 &&
            delivered.time == sample.timestamps.wallTimeMs &&
            delivered.elapsedRealtimeNanos == sample.timestamps.elapsedRealtimeNanos
    }

    private fun createOnNmeaMessageListenerProxy(original: Any, classLoader: ClassLoader): Any {
        nmeaListenerProxies[original]?.let { proxy ->
            startNmeaInjector(original, "onNmeaMessage")
            return proxy
        }
        val interfaceClass = classLoader.loadClass("android.location.OnNmeaMessageListener")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            classLoader,
            arrayOf(interfaceClass),
            object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (method.name == "onNmeaMessage" && args != null && args.size >= 2) {
                        val originalMsg = args[0] as? String
                        val timestamp = (args[1] as? Number)?.toLong()
                        if (originalMsg != null && timestamp != null) {
                            val config = lastConfig
                            val deliveredMsg = if (shouldBlockRealLocation(config)) {
                                spoofNmeaMessage(originalMsg, config ?: return null)
                            } else {
                                originalMsg
                            }
                            if (deliveredMsg == null) return null // Allow dropping messages
                            val listener = original as? android.location.OnNmeaMessageListener
                            if (listener != null) {
                                // An active-created proxy can outlive the simulation. In the
                                // inactive state this is now a direct, allocation-free pass-through:
                                // no config read, sentence rewrite, argument copy, or reflection.
                                listener.onNmeaMessage(deliveredMsg, timestamp)
                                return null
                            }
                            return method.invoke(original, deliveredMsg, timestamp)
                        }
                    }
                    val methodArgs = if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                    return method.invoke(original, *methodArgs)
                }
            }
        )
        nmeaListenerProxies[original] = proxy
        startNmeaInjector(original, "onNmeaMessage")
        return proxy
    }

    @Suppress("DEPRECATION")
    private fun createGpsStatusNmeaListenerProxy(original: Any, classLoader: ClassLoader): Any {
        nmeaListenerProxies[original]?.let { proxy ->
            startNmeaInjector(original, "onNmeaReceived")
            return proxy
        }
        val interfaceClass = classLoader.loadClass("android.location.GpsStatus\$NmeaListener")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            classLoader,
            arrayOf(interfaceClass),
            object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (method.name == "onNmeaReceived" && args != null && args.size >= 2) {
                        val originalMsg = args[1] as? String
                        val timestamp = (args[0] as? Number)?.toLong()
                        if (originalMsg != null && timestamp != null) {
                            val config = lastConfig
                            val deliveredMsg = if (shouldBlockRealLocation(config)) {
                                spoofNmeaMessage(originalMsg, config ?: return null)
                            } else {
                                originalMsg
                            }
                            if (deliveredMsg == null) return null // Allow dropping messages
                            val listener = original as? android.location.GpsStatus.NmeaListener
                            if (listener != null) {
                                listener.onNmeaReceived(timestamp, deliveredMsg)
                                return null
                            }
                            return method.invoke(original, timestamp, deliveredMsg)
                        }
                    }
                    val methodArgs = if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                    return method.invoke(original, *methodArgs)
                }
            }
        )
        nmeaListenerProxies[original] = proxy
        startNmeaInjector(original, "onNmeaReceived")
        return proxy
    }

    private fun startNmeaInjector(original: Any, methodName: String) {
        if (!shouldActivelyInjectClientGnss()) return
        if (!shouldDeliverLocation(readConfig())) return
        if (nmeaTimers.containsKey(original)) return
        val timer = java.util.Timer("WeRePosition_NMEA_${System.identityHashCode(original)}", true)
        nmeaTimers[original] = timer
        XposedBridge.logEvery(
            "nmeaInjectorStarted:$currentPackageName:${original.javaClass.name}",
            "[WeRePosition] active NMEA injector started for $currentPackageName listener=${original.javaClass.name}"
        )
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                try {
                    val config = readConfig()
                    if (!shouldDeliverLocation(config)) {
                        nmeaTimers.remove(original)?.cancel()
                        return
                    }
                    if (!locationAllowedForCaller(config, currentPackageName)) return
                    val activeConfig = config ?: return
                    val now = System.currentTimeMillis()
                    for (sentence in buildSpoofedNmeaBurst(activeConfig)) {
                        if (methodName == "onNmeaReceived") {
                            XposedHelpers.tryCallMethodDeep(original, methodName, now, sentence)
                        } else {
                            XposedHelpers.tryCallMethodDeep(original, methodName, sentence, now)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }, 150L, 1000L)
    }

    private val nmeaSentenceFactory = NmeaSentenceFactory(
        ::updateSatelliteCacheIfNeeded,
        { cachedSatellites },
        { isSpoofingActiveCache },
        ::spoofedBearing,
        ::spoofedSpeed,
        ::hasSpoofedMotion
    )

    private fun spoofNmeaMessage(sentence: String, config: JSONObject): String? {
        if (!shouldDeliverLocation(config) ||
            !locationAllowedForCaller(config, currentPackageName)) return null
        return nmeaSentenceFactory.spoofNmeaMessage(sentence, config)
    }

    private fun buildSpoofedNmeaBurst(config: JSONObject): List<String> =
        nmeaSentenceFactory.buildSpoofedNmeaBurst(config)

}
