package com.shiraka.wereposition.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shiraka.wereposition.data.db.AppDatabase
import com.shiraka.wereposition.data.repository.LocationRepository
import com.shiraka.wereposition.data.model.RoutePoint
import com.shiraka.wereposition.data.model.SimulatedLocation
import com.shiraka.wereposition.provider.SpooferProvider
import com.shiraka.wereposition.utils.EnvironmentCoveragePolicy
import com.shiraka.wereposition.utils.EnvironmentRfResolver
import com.shiraka.wereposition.utils.ConfigManager
import com.shiraka.wereposition.utils.RootManager
import com.shiraka.wereposition.utils.RuntimeConfigWriteCoordinator
import com.shiraka.wereposition.utils.SettingsManager
import com.shiraka.wereposition.utils.SyntheticRfGenerator
import com.shiraka.wereposition.utils.JoystickMotion
import com.shiraka.wereposition.utils.TrajectorySimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import org.koin.core.context.GlobalContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SpoofingService : Service() {

    private var spoofingJob: Job? = null
    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val runGeneration = AtomicLong(0L)
    @Volatile private var activeLifecycleToken = 0L
    private val lastErrorLogUptimeMs = AtomicLong(0L)
    private var lastSuccessLogUptimeMs = 0L
    private var lastConfigWriteUptimeMs = 0L
    private var lastRuntimeFrameWriteUptimeMs = 0L
    private var lastFullSnapshotGeneration = 0L
    private var lastWriteAttemptUptimeMs = 0L
    private var consecutiveWriteFailures = 0
    @Volatile private var writerSuperseded = false
    private var lastPersistedFingerprint: RuntimeContentFingerprint? = null
    private var lastPublishedPosition: RuntimePositionFingerprint? = null
    private var locationWasEnabled = true
    private val locationStateWake = Channel<Unit>(Channel.CONFLATED)
    private var locationReceiverRegistered = false
    private val locationStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            locationStateWake.trySend(Unit)
        }
    }
    private val rootManager = RootManager()
    private val environmentDao by lazy {
        AppDatabase.getDatabase(applicationContext).environmentDao()
    }
    private val rfResolver by lazy { EnvironmentRfResolver(environmentDao) }
    private val settingsManager by lazy { SettingsManager(applicationContext) }
    private lateinit var syntheticRfGenerator: SyntheticRfGenerator
    private val rfDatabaseRevision = AtomicLong(0L)
    private var rfInvalidationJob: Job? = null
    private var resolvedRf = EnvironmentRfResolver.ResolvedRfCoverage.empty()
    private var resolvedRfDatabaseRevision = -1L
    private var resolvedRfRequestRevision = -1L
    private var resolvedRfLatitude = Double.NaN
    private var resolvedRfLongitude = Double.NaN
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val currentBootId by lazy { readBootId() }
    private var joystickOverlay: FloatingJoystickOverlay? = null
    @Volatile private var joystickMoving = false
    private var joystickStartPosition: Pair<Double, Double>? = null

    private data class RuntimeWriteRequest(
        val location: SimulatedLocation,
        val rfSnapshot: SpooferProvider.Companion.RuntimeRfSnapshot,
        val logStateChange: Boolean
    )

    private data class RuntimeContentFingerprint(
        val simMode: String,
        val startTimestamp: Long,
        val routeJson: String,
        val isRouteMode: Boolean,
        val joystickMode: Boolean,
        val wifiJson: String,
        val cellJson: String,
        val mockWifi: Boolean,
        val mockCell: Boolean,
        val enableJitter: Boolean,
        val jitterRadiusMeters: Int,
        val jitterSpeed: String,
        val signalJitterEnabled: Boolean,
        val signalJitterLevel: Int,
        val wifiConnectionMode: String,
        val altitude: Double,
        val satelliteCount: Int,
        val locationAllowlistEnabled: Boolean,
        val locationAllowlist: Set<String>,
    )

    private data class RuntimePositionFingerprint(
        val latitude: Double,
        val longitude: Double,
        val bearing: Float,
        val speed: Float
    )

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_JOYSTICK = "ACTION_JOYSTICK"
        const val EXTRA_JOYSTICK_VISIBLE = "EXTRA_JOYSTICK_VISIBLE"
        const val EXTRA_LAT = "EXTRA_LAT"
        const val EXTRA_LNG = "EXTRA_LNG"
        const val EXTRA_LIFECYCLE_TOKEN = "EXTRA_LIFECYCLE_TOKEN"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "SpoofingServiceChannel"
        private const val ACTIVE_TICK_INTERVAL_MS = 1_000L
        private const val JOYSTICK_TICK_INTERVAL_MS = 250L
        private const val LOCATION_DISABLED_TICK_INTERVAL_MS = 60_000L
        private const val FIXED_CONFIG_WRITE_INTERVAL_MS = 60_000L
        private const val RUNTIME_FRAME_HEARTBEAT_INTERVAL_MS = 10_000L
        private const val SUCCESS_LOG_INTERVAL_MS = 60_000L
        private const val ERROR_LOG_INTERVAL_MS = 60_000L
        private const val STALE_WRITER_MARKER = "__WRP_STALE_WRITER__"
        // Keep retries comfortably inside the hook's 30-second liveness TTL so a
        // transient root failure does not unnecessarily drop back to real data.
        private val WRITE_RETRY_BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L, 15_000L)

        @Volatile var isRunning = false
            private set
        @Volatile var lastReadyLifecycleToken = 0L
            private set
        @Volatile var isJoystickVisible = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        locationReceiverRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(locationStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(locationStateReceiver, filter)
            }
            true
        }.getOrDefault(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val token = intent.getLongExtra(EXTRA_LIFECYCLE_TOKEN, 0L)
                if (token == 0L || token != SpooferProvider.runtimeLifecycleToken ||
                    !SpooferProvider.isActive
                ) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                startSpoofing(lat, lng, token, startId)
            }
            ACTION_STOP -> {
                val token = intent.getLongExtra(EXTRA_LIFECYCLE_TOKEN, 0L)
                if (token == SpooferProvider.runtimeLifecycleToken && !SpooferProvider.isActive) {
                    stopSpoofing(startId)
                } else if (!isRunning) {
                    stopSelfResult(startId)
                }
            }
            ACTION_JOYSTICK -> {
                if (intent.getBooleanExtra(EXTRA_JOYSTICK_VISIBLE, false) && isRunning &&
                    SpooferProvider.isActive && Settings.canDrawOverlays(this)) {
                    showFloatingJoystick()
                } else hideFloatingJoystick()
            }
            else -> if (!isRunning) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun startSpoofing(lat: Double, lng: Double, lifecycleToken: Long, startId: Int) {
        // A new fixed point or route run supersedes the floating joystick.
        hideFloatingJoystick(restoreStart = false)
        ensureServiceScope()
        val generation = runGeneration.incrementAndGet()
        activeLifecycleToken = lifecycleToken
        val previousJob = spoofingJob
        previousJob?.cancel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.shiraka.wereposition.R.string.spoofing_service_title))
            .setContentText(getString(com.shiraka.wereposition.R.string.spoofing_service_content, lat.toString(), lng.toString()))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        isRunning = true

        val newJob = serviceScope.launch {
            try {
                // A previous run can be inside an acknowledged root write when a
                // rapid START arrives. Join it before resetting any shared writer
                // state so the cancelled run cannot publish old generations later.
                previousJob?.cancelAndJoin()
                currentCoroutineContext().ensureActive()
                syntheticRfGenerator = SyntheticRfGenerator(
                    applicationContext, SpooferProvider.syntheticCycleSeed
                )
                if (!SpooferProvider.isActive ||
                    SpooferProvider.runtimeLifecycleToken != lifecycleToken ||
                    runGeneration.get() != generation
                ) return@launch

                if (lastReadyLifecycleToken != lifecycleToken) lastReadyLifecycleToken = 0L
                resolvedRf = EnvironmentRfResolver.ResolvedRfCoverage.empty()
                resolvedRfDatabaseRevision = -1L
                resolvedRfRequestRevision = -1L
                resolvedRfLatitude = Double.NaN
                resolvedRfLongitude = Double.NaN
                startRfInvalidationObserver()
                lastConfigWriteUptimeMs = 0L
                lastRuntimeFrameWriteUptimeMs = 0L
                lastFullSnapshotGeneration = 0L
                lastWriteAttemptUptimeMs = 0L
                consecutiveWriteFailures = 0
                writerSuperseded = false
                lastSuccessLogUptimeMs = 0L
                lastPersistedFingerprint = null
                lastPublishedPosition = null
                locationWasEnabled = isLocationEnabled()
                if (!locationWasEnabled) {
                    // Starting while Android's location master switch is off is valid.
                    // Keep the requested lifecycle alive but publish no active runtime
                    // frame until the switch is enabled; the broadcast wakes this loop.
                    if (!settingsManager.pureModeEnabled) lastReadyLifecycleToken = lifecycleToken
                    Log.i(
                        "SpoofingService",
                        "simulation armed with location master switch off; runtime writer paused"
                    )
                }

                var nextTickUptimeMs = SystemClock.uptimeMillis()
                while (isActive && SpooferProvider.isActive && !writerSuperseded &&
                    SpooferProvider.runtimeLifecycleToken == lifecycleToken &&
                    runGeneration.get() == generation
                ) {
                    val locationEnabled = isLocationEnabled()

                    if (locationEnabled) {
                        try {
                            val currentLoc = computeCurrentLocation()
                            val rfSnapshot = resolveRuntimeRfSnapshot(currentLoc)
                            val wallNow = System.currentTimeMillis()
                            SpooferProvider.publishCurrentPosition(
                                currentLoc.lat,
                                currentLoc.lng,
                                currentLoc.bearing,
                                currentLoc.speed
                            )
                            SpooferProvider.publishRuntimeHeartbeat(wallNow, currentBootId)

                            val fingerprint = runtimeContentFingerprint(rfSnapshot)
                            val contentChanged = fingerprint != lastPersistedFingerprint
                            val positionFingerprint = RuntimePositionFingerprint(
                                currentLoc.lat,
                                currentLoc.lng,
                                currentLoc.bearing,
                                currentLoc.speed
                            )
                            val positionChanged = positionFingerprint != lastPublishedPosition
                            val moving = SpooferProvider.isRouteMode ||
                                SpooferProvider.simMode != "STILL" ||
                                currentLoc.speed > 0.05f
                            val nowUptime = SystemClock.uptimeMillis()
                            val periodicWriteDue = nowUptime - lastConfigWriteUptimeMs >=
                                FIXED_CONFIG_WRITE_INTERVAL_MS
                            val resumedFromDisabled = !locationWasEnabled
                            val fullWriteDue = contentChanged || periodicWriteDue || resumedFromDisabled
                            val frameHeartbeatDue = nowUptime - lastRuntimeFrameWriteUptimeMs >=
                                RUNTIME_FRAME_HEARTBEAT_INTERVAL_MS
                            val overlayWriteDue = !fullWriteDue &&
                                (moving || positionChanged || frameHeartbeatDue)
                            val retryDelay = WRITE_RETRY_BACKOFF_MS[
                                (consecutiveWriteFailures - 1).coerceIn(
                                    0,
                                    WRITE_RETRY_BACKOFF_MS.lastIndex
                                )
                            ]
                            val retryAllowed = consecutiveWriteFailures == 0 ||
                                nowUptime - lastWriteAttemptUptimeMs >= retryDelay
                            if ((fullWriteDue || overlayWriteDue) && retryAllowed) {
                                lastWriteAttemptUptimeMs = nowUptime
                                val committed = if (fullWriteDue) {
                                    writeRuntimeConfig(
                                        currentLoc,
                                        rfSnapshot,
                                        contentChanged || resumedFromDisabled
                                    )
                                } else {
                                    writeRuntimePositionOverlay(currentLoc)
                                }
                                currentCoroutineContext().ensureActive()
                                if (runGeneration.get() != generation ||
                                    activeLifecycleToken != lifecycleToken ||
                                    SpooferProvider.runtimeLifecycleToken != lifecycleToken
                                ) {
                                    throw CancellationException("runtime generation superseded")
                                }
                                if (writerSuperseded) break
                                if (committed) {
                                    consecutiveWriteFailures = 0
                                    lastPublishedPosition = positionFingerprint
                                    lastRuntimeFrameWriteUptimeMs = SystemClock.uptimeMillis()
                                    if (fullWriteDue) {
                                        lastPersistedFingerprint = fingerprint
                                        lastConfigWriteUptimeMs = SystemClock.uptimeMillis()
                                        lastReadyLifecycleToken = lifecycleToken
                                        settingsManager.isSpoofingActive = true
                                        logWriteSuccessIfNeeded(contentChanged || resumedFromDisabled, currentLoc)
                                    }
                                } else {
                                    consecutiveWriteFailures++
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logRuntimeErrorThrottled(e)
                        }
                    } else if (locationWasEnabled) {
                        rootManager.closePersistentSession()
                        Log.i("SpoofingService", "location master switch off; runtime writer paused")
                    }
                    locationWasEnabled = locationEnabled

                    if (locationEnabled) {
                        nextTickUptimeMs += if (isJoystickVisible) {
                            JOYSTICK_TICK_INTERVAL_MS
                        } else {
                            ACTIVE_TICK_INTERVAL_MS
                        }
                        val remainingMs = nextTickUptimeMs - SystemClock.uptimeMillis()
                        if (remainingMs > 0L) {
                            val wokeEarly = withTimeoutOrNull(remainingMs) {
                                locationStateWake.receive()
                                true
                            } ?: false
                            if (wokeEarly) nextTickUptimeMs = SystemClock.uptimeMillis()
                        } else {
                            nextTickUptimeMs = SystemClock.uptimeMillis()
                        }
                    } else {
                        // The framework broadcasts location-master changes. While disabled,
                        // remain asleep except for a long compatibility fallback.
                        withTimeoutOrNull(LOCATION_DISABLED_TICK_INTERVAL_MS) {
                            locationStateWake.receive()
                        }
                        nextTickUptimeMs = SystemClock.uptimeMillis()
                    }
                }
            } finally {
                // Detach this run's now-idle root session before the Job reaches its
                // completed state. cancelAndJoin() is therefore a real close barrier
                // and an old completion callback cannot kill a newer run's session.
                rootManager.closePersistentSession()
                if (writerSuperseded &&
                    runGeneration.get() == generation &&
                    activeLifecycleToken == lifecycleToken
                ) {
                    // A newer process/lifecycle generation owns the shared runtime now.
                    // Retire locally without publishing another stop tombstone that could
                    // turn that newer run off.
                    activeLifecycleToken = 0L
                    SpooferProvider.isActive = false
                    if (lastReadyLifecycleToken == lifecycleToken) lastReadyLifecycleToken = 0L
                    rfInvalidationJob?.cancel()
                    rfInvalidationJob = null
                    isRunning = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                } else if (runGeneration.get() == generation &&
                    activeLifecycleToken == lifecycleToken &&
                    SpooferProvider.runtimeLifecycleToken == lifecycleToken
                ) {
                    rfInvalidationJob?.cancel()
                    rfInvalidationJob = null
                    isRunning = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
        }
        spoofingJob = newJob
    }

    private fun ensureServiceScope() {
        if (serviceJob.isCancelled || serviceJob.isCompleted) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
        }
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            locationManager.isLocationEnabled
        } catch (_: Throwable) {
            false
        }
    }

    private fun runtimeContentFingerprint(
        rfSnapshot: SpooferProvider.Companion.RuntimeRfSnapshot
    ): RuntimeContentFingerprint {
        return RuntimeContentFingerprint(
            simMode = SpooferProvider.simMode,
            startTimestamp = SpooferProvider.startTimestamp,
            routeJson = SpooferProvider.routeJson,
            isRouteMode = SpooferProvider.isRouteMode,
            joystickMode = isJoystickVisible,
            wifiJson = rfSnapshot.wifiJson,
            cellJson = rfSnapshot.cellJson,
            mockWifi = rfSnapshot.mockWifi,
            mockCell = rfSnapshot.mockCell,
            enableJitter = SpooferProvider.enableJitter && !isJoystickVisible,
            jitterRadiusMeters = SpooferProvider.jitterRadiusMeters,
            jitterSpeed = SpooferProvider.jitterSpeed,
            signalJitterEnabled = SpooferProvider.signalJitterEnabled,
            signalJitterLevel = SpooferProvider.signalJitterLevel,
            wifiConnectionMode = SpooferProvider.wifiConnectionMode,
            altitude = SpooferProvider.altitude,
            satelliteCount = SpooferProvider.satelliteCount,
            locationAllowlistEnabled = settingsManager.perAppLocationEnabled,
            locationAllowlist = settingsManager.locationAllowedApps,
        )
    }

    private fun logWriteSuccessIfNeeded(stateChanged: Boolean, loc: SimulatedLocation) {
        val now = SystemClock.uptimeMillis()
        if (stateChanged || now - lastSuccessLogUptimeMs >= SUCCESS_LOG_INTERVAL_MS) {
            lastSuccessLogUptimeMs = now
            Log.i(
                "SpoofingService",
                "runtime config committed mode=${SpooferProvider.simMode} moving=${SpooferProvider.isRouteMode || loc.speed > 0.05f}"
            )
        }
    }

    private fun logRuntimeErrorThrottled(error: Throwable) {
        val now = SystemClock.uptimeMillis()
        val previous = lastErrorLogUptimeMs.get()
        if ((previous == 0L || now - previous >= ERROR_LOG_INTERVAL_MS) &&
            lastErrorLogUptimeMs.compareAndSet(previous, now)
        ) {
            Log.e("SpoofingService", "runtime update failed; retrying", error)
        }
    }

    private fun consumeStaleWriterMarker(result: String): Boolean {
        if (!result.contains(STALE_WRITER_MARKER)) return false
        if (!writerSuperseded) {
            writerSuperseded = true
            Log.w("SpoofingService", "runtime writer superseded; retiring stale service generation")
        }
        return true
    }

    private fun startRfInvalidationObserver() {
        rfInvalidationJob?.cancel()
        rfInvalidationJob = serviceScope.launch {
            environmentDao.observeRfCoverageLocations().collect {
                rfDatabaseRevision.incrementAndGet()
            }
        }
    }

    private suspend fun resolveRuntimeRfSnapshot(
        loc: SimulatedLocation
    ): SpooferProvider.Companion.RuntimeRfSnapshot {
        if (isJoystickVisible) {
            // RF replay requires a stable coverage cell. During joystick movement the
            // user's RF switches are temporarily off, without changing their settings.
            SpooferProvider.publishRfSnapshot(
                loc.lat, loc.lng, EnvironmentRfResolver.EMPTY_WIFI_JSON, "[]",
                false, false
            )
            return SpooferProvider.rfSnapshotFor(loc.lat, loc.lng)
        }
        val targetCell = EnvironmentCoveragePolicy.cellFor(loc.lat, loc.lng)
        val databaseRevision = rfDatabaseRevision.get()
        val requestRevision = SpooferProvider.currentRfRefreshRevision()
        val movedWithinCell = if (resolvedRfLatitude.isFinite() && resolvedRfLongitude.isFinite()) {
            distanceMeters(resolvedRfLatitude, resolvedRfLongitude, loc.lat, loc.lng) > 20.0
        } else {
            true
        }
        if (resolvedRf.cell != targetCell ||
            resolvedRfDatabaseRevision != databaseRevision ||
            resolvedRfRequestRevision != requestRevision ||
            movedWithinCell
        ) {
            val localRf = rfResolver.resolve(loc.lat, loc.lng)
            val options = settingsManager.syntheticRfOptions
            resolvedRf = if (!SpooferProvider.isRouteMode && loc.speed <= 0.05f) {
                syntheticRfGenerator.fillMissing(loc.lat, loc.lng, localRf, options)
            } else {
                localRf
            }
            resolvedRfDatabaseRevision = databaseRevision
            resolvedRfRequestRevision = requestRevision
            resolvedRfLatitude = loc.lat
            resolvedRfLongitude = loc.lng
        }

        // User switches express intent. With the switch on, an unavailable
        // exact-cell payload remains an authoritative empty/blocking snapshot;
        // it must not expose the device's real RF environment outside a hex.
        val wifiJson = SpooferProvider.connectedWifiOverrideFor(loc.lat, loc.lng)
            ?.let { EnvironmentRfResolver.selectConnectedWifi(resolvedRf.wifiJson, it) }
            ?: resolvedRf.wifiJson
        SpooferProvider.publishRfSnapshot(
            loc.lat,
            loc.lng,
            wifiJson,
            resolvedRf.cellJson,
            settingsManager.mockWifi,
            settingsManager.mockCell,
            resolvedRf.syntheticWifi,
            resolvedRf.syntheticCell
        )
        return SpooferProvider.rfSnapshotFor(loc.lat, loc.lng)
    }

    private fun computeCurrentLocation(): SimulatedLocation {
        if (isJoystickVisible) {
            val position = SpooferProvider.currentPosition()
            return SimulatedLocation(
                position.latitude, position.longitude, position.speed, position.bearing,
                5f, SpooferProvider.altitude
            )
        }
        val routePoints = parseRoutePoints(SpooferProvider.routeJson)
        return if (SpooferProvider.isRouteMode && routePoints.size >= 2) {
            TrajectorySimulator.calculatePingPongRoutePosition(
                routePoints,
                SpooferProvider.startTimestamp,
                SpooferProvider.simMode,
                speedOverrideMs = SpooferProvider.simSpeed.toDouble()
                    .takeIf { SpooferProvider.simMode == "CUSTOM" && it > 0.0 }
            )
        } else {
            val anchor = SpooferProvider.anchorPosition()
            TrajectorySimulator.calculateSimulatedLocation(
                anchor.latitude,
                anchor.longitude,
                SpooferProvider.startTimestamp,
                SpooferProvider.simMode,
                SpooferProvider.simBearing,
                enableJitter = false,
                jitterRadiusMeters = SpooferProvider.jitterRadiusMeters.toDouble()
            )
        }
    }

    private fun parseRoutePoints(json: String): List<RoutePoint> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RoutePoint(obj.getDouble("lat"), obj.getDouble("lng"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showFloatingJoystick() {
        if (isJoystickVisible) return
        val current = SpooferProvider.currentPosition()
        joystickStartPosition = current.latitude to current.longitude
        joystickMoving = false
        SpooferProvider.publishAnchorPosition(current.latitude, current.longitude)
        SpooferProvider.simMode = "STILL"
        SpooferProvider.isRouteMode = false
        SpooferProvider.routeJson = "[]"
        SpooferProvider.publishCurrentPosition(current.latitude, current.longitude, current.bearing, 0f)
        val overlay = joystickOverlay ?: FloatingJoystickOverlay(
            this,
            onMove = ::moveFromFloatingJoystick,
            onRelease = ::stopFloatingJoystickMotion,
            onExitSimulation = ::exitSimulationFromOverlay
        ).also { joystickOverlay = it }
        isJoystickVisible = overlay.show()
        if (isJoystickVisible) locationStateWake.trySend(Unit)
    }

    private fun hideFloatingJoystick(restoreStart: Boolean = true) {
        joystickOverlay?.hide()
        joystickOverlay = null
        isJoystickVisible = false
        joystickMoving = false
        val start = joystickStartPosition
        joystickStartPosition = null
        if (restoreStart && start != null && SpooferProvider.isActive) {
            SpooferProvider.publishAnchorPosition(start.first, start.second)
            SpooferProvider.publishCurrentPosition(start.first, start.second, 0f, 0f)
        }
        if (SpooferProvider.isActive) SpooferProvider.requestRfRefresh()
        locationStateWake.trySend(Unit)
    }

    private fun exitSimulationFromOverlay() {
        hideFloatingJoystick(restoreStart = false)
        serviceScope.launch {
            val stopped = runCatching {
                GlobalContext.get().get<LocationRepository>().stopSpoofing(applicationContext)
            }.getOrDefault(false)
            if (settingsManager.pureModeEnabled) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@SpoofingService,
                        if (stopped) com.shiraka.wereposition.R.string.pure_mode_location_off
                        else com.shiraka.wereposition.R.string.pure_mode_stop_failed,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun moveFromFloatingJoystick(
        bearing: Double, intensity: Float, maxSpeedMs: Float, elapsedSeconds: Double
    ) {
        if (!isRunning || !SpooferProvider.isActive) return
        val anchor = SpooferProvider.anchorPosition()
        val speed = maxSpeedMs * intensity
        val (lat, lng) = JoystickMotion.advance(
            anchor.latitude, anchor.longitude, bearing,
            speed * elapsedSeconds.coerceIn(0.0, 0.25)
        )
        val oldCell = EnvironmentCoveragePolicy.cellFor(anchor.latitude, anchor.longitude)
        SpooferProvider.publishAnchorPosition(lat, lng)
        SpooferProvider.publishCurrentPosition(lat, lng, bearing.toFloat(), speed)
        if (!joystickMoving) {
            joystickMoving = true
            locationStateWake.trySend(Unit)
        }
        if (EnvironmentCoveragePolicy.cellFor(lat, lng) != oldCell) SpooferProvider.requestRfRefresh()
    }

    private fun stopFloatingJoystickMotion() {
        joystickMoving = false
        if (!SpooferProvider.isActive) return
        val position = SpooferProvider.currentPosition()
        SpooferProvider.publishCurrentPosition(position.latitude, position.longitude, position.bearing, 0f)
        locationStateWake.trySend(Unit)
    }

    private fun stopSpoofing(startId: Int) {
        hideFloatingJoystick(restoreStart = false)
        runGeneration.incrementAndGet()
        settingsManager.isSpoofingActive = false
        if (lastReadyLifecycleToken == activeLifecycleToken) lastReadyLifecycleToken = 0L
        activeLifecycleToken = 0L
        spoofingJob?.cancel()
        spoofingJob = null
        rfInvalidationJob?.cancel()
        rfInvalidationJob = null
        isRunning = false
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private suspend fun writeRuntimePositionOverlay(loc: SimulatedLocation): Boolean {
        if (!SpooferProvider.isActive) return false
        val snapshotGeneration = lastFullSnapshotGeneration
        if (snapshotGeneration <= 0L) return false
        val writeGeneration = RuntimeConfigWriteCoordinator.newGeneration()
        val heartbeatUptimeMs = SystemClock.uptimeMillis()
        val runtimeFrame = encodeRuntimeFrame(
            active = true,
            snapshotGeneration = snapshotGeneration,
            heartbeatUptimeMs = heartbeatUptimeMs,
            location = loc
        )
        val command = """
            wereposition_generation_gt() {
                left="${'$'}1"
                right="${'$'}2"
                [ "${'$'}{#left}" -gt "${'$'}{#right}" ] && return 0
                [ "${'$'}{#left}" -lt "${'$'}{#right}" ] && return 1
                [ "${'$'}left" = "${'$'}right" ] && return 1
                [ "${'$'}left" \> "${'$'}right" ]
            }
            wereposition_overlay_write() {
                flock -x 9 || return 73
                writer_status=0
                locked_snapshot_generation="${'$'}(getprop gsm.wrp.snapshot_generation)"
                locked_stop_generation="${'$'}(getprop gsm.wrp.stop_generation)"
                case "${'$'}locked_snapshot_generation" in
                    ''|*[!0-9]*) locked_snapshot_generation=0 ;;
                esac
                case "${'$'}locked_stop_generation" in
                    ''|*[!0-9]*) locked_stop_generation=0 ;;
                esac
                if [ "${'$'}locked_stop_generation" = "$snapshotGeneration" ] ||
                    wereposition_generation_gt "${'$'}locked_stop_generation" "$snapshotGeneration" ||
                    [ "${'$'}locked_snapshot_generation" != "$snapshotGeneration" ]; then
                    printf '%s\n' "$STALE_WRITER_MARKER" || writer_status=${'$'}?
                else
                    setprop gsm.wrp.frame "$runtimeFrame" || writer_status=${'$'}?
                fi
                flock -u 9 || writer_status=${'$'}?
                test "${'$'}writer_status" -eq 0
            }
            wereposition_overlay_write 9>/data/local/tmp/wereposition_runtime.lock
        """.trimIndent()
        return RuntimeConfigWriteCoordinator.commitIfLatest(writeGeneration) {
            if (!SpooferProvider.isActive) return@commitIfLatest false
            val result = rootManager.executePersistentCommand(command)
            if (consumeStaleWriterMarker(result)) return@commitIfLatest false
            if (result == "ERROR") {
                logRuntimeErrorThrottled(IllegalStateException("root position overlay failed"))
            }
            result != "ERROR"
        }
    }

    private suspend fun writeRuntimeConfig(
        loc: SimulatedLocation,
        rfSnapshot: SpooferProvider.Companion.RuntimeRfSnapshot,
        contentChanged: Boolean
    ): Boolean {
        if (!SpooferProvider.isActive) return false
        val writeGeneration = RuntimeConfigWriteCoordinator.newGeneration()
        val now = System.currentTimeMillis()
        val heartbeatUptimeMs = SystemClock.uptimeMillis()
        val bootId = currentBootId
        val runtimeFrame = encodeRuntimeFrame(
            active = true,
            snapshotGeneration = writeGeneration,
            heartbeatUptimeMs = heartbeatUptimeMs,
            location = loc
        )
        
        // The service is the sole active runtime writer. MainViewModel may move
        // the manual anchor, but only this heartbeat publishes output files.
        val outputAltitude = SpooferProvider.altitude
        
        val config = JSONObject().apply {
            put("lat", loc.lat)
            put("lng", loc.lng)
            put("active", true)
            put("config_updated_at", now)
            put("config_generation", writeGeneration)
            put("runtime_lifecycle_token", activeLifecycleToken)
            put("heartbeat_at", now)
            put("heartbeat_uptime_ms", heartbeatUptimeMs)
            put("boot_id", bootId)
            put("fail_closed", false)
            put("location_allowlist_enabled", settingsManager.perAppLocationEnabled)
            put("location_allowlist", JSONArray(settingsManager.locationAllowedApps.sorted()))
            put("sim_mode", SpooferProvider.simMode)
            put("sim_bearing", loc.bearing.toDouble())
            put("sim_speed", loc.speed.toDouble())
            put("start_timestamp", SpooferProvider.startTimestamp)
            put("route_points", safeJsonArray(SpooferProvider.routeJson))
            put("is_route_mode", SpooferProvider.isRouteMode)
            put("joystick_mode", isJoystickVisible)
            put("wifi_json", safeWifiJson(rfSnapshot.wifiJson))
            put("cell_json", safeJsonArray(rfSnapshot.cellJson))
            put("bluetooth_json", JSONArray())
            put("mock_wifi", rfSnapshot.mockWifi)
            put("mock_cell", rfSnapshot.mockCell)
            put("mock_bluetooth", false)
            put("enable_jitter", SpooferProvider.enableJitter && !isJoystickVisible)
            put("jitter_radius_meters", SpooferProvider.jitterRadiusMeters.coerceIn(1, 80))
            put("jitter_speed", SpooferProvider.jitterSpeed)
            put("signal_jitter_enabled", SpooferProvider.signalJitterEnabled)
            put("signal_jitter_level", SpooferProvider.signalJitterLevel.coerceIn(0, 100))
            put("wifi_connection_mode", SpooferProvider.wifiConnectionMode)
            put("altitude", SpooferProvider.altitude)
            put("satellite_count", SpooferProvider.satelliteCount)
        }
        val jsonText = config.toString()
        val command = """
            wereposition_generation_gt() {
                left="${'$'}1"
                right="${'$'}2"
                [ "${'$'}{#left}" -gt "${'$'}{#right}" ] && return 0
                [ "${'$'}{#left}" -lt "${'$'}{#right}" ] && return 1
                [ "${'$'}left" = "${'$'}right" ] && return 1
                [ "${'$'}left" \> "${'$'}right" ]
            }
            wereposition_full_write() {
            flock -x 9 || return 73
            set -e

            locked_snapshot_generation="${'$'}(getprop gsm.wrp.snapshot_generation)"
            locked_stop_generation="${'$'}(getprop gsm.wrp.stop_generation)"
            case "${'$'}locked_snapshot_generation" in
                ''|*[!0-9]*) locked_snapshot_generation=0 ;;
            esac
            case "${'$'}locked_stop_generation" in
                ''|*[!0-9]*) locked_stop_generation=0 ;;
            esac
            if [ "${'$'}locked_stop_generation" = "$writeGeneration" ] ||
                wereposition_generation_gt "${'$'}locked_stop_generation" "$writeGeneration" ||
                wereposition_generation_gt "${'$'}locked_snapshot_generation" "$writeGeneration"; then
                printf '%s\n' "$STALE_WRITER_MARKER"
                flock -u 9
                return 0
            fi

            cat > /data/local/tmp/wereposition_config.json.tmp.$writeGeneration <<'WEREPOSITION_JSON'
            $jsonText
            WEREPOSITION_JSON
            mv /data/local/tmp/wereposition_config.json.tmp.$writeGeneration /data/local/tmp/wereposition_config.json
            chmod 666 /data/local/tmp/wereposition_config.json
            chcon u:object_r:shell_data_file:s0 /data/local/tmp/wereposition_config.json 2>/dev/null || true

            cat > /data/system/wereposition_config.json.tmp.$writeGeneration <<'WEREPOSITION_JSON_SYSTEM'
            $jsonText
            WEREPOSITION_JSON_SYSTEM
            mv /data/system/wereposition_config.json.tmp.$writeGeneration /data/system/wereposition_config.json
            chown system:system /data/system/wereposition_config.json 2>/dev/null || true
            chmod 644 /data/system/wereposition_config.json
            chcon u:object_r:system_data_file:s0 /data/system/wereposition_config.json 2>/dev/null || true

            setprop gsm.wrp.active true
            setprop gsm.wrp.location_allowlist_enabled ${settingsManager.perAppLocationEnabled}
            setprop gsm.wrp.lat ${loc.lat}
            setprop gsm.wrp.lng ${loc.lng}
            setprop gsm.wrp.bearing ${loc.bearing}
            setprop gsm.wrp.speed ${loc.speed}
            setprop gsm.wrp.snapshot_generation $writeGeneration
            setprop gsm.wrp.boot_id "$bootId"
            setprop gsm.wrp.heartbeat_uptime $heartbeatUptimeMs
            setprop gsm.wrp.heartbeat $now
            setprop gsm.wrp.frame "$runtimeFrame"

            flock -u 9
            }
            wereposition_full_write 9>/data/local/tmp/wereposition_runtime.lock
        """.trimIndent()
        val committed = RuntimeConfigWriteCoordinator.commitIfLatest(writeGeneration) {
            if (!SpooferProvider.isActive) return@commitIfLatest false
            // Full snapshots finish non-cancellably after admission. The fd9 guard
            // rejects this writer if a newer snapshot or stop fence won while it waited.
            val result = withContext(NonCancellable) {
                rootManager.executeCommand(command)
            }
            if (consumeStaleWriterMarker(result)) return@commitIfLatest false
            if (result == "ERROR") {
                logRuntimeErrorThrottled(IllegalStateException("root runtime config write failed"))
                return@commitIfLatest false
            }
            // Preferences are only a host/UI cache. Update them after the authoritative
            // root commit so a rejected old writer cannot flip the visible lifecycle state.
            try {
                val editor = getSharedPreferences("wereposition_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("active", true)
                    .putLong("heartbeat", now)
                    .putString("boot_id", bootId)
                    .putFloat("lat", loc.lat.toFloat())
                    .putFloat("lng", loc.lng.toFloat())
                    .putFloat("bearing", loc.bearing)
                    .putFloat("speed", loc.speed)
                if (contentChanged) {
                    editor
                        .putFloat("alt", outputAltitude.toFloat())
                        .putInt("sat_count", SpooferProvider.satelliteCount)
                        .putBoolean("mock_wifi", rfSnapshot.mockWifi)
                        .putBoolean("mock_cell", rfSnapshot.mockCell)
                        .putBoolean("mock_bluetooth", false)
                        .putBoolean("enable_jitter", SpooferProvider.enableJitter)
                        .putInt("jitter_radius_meters", SpooferProvider.jitterRadiusMeters.coerceIn(1, 80))
                        .putString("jitter_speed", SpooferProvider.jitterSpeed)
                        .putBoolean("signal_jitter_enabled", SpooferProvider.signalJitterEnabled)
                        .putInt("signal_jitter_level", SpooferProvider.signalJitterLevel.coerceIn(0, 100))
                        .putString("wifi_connection_mode", SpooferProvider.wifiConnectionMode)
                }
                editor.apply()
            } catch (e: Throwable) {
                logRuntimeErrorThrottled(e)
            }
            true
        }
        currentCoroutineContext().ensureActive()
        if (committed) {
            lastFullSnapshotGeneration = writeGeneration
            SpooferProvider.publishRuntimeConfigGeneration(writeGeneration)
            // Wake scoped processes only for a committed full snapshot. Position-only
            // 1 Hz overlays remain property-backed and do not fan out ContentService work.
            SpooferProvider.notifyRuntimeConfigChanged(this)
        }
        return committed
    }

    private fun encodeRuntimeFrame(
        active: Boolean,
        snapshotGeneration: Long,
        heartbeatUptimeMs: Long,
        location: SimulatedLocation
    ): String {
        val latitudeE7 = (location.lat.coerceIn(-90.0, 90.0) * 10_000_000.0)
            .toInt()
        val longitudeE7 = (location.lng.coerceIn(-180.0, 180.0) * 10_000_000.0)
            .toInt()
        val normalizedBearing = ((location.bearing % 360f) + 360f) % 360f
        val bearingCentiDegrees = (normalizedBearing * 100f).toInt()
        val speedCentimetersPerSecond = (location.speed.coerceIn(0f, 1_000f) * 100f)
            .toInt()
        return listOf(
            "1",
            if (active) "1" else "0",
            snapshotGeneration.coerceAtLeast(0L).toString(),
            heartbeatUptimeMs.coerceAtLeast(0L).toString(),
            latitudeE7.toString(),
            longitudeE7.toString(),
            bearingCentiDegrees.toString(),
            speedCentimetersPerSecond.toString()
        ).joinToString("|")
    }

    private fun distanceMeters(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): Double {
        val radius = 6_378_137.0
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLat = Math.toRadians(toLat - fromLat)
        val dLng = Math.toRadians(toLng - fromLng)
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        return 2 * radius * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun readBootId(): String {
        return try {
            java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Throwable) {
            ""
        }
    }

    private fun safeJsonArray(text: String): JSONArray {
        return try {
            JSONArray(text)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun safeJsonObject(text: String): JSONObject {
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun safeWifiJson(text: String): Any {
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject().apply {
                put("isConnected", false)
                put("connectedWifi", JSONObject.NULL)
                put("nearbyWifi", JSONArray())
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.shiraka.wereposition.R.string.spoofing_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideFloatingJoystick()
        runGeneration.incrementAndGet()
        val destroyedToken = activeLifecycleToken
        var persistUnexpectedStop = false
        if (destroyedToken != 0L &&
            SpooferProvider.runtimeLifecycleToken == destroyedToken
        ) {
            SpooferProvider.isActive = false
            SpooferProvider.publishRuntimeConfigGeneration(0L)
            SpooferProvider.publishRuntimeHeartbeat(0L, "")
            settingsManager.isSpoofingActive = false
            if (lastReadyLifecycleToken == destroyedToken) lastReadyLifecycleToken = 0L
            persistUnexpectedStop = true
        }
        activeLifecycleToken = 0L
        spoofingJob?.cancel()
        spoofingJob = null
        rfInvalidationJob?.cancel()
        rfInvalidationJob = null
        isRunning = false
        if (locationReceiverRegistered) {
            runCatching { unregisterReceiver(locationStateReceiver) }
            locationReceiverRegistered = false
        }
        locationStateWake.close()
        serviceJob.cancel()
        if (persistUnexpectedStop) {
            // The cancelled active writer finishes inside the global coordinator;
            // this tombstone is therefore ordered after any admitted full snapshot.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                ConfigManager(RootManager()).saveConfig(0.0, 0.0, false)
            }
        }
        super.onDestroy()
    }
}
