package com.shiraka.wereposition.xposed

import org.json.JSONObject

/** Stops unsupported raw GNSS channels before they can reach app listeners. */
internal class SystemGnssRawDataHook(
    private val currentConfig: () -> JSONObject?,
    private val isSimulationActive: (JSONObject?) -> Boolean
) {
    fun install(classLoader: ClassLoader) {
        val channels = listOf(
            "com.android.server.location.gnss.GnssNmeaProvider" to "onReportNmea",
            "com.android.server.location.gnss.GnssMeasurementsProvider" to "onReportMeasurements",
            "com.android.server.location.gnss.GnssNavigationMessageProvider" to "onReportNavigationMessage"
        )
        for ((className, methodName) in channels) {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader)
            if (clazz == null) {
                XposedBridge.log("[WeRePosition] raw GNSS channel unavailable: $className")
                continue
            }
            val methods = clazz.declaredMethods.filter { it.name == methodName }
            if (methods.isEmpty()) {
                XposedBridge.log("[WeRePosition] raw GNSS method unavailable: $className.$methodName")
                continue
            }
            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isSimulationActive(currentConfig())) param.result = null
                }
            })
            XposedBridge.log("[WeRePosition] raw GNSS channel guarded: $className.$methodName")
        }
    }
}
