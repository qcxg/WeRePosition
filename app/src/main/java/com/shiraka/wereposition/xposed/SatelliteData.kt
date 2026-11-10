package com.shiraka.wereposition.xposed

/** One satellite in the synthetic sky shared by GNSS status and NMEA delivery. */
internal data class SatelliteData(
    val svid: Int,
    val type: Int, // 1=GPS, 3=GLONASS, 5=BDS
    val elevation: Float,
    val azimuth: Float,
    val cn0: Float,
    val usedInFix: Boolean
)
