package fr.velogo.core.ride

import fr.velogo.core.geo.Geo

/**
 * Prépare une trace lue dans un fichier (GPX d'une autre appli, ou sauvegarde de VeloGo) pour en faire une sortie
 * de l'historique.
 */
object TrackImport {
    /**
     * Garde les points datés, dans l'ordre du temps, et recalcule la vitesse à chaque point (un GPX ne la contient
     * pas) pour que le graphique de vitesse existe. Renvoie null si la trace n'est pas une sortie : pas d'heures,
     * trop peu de points, ou moins de [minDistanceM] parcourus.
     */
    fun prepare(points: List<TrackPoint>, minPoints: Int = 10, minDistanceM: Double = 100.0): List<TrackPoint>? {
        val dated = ArrayList<TrackPoint>(points.size)
        for (p in points) {
            if (p.timeMs <= 0L) continue
            val last = dated.lastOrNull()
            // Un point daté d'avant le précédent (horloge du GPS qui saute) ne peut pas être placé : on l'écarte.
            if (last != null && p.timeMs < last.timeMs) continue
            dated.add(p)
        }
        if (dated.size < minPoints) return null
        val distances = RideAnalyzer.cumulativeDistances(dated)
        if (distances.last() < minDistanceM) return null

        // Vitesse brute entre deux points voisins du même tronçon, puis moyenne sur cinq points pour gommer le bruit.
        val raw = DoubleArray(dated.size) { Double.NaN }
        for (i in 1 until dated.size) {
            val a = dated[i - 1]
            val b = dated[i]
            val dt = (b.timeMs - a.timeMs) / 1000.0
            if (a.segment == b.segment && dt > 0.0 && dt <= MAX_GAP_S) {
                val speed = (distances[i] - distances[i - 1]) / dt
                if (speed <= MAX_PLAUSIBLE_MPS) raw[i] = speed
            }
        }
        return dated.mapIndexed { i, p ->
            if (p.speedMps != null) return@mapIndexed p
            var sum = 0.0
            var count = 0
            for (j in (i - SMOOTHING)..(i + SMOOTHING)) {
                if (j in raw.indices && !raw[j].isNaN() && dated[j].segment == p.segment) {
                    sum += raw[j]
                    count++
                }
            }
            p.copy(speedMps = if (count > 0) sum / count else null)
        }
    }

    /** Distance parcourue par une trace (les sauts entre tronçons ne comptent pas). */
    fun distanceM(points: List<TrackPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.segment == b.segment) total += Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
        }
        return total
    }

    private const val SMOOTHING = 2
    /** Au-delà de deux minutes entre deux points, on était à l'arrêt : la « vitesse » n'aurait pas de sens. */
    private const val MAX_GAP_S = 120.0
    private const val MAX_PLAUSIBLE_MPS = 33.0
}
