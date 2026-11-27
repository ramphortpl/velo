package fr.velogo.core.nav

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.tiles.VectorTile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tan

/** Donne les tuiles vectorielles (décompressées) du fond de carte du téléphone, ou null là où il n'y en a pas. */
fun interface RoadTiles {
    fun tile(z: Int, x: Int, y: Int): ByteArray?
}

/**
 * Retrouve le nom des rues qu'emprunte un itinéraire.
 *
 * Le moteur d'itinéraire ne connaît pas les noms : ses données n'en contiennent pas. Mais la carte hors ligne du
 * téléphone, elle, les a (couche « roads » du fond Protomaps). Pour chaque consigne, on regarde donc sur quelle
 * voie nommée passe l'itinéraire juste après la manœuvre : quelques points de sonde le long du tracé, et pour
 * chacun la voie la plus proche qui va dans le même sens. Le nom retenu est celui que la majorité des sondes
 * désigne ; s'il n'y a pas d'accord net (voie sans nom, carte absente), la consigne reste sans nom.
 */
class StreetNamer(private val tiles: RoadTiles, private val zoom: Int = DEFAULT_ZOOM) {

    private class Road(val name: String, val line: IntArray)
    private class Roads(val extent: Int, val roads: List<Road>)

    /** Tuiles déjà lues pendant ce calcul (un itinéraire en traverse quelques dizaines). */
    private val cache = object : LinkedHashMap<Long, Roads>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Roads>?): Boolean = size > MAX_CACHED_TILES
    }

    /** Le même itinéraire, dont chaque consigne porte le nom de la voie qu'on prend après elle, quand il est connu. */
    fun named(route: Route): Route {
        val instructions = route.instructions
        var found = false
        val named = instructions.mapIndexed { i, instruction ->
            if (instruction.maneuver == Maneuver.ARRIVE) return@mapIndexed instruction
            val next = instructions.getOrNull(i + 1)?.distanceM ?: route.distanceM
            val street = nameAfter(route, instruction, next)
            if (street != null) found = true
            if (street == instruction.street) instruction else instruction.copy(street = street)
        }
        return if (found) route.withInstructions(named) else route
    }

    /** Nom de la voie que suit l'itinéraire après [instruction], avant la consigne suivante (à [nextM] du départ). */
    private fun nameAfter(route: Route, instruction: Instruction, nextM: Double): String? {
        // Après un giratoire, la voie qu'on prend commence plus loin : on laisse passer l'anneau.
        val roundabout = instruction.maneuver == Maneuver.ROUNDABOUT
        val limit = (nextM - END_MARGIN_M).coerceAtMost(route.distanceM)
        var from = instruction.distanceM + if (roundabout) ROUNDABOUT_START_M else START_M
        var to = (instruction.distanceM + if (roundabout) ROUNDABOUT_SPAN_M else SPAN_M).coerceAtMost(limit)
        if (to - from < STEP_M) {
            // Deux consignes très rapprochées : on sonde le peu de tracé qui les sépare.
            from = (instruction.distanceM + 4.0).coerceAtMost(limit)
            to = limit
            if (to < from) return null
        }
        return nameAlong(route, from, to)
    }

    /** Nom de la voie que suit l'itinéraire entre [fromM] et [toM] mètres du départ, ou null s'il n'est pas net. */
    fun nameAlong(route: Route, fromM: Double, toM: Double): String? {
        val votes = HashMap<String, Int>()
        var probes = 0
        var m = fromM
        while (m <= toM + 0.5 && probes < MAX_PROBES) {
            probes++
            val a = route.positionAt(m - HALF_BASE_M)
            val b = route.positionAt(m + HALF_BASE_M)
            if (a != b) {
                val direction = Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon)
                roadAt(route.positionAt(m), direction)?.let { votes[it] = (votes[it] ?: 0) + 1 }
            }
            m += STEP_M
        }
        val best = votes.maxByOrNull { it.value } ?: return null
        // Une sonde isolée peut tomber sur une rue qu'on ne fait que croiser : il faut qu'un tiers au moins s'accordent.
        return best.key.takeIf { best.value * 3 >= probes }
    }

    /** Nom de la voie la plus proche de [p] qui va dans la direction [directionDeg] (dans un sens ou dans l'autre). */
    private fun roadAt(p: LatLon, directionDeg: Double): String? {
        val n = 1 shl zoom
        val latRad = Math.toRadians(p.lat)
        val worldX = (p.lon + 180.0) / 360.0 * n
        val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        val tileX = floor(worldX).toInt()
        val tileY = floor(worldY).toInt()
        val roads = roads(tileX, tileY) ?: return null
        val px = (worldX - tileX) * roads.extent
        val py = (worldY - tileY) * roads.extent
        val metersPerUnit = EARTH_CIRCUMFERENCE_M * cos(latRad) / n / roads.extent
        val reach = MAX_OFFSET_M / metersPerUnit
        var bestName: String? = null
        var best = reach
        for (road in roads.roads) {
            val line = road.line
            var i = 0
            while (i + 3 < line.size) {
                val ax = line[i].toDouble()
                val ay = line[i + 1].toDouble()
                val bx = line[i + 2].toDouble()
                val by = line[i + 3].toDouble()
                i += 2
                val dx = bx - ax
                val dy = by - ay
                val length2 = dx * dx + dy * dy
                if (length2 == 0.0) continue
                val t = (((px - ax) * dx + (py - ay) * dy) / length2).coerceIn(0.0, 1.0)
                val cx = ax + t * dx - px
                val cy = ay + t * dy - py
                val distance = sqrt(cx * cx + cy * cy)
                if (distance >= best) continue
                // Dans une tuile, y descend vers le sud : le nord est du côté des y décroissants.
                val bearing = Math.toDegrees(atan2(dx, -dy))
                if (unsignedAngle(bearing, directionDeg) > MAX_ANGLE_DEG) continue
                best = distance
                bestName = road.name
            }
        }
        return bestName
    }

    @Synchronized
    private fun roads(x: Int, y: Int): Roads? {
        val key = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        cache[key]?.let { return it.takeIf { roads -> roads.roads.isNotEmpty() } }
        val layer = tiles.tile(zoom, x, y)?.let { VectorTile.lines(it, LAYER, WANTED) }
        val roads = if (layer == null) EMPTY else Roads(
            layer.extent,
            layer.lines.flatMap { feature ->
                val kind = feature.attributes["kind"]
                val detail = feature.attributes["kind_detail"]
                val name = (feature.attributes["name"] ?: feature.attributes["ref"])?.trim()
                if (name.isNullOrEmpty() || kind in IGNORED_KINDS || detail in IGNORED_DETAILS) emptyList()
                else feature.lines.map { Road(name, it) }
            },
        )
        cache[key] = roads
        return roads.takeIf { it.roads.isNotEmpty() }
    }

    companion object {
        /** Zoom le plus détaillé du fond de carte hors ligne : toutes les voies y portent leur nom. */
        const val DEFAULT_ZOOM = 15
        private const val LAYER = "roads"
        private val WANTED = setOf("name", "ref", "kind", "kind_detail")
        /** Ce qui porte un nom dans la couche des voies sans être une voie où l'on roule. */
        private val IGNORED_KINDS = setOf("rail", "ferry", "aerialway", "aeroway")
        private val IGNORED_DETAILS = setOf("platform", "pier", "runway", "taxiway", "steps")
        private val EMPTY = Roads(4096, emptyList())
        private const val MAX_CACHED_TILES = 48
        private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

        /** Première sonde après la manœuvre (m) : le carrefour lui-même est passé. */
        private const val START_M = 12.0
        /** Jusqu'où l'on sonde après la manœuvre (m). */
        private const val SPAN_M = 90.0
        private const val ROUNDABOUT_START_M = 45.0
        private const val ROUNDABOUT_SPAN_M = 150.0
        private const val STEP_M = 13.0
        private const val MAX_PROBES = 9
        /** On s'arrête un peu avant la consigne suivante (m). */
        private const val END_MARGIN_M = 6.0
        /** Demi-longueur du bout de tracé qui donne la direction de l'itinéraire à une sonde (m). */
        private const val HALF_BASE_M = 6.0
        /** Écart toléré entre le tracé de l'itinéraire et celui de la voie sur la carte (m). */
        private const val MAX_OFFSET_M = 14.0
        private const val MAX_ANGLE_DEG = 35.0

        /** Angle entre deux directions sans tenir compte du sens (0 à 90°). */
        internal fun unsignedAngle(a: Double, b: Double): Double {
            val d = abs(a - b) % 180.0
            return if (d > 90.0) 180.0 - d else d
        }
    }
}
