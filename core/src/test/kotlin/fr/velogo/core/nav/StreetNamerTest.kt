package fr.velogo.core.nav

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.tiles.VectorTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Noms de rue retrouvés dans de vraies tuiles de Corte (zoom 15, couche des voies). Les itinéraires d'essai sont
 * tracés sur les rues telles que la carte les dessine : ils ne dépendent d'aucun calcul d'itinéraire.
 */
class StreetNamerTest {

    private val tiles = RoadTiles { z, x, y -> javaClass.getResource("/tuiles/roads-$z-$x-$y.mvt")?.readBytes() }

    private val tileX = 17216
    private val tileY = 12126

    /** Position géographique d'un point de la tuile du centre de Corte. */
    private fun latLon(x: Int, y: Int, extent: Int): LatLon {
        val n = (1 shl 15).toDouble()
        val lon = (tileX + x.toDouble() / extent) / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (tileY + y.toDouble() / extent) / n))))
        return LatLon(lat, lon)
    }

    /** Le plus long tronçon de la rue [name] dans la tuile du centre, en positions géographiques. */
    private fun street(name: String): List<LatLon> {
        val layer = VectorTile.lines(tiles.tile(15, tileX, tileY)!!, "roads", setOf("name"))!!
        val line = layer.lines.filter { it.attributes["name"] == name }.flatMap { it.lines }.maxByOrNull { it.size }
            ?: throw AssertionError("pas de « $name » dans la tuile")
        return (0 until line.size / 2).map { latLon(line[2 * it], line[2 * it + 1], layer.extent) }
    }

    private fun route(points: List<LatLon>, vararg maneuvers: Pair<Int, Maneuver>): Route {
        val distances = Route.cumulativeDistances(points)
        val instructions = maneuvers.map { (index, maneuver) -> Instruction(index, distances[index], maneuver) } +
            Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)
        return Route(points, null, distances, 60.0, 0.0, 0.0, instructions, listOf(points.first(), points.last()))
    }

    @Test
    fun `une consigne recoit le nom de la rue qu on prend apres elle`() {
        val cours = street("Cours Paoli")
        assertTrue("tronçon de ${Route.cumulativeDistances(cours).last()} m", Route.cumulativeDistances(cours).last() > 40)
        val named = StreetNamer(tiles).named(route(cours, 0 to Maneuver.LEFT))
        assertEquals("Cours Paoli", named.instructions.first().street)
        // L'arrivée n'a pas de nom, et le reste de l'itinéraire est inchangé.
        assertNull(named.instructions.last().street)
        assertEquals(cours, named.points)
    }

    @Test
    fun `le nom est le meme dans l autre sens et a quelques metres de la chaussee`() {
        val cours = street("Cours Paoli").reversed()
        // Tracé décalé de six mètres sur le côté : le GPS et la carte ne se superposent jamais exactement.
        val shifted = cours.mapIndexed { i, p ->
            val other = if (i < cours.size - 1) cours[i + 1] else cours[i - 1]
            val along = if (i < cours.size - 1) Geo.bearingDeg(p.lat, p.lon, other.lat, other.lon) else Geo.bearingDeg(other.lat, other.lon, p.lat, p.lon)
            Geo.offset(p.lat, p.lon, along + 90.0, 6.0)
        }
        assertEquals("Cours Paoli", StreetNamer(tiles).named(route(shifted, 0 to Maneuver.CONTINUE)).instructions.first().street)
    }

    @Test
    fun `une rue qu on ne fait que croiser ne donne pas son nom`() {
        val cours = street("Cours Paoli")
        val middle = cours[cours.size / 2]
        val before = cours[cours.size / 2 - 1]
        val along = Geo.bearingDeg(before.lat, before.lon, middle.lat, middle.lon)
        // Cent mètres en travers du cours, qu'on coupe à angle droit en son milieu.
        val start = Geo.offset(middle.lat, middle.lon, along + 90.0, 50.0)
        val points = (0..10).map { Geo.offset(start.lat, start.lon, along - 90.0, it * 10.0) }
        val crossing = route(points, 0 to Maneuver.RIGHT)
        assertTrue("jamais « Cours Paoli » en le coupant", StreetNamer(tiles).named(crossing).instructions.first().street != "Cours Paoli")
    }

    @Test
    fun `sans carte l itineraire reste tel quel`() {
        val cours = street("Cours Paoli")
        val plain = route(cours, 0 to Maneuver.LEFT)
        assertSame(plain, StreetNamer(RoadTiles { _, _, _ -> null }).named(plain))
        // En pleine mer, la carte de Corte n'a rien à dire non plus.
        val sea = route((0..10).map { Geo.offset(42.0, 9.9, 90.0, it * 20.0) }, 0 to Maneuver.LEFT)
        assertSame(sea, StreetNamer(tiles).named(sea))
    }

    @Test
    fun `deux directions se comparent sans tenir compte du sens`() {
        assertEquals(0.0, StreetNamer.unsignedAngle(10.0, 190.0), 1e-9)
        assertEquals(20.0, StreetNamer.unsignedAngle(350.0, 10.0), 1e-9)
        assertEquals(90.0, StreetNamer.unsignedAngle(0.0, 90.0), 1e-9)
        assertEquals(30.0, StreetNamer.unsignedAngle(-100.0, 110.0), 1e-9)
    }
}
