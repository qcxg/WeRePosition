package com.shiraka.wereposition.xposed

import com.shiraka.wereposition.utils.EnvironmentCoveragePolicy
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Owns the process-local, bounded position drift and its reported uncertainty. */
internal class LocationJitterEngine(
    private val elapsedRealtimeMs: () -> Long,
    private val random: Random = Random(),
    private val logProfile: (String) -> Unit = {}
) {
    data class Config(
        val enabled: Boolean,
        val radiusMeters: Double,
        val speed: String,
        val constrainToRfCell: Boolean
    )

    data class Sample(val latitude: Double, val longitude: Double, val accuracy: Float)

    companion object {
        const val DEFAULT_RADIUS_METERS = 30.0
        const val MIN_RADIUS_METERS = 1.0
        const val MAX_RADIUS_METERS = 80.0
        private const val EARTH_RADIUS_METERS = 6_378_137.0
    }

    private var driftNorthMeters = 0.0
    private var driftEastMeters = 0.0
    private var accuracyDrift = 0.0
    private var lastCallTimeMs = 0L
    private var cachedAtElapsedMs = 0L
    private var cachedBaseLat = Double.NaN
    private var cachedBaseLng = Double.NaN
    private var cachedRadius = DEFAULT_RADIUS_METERS
    private var cachedSpeed = "MEDIUM"
    private var cachedRfCellEnforced = true
    private var cachedPosition = Pair(0.0, 0.0)
    private var lastLoggedSpeed = ""

    @Synchronized
    fun sample(config: Config, baseLat: Double, baseLng: Double): Sample {
        val enforceRfCell = config.constrainToRfCell
        if (!config.enabled) {
            driftNorthMeters = 0.0
            driftEastMeters = 0.0
            accuracyDrift = 0.0
            lastCallTimeMs = 0L
            cachedAtElapsedMs = 0L
            cachedBaseLat = baseLat
            cachedBaseLng = baseLng
            cachedRfCellEnforced = enforceRfCell
            cachedPosition = Pair(baseLat, baseLng)
            return Sample(baseLat, baseLng, 8f)
        }

        val radius = config.radiusMeters
            .coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        val profile = profileFor(config.speed)
        if (lastLoggedSpeed != profile.speedName) {
            lastLoggedSpeed = profile.speedName
            logProfile(
                "[WeRePosition] jitter profile=${profile.speedName} " +
                    "hold=${profile.sampleIntervalMs}ms timeScale=${profile.timeScale}"
            )
        }

        val now = elapsedRealtimeMs()
        if (now - cachedAtElapsedMs >= profile.sampleIntervalMs ||
            cachedBaseLat != baseLat || cachedBaseLng != baseLng ||
            cachedRadius != radius || cachedSpeed != profile.speedName ||
            cachedRfCellEnforced != enforceRfCell
        ) {
            val realDt = if (lastCallTimeMs > 0L) {
                ((now - lastCallTimeMs) / 1000.0).coerceIn(0.01, 5.0)
            } else 1.0
            lastCallTimeMs = now
            val dt = (realDt * profile.timeScale).coerceIn(0.01, 5.0)
            val sigma = radius / profile.sigmaDivisor
            val alpha = profile.pullback

            driftNorthMeters += sigma * sqrt(dt) * random.nextGaussian() -
                alpha * driftNorthMeters * dt
            driftEastMeters += sigma * sqrt(dt) * random.nextGaussian() -
                alpha * driftEastMeters * dt

            val distance = sqrt(driftNorthMeters * driftNorthMeters + driftEastMeters * driftEastMeters)
            if (distance > radius && distance > 0.0) {
                val scale = radius / distance
                driftNorthMeters *= scale
                driftEastMeters *= scale
            }

            val longitudeCosine = cos(Math.toRadians(baseLat))
                .let { if (abs(it) < 1e-6) 1e-6 else it }
            cachedPosition = constrainToCoverageCell(
                baseLat, baseLng, longitudeCosine, enforceRfCell
            )
            cachedAtElapsedMs = now
            cachedBaseLat = baseLat
            cachedBaseLng = baseLng
            cachedRadius = radius
            cachedSpeed = profile.speedName
            cachedRfCellEnforced = enforceRfCell
        }

        accuracyDrift += 0.5 * random.nextGaussian() - 0.03 * accuracyDrift
        // Radius bounds coordinate drift; horizontal accuracy is its typical uncertainty.
        val baseAccuracy = (radius * 0.25).coerceIn(5.0, 15.0)
        val accuracy = (baseAccuracy + accuracyDrift).coerceIn(4.0, 18.0).toFloat()
        return Sample(cachedPosition.first, cachedPosition.second, accuracy)
    }

    /** RF payloads belong to the anchor cell, so an enabled RF channel bounds drift there. */
    private fun constrainToCoverageCell(
        baseLat: Double,
        baseLng: Double,
        longitudeCosine: Double,
        enforceCell: Boolean
    ): Pair<Double, Double> {
        fun pointAt(scale: Double): Pair<Double, Double> = Pair(
            baseLat + Math.toDegrees(driftNorthMeters * scale / EARTH_RADIUS_METERS),
            EnvironmentCoveragePolicy.normalizeLongitude(
                baseLng + Math.toDegrees(
                    driftEastMeters * scale / (EARTH_RADIUS_METERS * longitudeCosine)
                )
            )
        )

        val candidate = pointAt(1.0)
        if (!enforceCell) return candidate
        val baseCell = EnvironmentCoveragePolicy.cellFor(baseLat, baseLng) ?: return candidate
        if (EnvironmentCoveragePolicy.cellFor(candidate.first, candidate.second) == baseCell) {
            return candidate
        }

        var inside = 0.0
        var outside = 1.0
        repeat(28) {
            val middle = (inside + outside) / 2.0
            val point = pointAt(middle)
            if (EnvironmentCoveragePolicy.cellFor(point.first, point.second) == baseCell) {
                inside = middle
            } else {
                outside = middle
            }
        }
        val safeScale = (inside * 0.999_999).coerceIn(0.0, 1.0)
        driftNorthMeters *= safeScale
        driftEastMeters *= safeScale
        return pointAt(1.0)
    }

    private data class Profile(
        val speedName: String,
        val sampleIntervalMs: Long,
        val sigmaDivisor: Double,
        val pullback: Double,
        val timeScale: Double
    )

    private fun profileFor(speed: String): Profile = when (speed.uppercase()) {
        "SLOW" -> Profile("SLOW", 2_800L, 6.7, 0.10, 0.12)
        "FAST" -> Profile("FAST", 800L, 6.7, 0.10, 2.50)
        else -> Profile("MEDIUM", 1_500L, 6.7, 0.10, 0.65)
    }
}
