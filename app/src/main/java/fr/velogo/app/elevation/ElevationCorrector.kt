package fr.velogo.app.elevation

import fr.velogo.app.data.RideRepository
import fr.velogo.app.data.db.RideEntity
import fr.velogo.core.geo.LatLon
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TerrainCorrection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recale l'altitude d'une sortie terminée sur le relief.
 * Le GPS seul (le téléphone n'a pas de baromètre) dérive de plusieurs mètres : le dénivelé qu'on en tire est
 * approximatif. Avec le relief, il devient fiable.
 *
 * Deux sources, de la plus fine à la plus disponible : le service altimétrique de l'IGN (en ligne, au mètre
 * près), puis le relief des cartes hors ligne (sans réseau, là où une région est téléchargée).
 */
class ElevationCorrector(
    private val rides: RideRepository,
    private val ign: IgnAltimetry,
    private val relief: ReliefSource,
) {

    /**
     * Renvoie vrai si la sortie a été recalée. Avec [online] à faux, l'IGN n'est pas interrogé.
     * Sans réseau et sans carte hors ligne, la sortie garde son altitude GPS : on pourra réessayer.
     */
    suspend fun correct(rideId: Long, online: Boolean = true): Boolean {
        val points = rides.points(rideId)
        if (points.size < MIN_POINTS) return false
        val distances = RideAnalyzer.cumulativeDistances(points)
        val indices = TerrainCorrection.sampleIndices(distances, STEP_M, MAX_SAMPLES)
        val positions = indices.map { LatLon(points[it].lat, points[it].lon) }

        var source = RideEntity.ALTITUDE_IGN
        var sampled = if (online) runCatching { ign.elevations(positions) }.getOrNull()?.takeIf { usable(it) } else null
        if (sampled == null) {
            source = RideEntity.ALTITUDE_RELIEF
            sampled = withContext(Dispatchers.Default) {
                DoubleArray(positions.size) { relief.altitude(positions[it].lat, positions[it].lon) ?: Double.NaN }
            }.takeIf { usable(it) }
        }
        // Trace largement hors de France, hors des régions téléchargées, ou service en panne : on garde le GPS.
        if (sampled == null) return false

        val terrain = TerrainCorrection.interpolate(distances, indices, sampled)
        val fused = TerrainCorrection.fuse(terrain, points.map { it.altitudeM }, distances)
        val corrected = points.mapIndexed { i, p -> if (fused[i].isNaN()) p else p.copy(altitudeM = fused[i]) }
        val totals = RideAnalyzer.summarize(corrected, AltitudeSource.TERRAIN)
        rides.replaceAltitudes(rideId, corrected, totals, source)
        return true
    }

    /** Sorties récentes restées sur leur altitude GPS (pas de réseau ce jour-là) : nouvelle tentative. */
    suspend fun retryPending(online: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val candidates = rides.finishedRides()
            .filter { it.altitudeSource == RideEntity.ALTITUDE_GNSS && nowMs - it.startTimeMs < RETRY_WINDOW_MS }
            .take(MAX_RETRIES)
        for (ride in candidates) runCatching { correct(ride.id, online) }
    }

    private fun usable(sampled: DoubleArray): Boolean =
        sampled.isNotEmpty() && sampled.count { it.isNaN() } <= sampled.size * MAX_MISSING_RATIO

    private companion object {
        const val MIN_POINTS = 10
        const val STEP_M = 12.0
        const val MAX_SAMPLES = 16_000
        const val MAX_MISSING_RATIO = 0.2
        const val RETRY_WINDOW_MS = 21L * 24 * 60 * 60 * 1000
        const val MAX_RETRIES = 6
    }
}
