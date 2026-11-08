package com.shiraka.wereposition

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shiraka.wereposition.utils.CoordinateConverter
import com.shiraka.wereposition.utils.CoordinateSystem
import com.shiraka.wereposition.utils.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoordinateConverterTest {
    @Test fun coordinateEntryRoundTripsWithoutChangingWgsRuntime() {
        val wgs = GeoPoint(31.2304, 121.4737)
        for (system in CoordinateSystem.entries) {
            val displayed = CoordinateConverter.fromWgs84(wgs, system)
            val runtime = CoordinateConverter.toWgs84(displayed, system)
            assertEquals(wgs.latitude, runtime.latitude, 0.000001)
            assertEquals(wgs.longitude, runtime.longitude, 0.000001)
        }
    }

    @Test fun gcjOutsideChinaLeavesWgsUntouched() {
        val taipei = GeoPoint(25.033, 121.5654)
        assertEquals(taipei, CoordinateConverter.fromWgs84(taipei, CoordinateSystem.GCJ02))
    }
}
