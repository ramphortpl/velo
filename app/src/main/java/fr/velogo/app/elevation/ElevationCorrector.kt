package fr.velogo.app.elevation

import fr.velogo.app.data.RideRepository
import fr.velogo.app.data.db.RideEntity
import fr.velogo.core.geo.LatLon
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TerrainCorrection

/**
 * Recale l'altitude d'une sortie terminée sur le relief de l'IGN.
 * Le GPS seul (le téléphone n'a pas de baromètre) dérive de plusieurs mètres : le dénivelé qu'on en tire est
 * approximatif. Avec le relief, il devient fiable.
 */
class ElevationCorrector(private val rides: RideRepository, private val ign: IgnAltimetry) {

    /** Renvoie vrai si la sortie a été recalée. Échoue silencieusement sans réseau : on pourra réessayer. */
    suspend fun correct(rideId: Long): Boolean {
        val points = rides.points(rideId)
        if (points.size < MIN_POINTS) return false
        val distances = RideAnalyzer.cumulativeDistances(points)
        val indices = TerrainCorrection.sampleIndices(distances, STEP_M, MAX_SAMPLES)
        val sampled = try {
            ign.elevations(indices.map { LatLon(points[it].lat, points[it].lon) })
        } catch (_: Exception) {
            return false
        }
        // Trace largement hors de France (ou service en panne) : on garde l'altitude du GPS.
        if (sampled.count { it.isNaN() } > sampled.size * MAX_MISSING_RATIO) return false

        val terrain = TerrainCorrection.interpolate(distances, indices, sampled)
        val fused = TerrainCorrection.fuse(terrain, points.map { it.altitudeM }, distances)
        val corrected = points.mapIndexed { i, p -> if (fused[i].isNaN()) p else p.copy(altitudeM = fused[i]) }
        val totals = RideAnalyzer.summarize(corrected, AltitudeSource.TERRAIN)
        rides.replaceAltitudes(rideId, corrected, totals, RideEntity.ALTITUDE_IGN)
        return true
    }

    private companion object {
        const val MIN_POINTS = 10
        const val STEP_M = 12.0
        const val MAX_SAMPLES = 16_000
        const val MAX_MISSING_RATIO = 0.2
    }
}
