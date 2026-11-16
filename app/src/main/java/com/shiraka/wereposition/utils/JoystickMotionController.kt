package com.shiraka.wereposition.utils

/** Latest velocity command only. Call under the service's motion lock. */
class JoystickMotionController(
    latitude: Double,
    longitude: Double,
    nowNanos: Long
) {
    data class Position(val latitude: Double, val longitude: Double, val bearing: Float, val speed: Float)

    private var position = Position(latitude, longitude, 0f, 0f)
    private var integratedAt = nowNanos
    private var commandExpiresAt = nowNanos
    private var lastCommandAt = nowNanos

    fun command(bearing: Double, intensity: Float, maxSpeed: Float, nowNanos: Long): Position {
        if (nowNanos < lastCommandAt) return position
        sample(nowNanos)
        if (!bearing.isFinite() || !intensity.isFinite() || !maxSpeed.isFinite()) return stop(nowNanos)
        val strength = ((intensity.coerceIn(0f, 1f) - DEAD_ZONE) / (1f - DEAD_ZONE)).coerceIn(0f, 1f)
        val speed = maxSpeed.coerceIn(0f, 20f) * strength
        position = position.copy(bearing = if (speed > 0f) ((bearing % 360 + 360) % 360).toFloat()
            else position.bearing, speed = speed)
        commandExpiresAt = nowNanos + COMMAND_LEASE_NANOS
        lastCommandAt = nowNanos
        return position
    }

    fun sample(nowNanos: Long): Position {
        val end = minOf(nowNanos, commandExpiresAt)
        val elapsed = (end - integratedAt).coerceIn(0L, MAX_STEP_NANOS)
        if (position.speed > 0f && elapsed > 0L) {
            val (lat, lng) = JoystickMotion.advance(position.latitude, position.longitude,
                position.bearing.toDouble(), position.speed * elapsed / 1_000_000_000.0)
            position = position.copy(latitude = lat, longitude = lng)
        }
        integratedAt = maxOf(integratedAt, nowNanos)
        if (nowNanos >= commandExpiresAt) position = position.copy(speed = 0f)
        return position
    }

    fun stop(nowNanos: Long): Position {
        if (nowNanos < lastCommandAt) return position
        sample(nowNanos)
        lastCommandAt = nowNanos
        commandExpiresAt = nowNanos
        position = position.copy(speed = 0f)
        return position
    }

    companion object {
        const val DEAD_ZONE = 0.1f
        const val COMMAND_LEASE_NANOS = 500_000_000L
        private const val MAX_STEP_NANOS = 250_000_000L
    }
}
