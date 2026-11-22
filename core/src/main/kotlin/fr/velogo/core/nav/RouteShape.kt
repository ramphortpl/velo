package fr.velogo.core.nav

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon

/** L'itinéraire repasse par son point de départ en cours de route, puis continue jusqu'à son arrivée. */
data class StartReturn(
    /** Le point de la trace où l'on est de retour au départ. */
    val index: Int,
    /** Distance parcourue à ce moment-là, en mètres : la longueur de la boucle. */
    val distanceM: Double,
    /** Ce qu'il reste ensuite jusqu'à l'arrivée, en mètres. */
    val remainingM: Double,
)

/**
 * Ce qu'un itinéraire a de particulier dans sa forme.
 *
 * Un itinéraire peut passer deux fois par les mêmes routes : sur la carte, les deux passages se superposent et
 * n'en font qu'un. Cas vécu (22/11/2026) : arrivée posée à Bouffémont, puis le retour à la maison ajouté comme
 * simple étape. L'itinéraire faisait la boucle (29 km), repassait par la maison, puis repartait vers Bouffémont
 * par les routes du début (11 km de plus). Annoncé 40 km, il ne montrait à l'écran qu'une boucle de 29.
 */
object RouteShape {
    /**
     * À cette distance du départ (m), on considère qu'on y est revenu. Large exprès : le départ (« ma position »)
     * et l'étape « maison » posée au doigt ou par son adresse ne tombent pas forcément sur la même rue, et le
     * moteur d'itinéraire accroche chacune à la voie la plus proche d'elle.
     */
    const val NEAR_M = 200.0
    private const val AWAY_M = 500.0
    private const val MIN_REMAINING_M = 1_000.0

    /** Vrai si l'itinéraire finit là où il commence : c'est une boucle. */
    fun isLoop(route: Route, toleranceM: Double = NEAR_M): Boolean =
        route.distanceM > AWAY_M && Geo.distanceM(route.points.first(), route.points.last()) <= toleranceM

    /**
     * Le retour au point de départ d'un itinéraire qui n'y finit pas : il s'en éloigne, y repasse, puis continue
     * au moins un kilomètre jusqu'à son arrivée. Null pour un itinéraire ordinaire, et pour une boucle : une
     * boucle « en huit » a bien le droit de repasser par son départ, puisqu'elle y revient à la fin.
     *
     * S'il y repasse plusieurs fois, c'est le dernier passage qui compte : celui après lequel il ne revient plus.
     */
    fun returnToStart(route: Route): StartReturn? {
        if (isLoop(route)) return null
        val points = route.points
        val start = points.first()
        var found: StartReturn? = null
        var away = false
        var i = 1
        while (i < points.size) {
            if (!away) {
                // Tant qu'on n'est pas parti pour de bon, tourner autour du départ ne compte pas comme un retour.
                if (near(start, points[i]) > AWAY_M) away = true
                i++
                continue
            }
            if (passDistance(route, i) > NEAR_M) {
                i++
                continue
            }
            // Un passage : on y retient le point le plus proche du départ, jusqu'à ce que le tracé s'en éloigne.
            var best = i
            var bestM = Double.MAX_VALUE
            var j = i
            while (j < points.size && passDistance(route, j) <= NEAR_M) {
                for (k in j - 1..j) {
                    val d = near(start, points[k])
                    if (d < bestM) {
                        best = k
                        bestM = d
                    }
                }
                j++
            }
            val remaining = route.distanceM - route.distances[best]
            if (best >= 1 && remaining >= MIN_REMAINING_M) found = StartReturn(best, route.distances[best], remaining)
            // Il faut repartir pour de bon avant qu'un autre passage compte.
            away = false
            i = j
        }
        return found
    }

    /**
     * Distance entre deux points proches, par un calcul local bien plus léger que la distance exacte sur
     * l'ellipsoïde : un itinéraire a des milliers de points, et quelques mètres d'écart sur des seuils de 200 ou
     * 500 m ne changent rien.
     */
    private fun near(a: LatLon, b: LatLon): Double = Geo.distanceToSegmentM(a, b, b).distanceM

    /** Distance du départ au tronçon qui arrive au point [index] (un tronçon peut frôler le départ entre deux points). */
    private fun passDistance(route: Route, index: Int): Double =
        Geo.distanceToSegmentM(route.points.first(), route.points[index - 1], route.points[index]).distanceM
}
