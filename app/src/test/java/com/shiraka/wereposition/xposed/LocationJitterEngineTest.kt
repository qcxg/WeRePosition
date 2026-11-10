package com.shiraka.wereposition.xposed

import com.shiraka.wereposition.utils.EnvironmentCoveragePolicy
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationJitterEngineTest {
    private val latitude = 25.0330
    private val longitude = 121.5654

    @Test
    fun radiusControlsFrameworkCoordinateDriftAndAccuracy() {
        var now = 10_000L
        val small = LocationJitterEngine({ now }, Random(42L))
        val large = LocationJitterEngine({ now }, Random(42L))
        val oneMeter = LocationJitterEngine.Config(true, 1.0, "FAST", false)
        val eightyMeters = LocationJitterEngine.Config(true, 80.0, "FAST", false)
        var largestSmall = 0.0
        var largestLarge = 0.0
        var smallAccuracyTotal = 0.0
        var largeAccuracyTotal = 0.0
        repeat(30) {
            now += 1_000L
            val smallFix = small.sample(oneMeter, latitude, longitude)
            val largeFix = large.sample(eightyMeters, latitude, longitude)
            largestSmall = maxOf(largestSmall, metersFromAnchor(smallFix))
            largestLarge = maxOf(largestLarge, metersFromAnchor(largeFix))
            smallAccuracyTotal += smallFix.accuracy
            largeAccuracyTotal += largeFix.accuracy
            assertTrue(smallFix.accuracy in 4f..18f)
            assertTrue(largeFix.accuracy in 4f..18f)
        }
        assertTrue("1 m slider setting must bound actual drift", largestSmall <= 1.01)
        assertTrue("80 m slider setting must change actual drift", largestLarge > 5.0)
        assertTrue("larger radius raises reported uncertainty", largeAccuracyTotal > smallAccuracyTotal + 100.0)
    }

    @Test
    fun disabledJitterReturnsExactAnchorAndResetsPriorDrift() {
        var now = 10_000L
        val engine = LocationJitterEngine({ now }, Random(7L))
        val enabled = LocationJitterEngine.Config(true, 80.0, "FAST", false)
        engine.sample(enabled, latitude, longitude)
        now += 1_000L
        val disabled = engine.sample(enabled.copy(enabled = false), latitude, longitude)
        assertEquals(latitude, disabled.latitude, 0.0)
        assertEquals(longitude, disabled.longitude, 0.0)
        assertEquals(8f, disabled.accuracy, 0f)
        now += 1_000L
        val restarted = engine.sample(enabled.copy(radiusMeters = 1.0), latitude, longitude)
        assertTrue(metersFromAnchor(restarted) <= 1.01)
    }

    @Test
    fun rfEnabledSamplesRemainInAnchorCoverageCell() {
        var now = 10_000L
        val engine = LocationJitterEngine({ now }, Random(123L))
        val config = LocationJitterEngine.Config(true, 80.0, "FAST", true)
        val anchorCell = EnvironmentCoveragePolicy.cellFor(latitude, longitude)
        repeat(50) {
            now += 1_000L
            val fix = engine.sample(config, latitude, longitude)
            assertEquals(anchorCell, EnvironmentCoveragePolicy.cellFor(fix.latitude, fix.longitude))
        }
    }

    private fun metersFromAnchor(sample: LocationJitterEngine.Sample): Double {
        val north = (sample.latitude - latitude) * 111_319.49
        val east = (sample.longitude - longitude) * 111_319.49 * cos(Math.toRadians(latitude))
        return hypot(north, east)
    }
}
