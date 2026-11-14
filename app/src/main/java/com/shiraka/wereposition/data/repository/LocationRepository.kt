package com.shiraka.wereposition.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import com.shiraka.wereposition.data.model.RoutePoint
import com.shiraka.wereposition.data.model.ActiveRouteSession
import com.shiraka.wereposition.data.model.SimMode
import com.shiraka.wereposition.provider.SpooferProvider
import com.shiraka.wereposition.service.SpoofingService
import com.shiraka.wereposition.utils.ConfigManager
import com.shiraka.wereposition.utils.EnvironmentRfResolver
import com.shiraka.wereposition.utils.LSPosedManager
import com.shiraka.wereposition.utils.RootManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class LocationRepository(
    private val configManager: ConfigManager,
    private val rootManager: RootManager,
    private val lsposedManager: LSPosedManager,
    private val settingsManager: com.shiraka.wereposition.utils.SettingsManager,
    private val savedRouteDao: com.shiraka.wereposition.data.db.SavedRouteDao
) {
    private val lifecycleGeneration = AtomicLong(0L)
    private val lifecycleMutex = Mutex()

    suspend fun checkRootAccess(): Boolean = rootManager.checkRootAccess()

    fun isModuleActive(): Boolean = lsposedManager.isModuleActive()

    fun runningRoute(): ActiveRouteSession? {
        val token = SpooferProvider.runtimeLifecycleToken
        if (!SpoofingService.isRunning || !SpooferProvider.isActive || !SpooferProvider.isRouteMode) return null
        val points = ActiveRouteSession.parsePoints(SpooferProvider.routeJson)
        val mode = runCatching { SimMode.valueOf(SpooferProvider.simMode) }.getOrNull() ?: return null
        val speed = if (mode == SimMode.CUSTOM) SpooferProvider.simSpeed else mode.speedMs.toFloat()
        if (points.size < 2 || !speed.isFinite() || speed !in 0.1f..100f ||
            token != SpooferProvider.runtimeLifecycleToken || !SpooferProvider.isActive) return null
        return ActiveRouteSession(points, mode, speed, SpooferProvider.startTimestamp)
    }

    suspend fun reconnectActiveRoute(context: Context): ActiveRouteSession? {
        runningRoute()?.let { return it }
        if (SpoofingService.isRunning || SpooferProvider.isActive || !settingsManager.isSpoofingActive) return null
        val expectedGeneration = lifecycleGeneration.get()
        // Read the authoritative snapshot and stop fence together; preferences alone
        // cannot distinguish a killed host from an explicitly stopped/old-boot run.
        val output = rootManager.executeCommand("""
            read_route_session() {
                flock -x 9 || return 73
                cat /proc/sys/kernel/random/boot_id
                printf '%s\n' "${'$'}(getprop gsm.wrp.stop_generation)"
                cat /data/local/tmp/wereposition_config.json
            }
            read_route_session 9>/data/local/tmp/wereposition_runtime.lock
        """.trimIndent()).split('\n', limit = 3)
        if (output.size != 3) return null
        val stop = output[1].trim().let { if (it.isEmpty()) 0L else it.toLongOrNull() } ?: return null
        val session = ActiveRouteSession.fromSnapshot(output[2], output[0].trim(), stop) ?: return null
        startSpoofing(context, session.points.first().lat, session.points.first().lng,
            session.mode.name, 0f, session.startedAt, session.points, true,
            mockWifi = settingsManager.mockWifi, mockCell = settingsManager.mockCell,
            enableJitter = settingsManager.enableJitter,
            jitterRadiusMeters = settingsManager.jitterRadiusMeters, jitterSpeed = settingsManager.jitterSpeed,
            signalJitterEnabled = settingsManager.signalJitterEnabled, signalJitterLevel = settingsManager.signalJitterLevel,
            wifiConnectionMode = settingsManager.wifiConnectionMode,
            speedOverrideMs = session.speedMs, expectedLifecycleGeneration = expectedGeneration)
        return runningRoute()
    }

    suspend fun startSpoofing(
        context: Context,
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean,
        wifiJson: String = EnvironmentRfResolver.EMPTY_WIFI_JSON,
        cellJson: String = "[]",
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        enableJitter: Boolean = true,
        jitterRadiusMeters: Int = SpooferProvider.jitterRadiusMeters,
        jitterSpeed: String = SpooferProvider.jitterSpeed,
        signalJitterEnabled: Boolean = SpooferProvider.signalJitterEnabled,
        signalJitterLevel: Int = SpooferProvider.signalJitterLevel,
        wifiConnectionMode: String = SpooferProvider.wifiConnectionMode,
        syntheticCycleSeed: Long = 0L,
        syntheticWifi: Boolean = false,
        syntheticCell: Boolean = false,
        speedOverrideMs: Float = 0f,
        expectedLifecycleGeneration: Long? = null
    ) {
        if (settingsManager.moduleRestartRequired) {
            throw IllegalStateException("模組已更新，請在方便解鎖時重啟設備後再啟用模擬")
        }
        val pureMode = settingsManager.pureModeEnabled
        val alt = settingsManager.altitude.toDoubleOrNull() ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        val generation = lifecycleMutex.withLock {
            if (expectedLifecycleGeneration != null && lifecycleGeneration.get() != expectedLifecycleGeneration) {
                throw kotlinx.coroutines.CancellationException("Route recovery superseded by a lifecycle action")
            }
            if (pureMode) {
                // Keep recovery and explicit stop ordered, including the location switch.
                if (!rootManager.setPureArmed(true) || !rootManager.setLocationMasterEnabled(false)) {
                    throw IllegalStateException("Pure 模式無法安全準備系統定位")
                }
            }
            val nextGeneration = lifecycleGeneration.incrementAndGet()
            SpooferProvider.runtimeLifecycleToken = nextGeneration
            SpooferProvider.syntheticCycleSeed = syntheticCycleSeed
            SpooferProvider.publishRuntimeConfigGeneration(0L)
            SpooferProvider.clearRfForCoverageTransition()
            SpooferProvider.isActive = true
            SpooferProvider.publishAnchorPosition(lat, lng)
            SpooferProvider.publishCurrentPosition(lat, lng)
            SpooferProvider.publishRuntimeHeartbeat(startTime)
            SpooferProvider.startTimestamp = startTime
            SpooferProvider.simMode = simMode
            SpooferProvider.simSpeed = speedOverrideMs
            SpooferProvider.simBearing = simBearing
            SpooferProvider.routeJson = routePointsToJson(routePoints)
            SpooferProvider.isRouteMode = isRouteMode
            SpooferProvider.enableJitter = enableJitter
            SpooferProvider.jitterRadiusMeters = jitterRadiusMeters.coerceIn(1, 80)
            SpooferProvider.jitterSpeed = jitterSpeed
            SpooferProvider.signalJitterEnabled = signalJitterEnabled
            SpooferProvider.signalJitterLevel = signalJitterLevel.coerceIn(0, 100)
            SpooferProvider.wifiConnectionMode = if (wifiConnectionMode == "RANDOM") "RANDOM" else "FIXED"
            SpooferProvider.publishRfSnapshot(
                lat,
                lng,
                wifiJson,
                cellJson,
                mockWifi,
                mockCell,
                syntheticWifi,
                syntheticCell
            )
            SpooferProvider.altitude = alt
            SpooferProvider.satelliteCount = satCount
            nextGeneration
        }
        try {
            context.startForegroundService(
                Intent(context, SpoofingService::class.java).apply {
                    action = SpoofingService.ACTION_START
                    putExtra(SpoofingService.EXTRA_LAT, lat)
                    putExtra(SpoofingService.EXTRA_LNG, lng)
                    putExtra(SpoofingService.EXTRA_LIFECYCLE_TOKEN, generation)
                }
            )
        } catch (error: Throwable) {
            rollbackFailedStart(context, generation)
            throw error
        }

        // SpoofingService is the sole active writer. Waiting for its first committed
        // full snapshot avoids two active writers racing through the generation gate.
        // Pure mode must keep Android location off until this synthetic snapshot is
        // ready; otherwise an empty activation window can expose an older app cache.
        val ready = withTimeoutOrNull(18_000L) {
            while (lifecycleGeneration.get() == generation && SpooferProvider.isActive) {
                if (SpoofingService.lastReadyLifecycleToken == generation) {
                    return@withTimeoutOrNull true
                }
                delay(50L)
            }
            false
        } ?: false
        if (!ready && lifecycleGeneration.get() == generation) {
            rollbackFailedStart(context, generation)
            throw IllegalStateException("Unable to commit active runtime configuration")
        }
        if (pureMode && !rootManager.setLocationMasterEnabled(true)) {
            rollbackFailedStart(context, generation)
            throw IllegalStateException("Pure 模式無法開啟系統定位")
        }
        if (lifecycleGeneration.get() == generation) settingsManager.isSpoofingActive = true
    }

    suspend fun stopSpoofing(context: Context): Boolean {
        val pureSession = settingsManager.pureModeEnabled || rootManager.isPureArmed()
        // Shut the master switch before publishing an inactive runtime snapshot.
        val locationDisabled = !pureSession || rootManager.setLocationMasterEnabled(false)
        val generation = lifecycleMutex.withLock {
            val nextGeneration = lifecycleGeneration.incrementAndGet()
            SpooferProvider.runtimeLifecycleToken = nextGeneration
            SpooferProvider.publishRuntimeConfigGeneration(0L)
            SpooferProvider.isActive = false
            SpooferProvider.syntheticCycleSeed = 0L
            SpooferProvider.publishCurrentPosition(0.0, 0.0)
            SpooferProvider.publishRuntimeHeartbeat(0L, "")
            SpooferProvider.clearRfForCoverageTransition()
            SpooferProvider.routeJson = "[]"
            SpooferProvider.isRouteMode = false
            settingsManager.isSpoofingActive = false
            nextGeneration
        }
        sendStopCommand(context, generation)
        val tombstoneSaved = withContext(NonCancellable) {
            lifecycleMutex.withLock {
                if (lifecycleGeneration.get() == generation) {
                    if (!persistInactiveConfig()) {
                        Log.e("LocationRepository", "inactive runtime tombstone failed after retry")
                        false
                    } else {
                        true
                    }
                } else {
                    false
                }
            }
        }
        val disarmed = if (pureSession && locationDisabled && tombstoneSaved) {
            rootManager.setPureArmed(false)
        } else !pureSession
        return locationDisabled && tombstoneSaved && disarmed
    }

    suspend fun cleanupRuntimeEnvironment(context: Context): Boolean {
        val pureSession = settingsManager.pureModeEnabled || rootManager.isPureArmed()
        val locationDisabled = !pureSession || rootManager.setLocationMasterEnabled(false)
        val generation = lifecycleMutex.withLock {
            val nextGeneration = lifecycleGeneration.incrementAndGet()
            SpooferProvider.runtimeLifecycleToken = nextGeneration
            SpooferProvider.publishRuntimeConfigGeneration(0L)
            SpooferProvider.isActive = false
            SpooferProvider.publishAnchorPosition(0.0, 0.0)
            SpooferProvider.publishCurrentPosition(0.0, 0.0)
            SpooferProvider.publishRuntimeHeartbeat(0L, "")
            SpooferProvider.clearRfForCoverageTransition()
            SpooferProvider.routeJson = "[]"
            SpooferProvider.isRouteMode = false
            settingsManager.isSpoofingActive = false
            nextGeneration
        }
        sendStopCommand(context, generation)
        return withContext(NonCancellable) {
            lifecycleMutex.withLock {
                if (lifecycleGeneration.get() != generation) return@withLock false
                val tombstoneSaved = persistInactiveConfig()
                val cleaned = rootManager.cleanupRuntimeEnvironment(restoreLocationEnabled = !pureSession)
                val disarmed = if (pureSession && locationDisabled && tombstoneSaved && cleaned) {
                    rootManager.setPureArmed(false)
                } else !pureSession
                cleaned && tombstoneSaved && locationDisabled && disarmed
            }
        }
    }

    private fun sendStopCommand(context: Context, lifecycleToken: Long) {
        runCatching {
            context.startService(Intent(context, SpoofingService::class.java).apply {
                action = SpoofingService.ACTION_STOP
                putExtra(SpoofingService.EXTRA_LIFECYCLE_TOKEN, lifecycleToken)
            })
        }.onFailure {
            context.stopService(Intent(context, SpoofingService::class.java))
        }
    }

    /**
     * Rolls back a start only while it still owns the published lifecycle token.
     * The STOP is sent before the root tombstone so an existing foreground-service
     * instance cannot be left behind if startForegroundService() itself failed.
     */
    private suspend fun rollbackFailedStart(context: Context, generation: Long) {
        withContext(NonCancellable) {
            val pureSession = settingsManager.pureModeEnabled || rootManager.isPureArmed()
            val locationDisabled = !pureSession || rootManager.setLocationMasterEnabled(false)
            val ownsGeneration = lifecycleMutex.withLock {
                if (lifecycleGeneration.get() != generation ||
                    SpooferProvider.runtimeLifecycleToken != generation
                ) {
                    false
                } else {
                    SpooferProvider.isActive = false
                    SpooferProvider.publishRuntimeConfigGeneration(0L)
                    settingsManager.isSpoofingActive = false
                    true
                }
            }
            if (!ownsGeneration) return@withContext

            sendStopCommand(context, generation)
            lifecycleMutex.withLock {
                if (lifecycleGeneration.get() == generation &&
                    SpooferProvider.runtimeLifecycleToken == generation &&
                    !SpooferProvider.isActive
                ) {
                    val tombstoneSaved = persistInactiveConfig()
                    if (!tombstoneSaved) {
                        Log.e("LocationRepository", "start rollback tombstone failed after retry")
                    }
                    if (pureSession && locationDisabled && tombstoneSaved) {
                        rootManager.setPureArmed(false)
                    }
                }
            }
        }
    }

    private suspend fun persistInactiveConfig(): Boolean {
        repeat(2) { attempt ->
            if (configManager.saveConfig(0.0, 0.0, false)) return true
            if (attempt == 0) delay(250L)
        }
        return false
    }

    private fun routePointsToJson(points: List<RoutePoint>): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
            })
        }
        return arr.toString()
    }

    fun getSavedRoutes(): kotlinx.coroutines.flow.Flow<List<com.shiraka.wereposition.data.db.SavedRouteEntity>> {
        return savedRouteDao.getAllSavedRoutes()
    }

    suspend fun insertSavedRoute(name: String, points: List<RoutePoint>) {
        val pointsJson = routePointsToJson(points)
        savedRouteDao.insertSavedRoute(
            com.shiraka.wereposition.data.db.SavedRouteEntity(
                name = name,
                pointsJson = pointsJson
            )
        )
    }

    suspend fun deleteSavedRoute(route: com.shiraka.wereposition.data.db.SavedRouteEntity) {
        savedRouteDao.deleteSavedRoute(route)
    }
}
