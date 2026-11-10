package com.shiraka.wereposition.xposed

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Replaces GNSS status at the system_server listener fan-out, before Binder delivery. */
internal class SystemGnssStatusHook(
    private val currentConfig: () -> JSONObject?,
    private val isSimulationActive: (JSONObject?) -> Boolean,
    private val shouldDeliver: (JSONObject?) -> Boolean,
    private val locationAllowed: (JSONObject?, String?) -> Boolean,
    private val currentSatellites: () -> Array<LocationHooker.SatelliteData>
) {
    // Construct only when a listener appears; system_server may install hooks before
    // its main looper is ready.
    private val handler by lazy {
        val looper = Looper.getMainLooper() ?: HandlerThread("WRP-GnssStatus").also { it.start() }.looper
        Handler(looper)
    }
    private val injecting = ThreadLocal.withInitial { false }
    @Volatile private var provider: Any? = null
    @Volatile private var lastRealReportAtMs = 0L
    private val tickScheduled = AtomicBoolean(false)

    private val tick = object : Runnable {
        override fun run() {
            tickScheduled.set(false)
            val target = provider
            val active = isSimulationActive(currentConfig())
            if (active && target != null &&
                SystemClock.elapsedRealtime() - lastRealReportAtMs >= 1_500L
            ) {
                try {
                    injecting.set(true)
                    // The hooked onReportSvStatus substitutes its argument with a fresh sky.
                    XposedHelpers.callMethod(target, "onReportSvStatus", emptyStatus(target.javaClass.classLoader))
                } catch (error: Throwable) {
                    XposedBridge.logEvery(
                        "systemGnssDelivery:${error.javaClass.simpleName}",
                        "[WeRePosition] system GNSS delivery failed: ${error.javaClass.simpleName}",
                        60_000L
                    )
                } finally {
                    injecting.set(false)
                }
            }
            schedule(if (active) 1_000L else 3_000L)
        }
    }

    fun install(classLoader: ClassLoader) {
        // GNSS listener operations share this final method. Check each registration
        // at delivery so changing the allowlist affects listeners already registered.
        val gnssRegistrationClass = XposedHelpers.findClassIfExists(
            "com.android.server.location.gnss.GnssListenerMultiplexer\$GnssListenerRegistration",
            classLoader
        )
        XposedHelpers.findClassIfExists(
            "com.android.server.location.listeners.ListenerRegistration", classLoader
        )?.let { registrationClass ->
            XposedBridge.hookAllMethods(registrationClass, "executeOperation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val registration = param.thisObject ?: return
                    if (gnssRegistrationClass?.isInstance(registration) != true) return
                    val config = currentConfig()
                    if (!isSimulationActive(config)) return
                    val identity = runCatching {
                        XposedHelpers.callMethod(registration, "getIdentity")
                    }.getOrNull()
                    val packageName = identity?.let { runCatching {
                        XposedHelpers.callMethod(it, "getPackageName") as? String
                    }.getOrNull() }
                    if (!locationAllowed(config, packageName)) param.result = null
                }
            })
        }
        val providerClass = XposedHelpers.findClassIfExists(
            "com.android.server.location.gnss.GnssStatusProvider", classLoader
        ) ?: run {
            XposedBridge.log("[WeRePosition] system GNSS provider class unavailable")
            return
        }
        XposedBridge.hookAllMethods(providerClass, "addListener", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { capture(it) }
            }
        })
        XposedBridge.hookAllMethods(providerClass, "onReportSvStatus", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { capture(it) }
                val config = currentConfig()
                if (!isSimulationActive(config)) return
                if (injecting.get() != true) lastRealReportAtMs = SystemClock.elapsedRealtime()
                val satellites = if (shouldDeliver(config)) currentSatellites() else emptyArray()
                val loader = param.thisObject?.javaClass?.classLoader ?: classLoader
                val replacement = GnssStatusFactory.build(loader, satellites, satellites.size, "android")
                    ?: emptyStatus(loader)
                if (replacement == null) {
                    param.result = null // Never pass through the real sky on synthesis failure.
                    return
                }
                if (param.args.isEmpty()) {
                    param.result = null
                    return
                }
                param.args[0] = replacement
                XposedBridge.logEvery(
                    "systemGnssRewritten",
                    "[WeRePosition] system GNSS status delivered with ${satellites.size} satellites",
                    10_000L
                )
            }
        })
        XposedBridge.log("[WeRePosition] system GNSS status hook installed")
    }

    private fun capture(instance: Any) {
        // A synthetic tick calls onReportSvStatus() and re-enters this hook.
        // Scheduling from every report would turn the 1 Hz timer into a tight loop.
        if (provider !== instance) {
            provider = instance
            schedule(0L)
        }
    }

    private fun schedule(delayMs: Long) {
        if (!tickScheduled.compareAndSet(false, true)) return
        handler.postDelayed(tick, delayMs)
    }

    private fun emptyStatus(classLoader: ClassLoader?): Any? {
        val loader = classLoader ?: ClassLoader.getSystemClassLoader()
        return GnssStatusFactory.build(loader, emptyArray(), 0, "android")
    }
}
