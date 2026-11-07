package com.shiraka.wereposition.xposed

/** Builds a structurally valid status for active GNSS callback delivery. */
internal object GnssStatusFactory {
    fun build(
        classLoader: ClassLoader,
        satellites: Array<LocationHooker.SatelliteData>,
        count: Int,
        packageName: String
    ): Any? {
        val statusClass = XposedHelpers.findClassIfExists("android.location.GnssStatus", classLoader)
            ?: return null
        val n = minOf(count, satellites.size)

        // Android 14–16 expose the twelve-argument Builder.addSatellite method.
        try {
            val builderClass = XposedHelpers.findClassIfExists("android.location.GnssStatus\$Builder", classLoader)
            if (builderClass != null) {
                val builder = builderClass.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
                val addSatellite = builderClass.getMethod(
                    "addSatellite",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
                repeat(n) { i ->
                    val sat = satellites[i]
                    addSatellite.invoke(
                        builder, sat.type, sat.svid, sat.cn0, sat.elevation, sat.azimuth,
                        true, true, sat.usedInFix, false, 0f, false, 0f
                    )
                }
                return builderClass.getMethod("build").invoke(builder)
            }
        } catch (error: Throwable) {
            XposedBridge.logEvery(
                "gnssBuilderAdd:$packageName",
                "[WeRePosition] GnssStatus.Builder failed pkg=$packageName error=${error.javaClass.simpleName}"
            )
        }

        // Older Android releases may only expose the internal constructor.
        try {
            val flags = IntArray(n) { i ->
                val sat = satellites[i]
                var value = sat.type shl 3
                if (sat.usedInFix) value = value or 0x01
                value = value or 0x02 or 0x04
                (sat.svid shl 8) or value
            }
            val cn0 = FloatArray(n) { satellites[it].cn0 }
            val elevation = FloatArray(n) { satellites[it].elevation }
            val azimuth = FloatArray(n) { satellites[it].azimuth }
            statusClass.declaredConstructors.firstOrNull { it.parameterCount == 5 }?.let {
                it.isAccessible = true
                return it.newInstance(n, cn0, elevation, azimuth, flags)
            }
        } catch (_: Throwable) {
        }
        return null
    }
}
