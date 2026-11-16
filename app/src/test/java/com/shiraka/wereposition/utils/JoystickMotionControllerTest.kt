package com.shiraka.wereposition.utils

import org.junit.Assert.*
import org.junit.Test

class JoystickMotionControllerTest {
    private fun controller() = JoystickMotionController(0.0, 0.0, 0L)
    private fun ms(value: Long) = value * 1_000_000L

    @Test fun centerCommandClearsSpeedAndHoldsPosition() {
        val motion = controller()
        motion.command(90.0, 1f, 10f, 0L)
        val stopped = motion.command(90.0, 0f, 10f, ms(100))
        assertEquals(0f, stopped.speed, 0f)
        assertTrue(stopped.longitude > 0)
        assertEquals(stopped, motion.sample(ms(400)))
    }

    @Test fun releaseFreezesTheIntegratedPositionWithoutReturningToAnchor() {
        val motion = controller()
        motion.command(0.0, 1f, 10f, 0L)
        val stopped = motion.stop(ms(100))
        assertTrue(stopped.latitude > 0)
        assertEquals(0f, stopped.speed, 0f)
        repeat(30) { assertEquals(stopped, motion.sample(ms(200L + it * 100))) }
    }

    @Test fun staleCommandCannotRestartAfterRelease() {
        val motion = controller()
        motion.command(90.0, 1f, 10f, 0L)
        val stopped = motion.stop(ms(100))
        motion.command(90.0, 1f, 10f, ms(50))
        assertEquals(stopped, motion.sample(ms(200)))
    }

    @Test fun lostInputTimesOutAndDoesNotCatchUpAfterAStall() {
        val motion = controller()
        motion.command(90.0, 1f, 20f, 0L)
        val stopped = motion.sample(ms(5_000))
        assertEquals(0f, stopped.speed, 0f)
        assertEquals(5.0, Math.toRadians(stopped.longitude) * 6_378_137, 0.001)
        assertEquals(stopped, motion.sample(ms(6_000)))
    }

    @Test fun turnsApplyToFutureDistanceWithoutReplayingOldDirection() {
        val motion = controller()
        motion.command(90.0, 1f, 10f, 0L)
        val turn = motion.command(0.0, 1f, 10f, ms(100))
        val next = motion.sample(ms(200))
        assertEquals(turn.longitude, next.longitude, 0.00000001)
        assertTrue(next.latitude > turn.latitude)
        assertEquals(0f, next.bearing, 0f)
    }

    @Test fun irregularPollingDoesNotChangeTravelDistance() {
        val steady = controller()
        val irregular = controller()
        listOf(steady, irregular).forEach { it.command(90.0, 1f, 10f, 0L) }
        steady.sample(ms(100)); steady.sample(ms(200))
        irregular.sample(ms(20)); irregular.sample(ms(200))
        assertEquals(steady.stop(ms(250)).longitude, irregular.stop(ms(250)).longitude, 0.000000001)
    }

    @Test fun deadZoneAndInvalidInputsCannotMove() {
        val motion = controller()
        assertEquals(0f, motion.command(0.0, 0.09f, 10f, 0L).speed, 0f)
        assertEquals(0f, motion.command(Double.NaN, 1f, 10f, ms(10)).speed, 0f)
        assertEquals(0f, motion.command(0.0, Float.NaN, 10f, ms(20)).speed, 0f)
    }
}
