package fr.velogo.app.nav

import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Polyline
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.WayMix
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Met un itinéraire en texte (JSON compact) pour l'enregistrer dans la base, et le relit.
 * La trace est encodée en « polyline » (précision d'environ un mètre), les altitudes en décimètres.
 */
object RouteCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(route: Route): String = json.encodeToString(
        RouteDto.serializer(),
        RouteDto(
            trace = Polyline.encode(route.points),
            altitudes = route.elevations?.map { Math.round(it * 10).toInt() },
            duree = route.durationS,
            montee = route.ascentM,
            descente = route.descentM,
            consignes = route.instructions.map { InstructionDto(it.index, it.maneuver.name, it.exit) },
            etapes = route.waypoints.map { listOf(it.lat, it.lon) },
            voies = route.mix?.let { listOf(it.cyclewayM, it.quietM, it.roadM, it.busyM, it.pathM, it.unpavedM) },
        ),
    )

    /** Relit un itinéraire enregistré, ou renvoie null si le texte est illisible. */
    fun decode(text: String): Route? = runCatching {
        val dto = json.decodeFromString(RouteDto.serializer(), text)
        val points = Polyline.decode(dto.trace)
        if (points.size < 2) return null
        val distances = Route.cumulativeDistances(points)
        val elevations = dto.altitudes?.takeIf { it.size == points.size }?.let { list -> DoubleArray(list.size) { list[it] / 10.0 } }
        val instructions = dto.consignes.mapNotNull { c ->
            val maneuver = Maneuver.entries.firstOrNull { it.name == c.m } ?: return@mapNotNull null
            if (c.i !in points.indices) return@mapNotNull null
            Instruction(c.i, distances[c.i], maneuver, c.s)
        }.ifEmpty { listOf(Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)) }
        val waypoints = dto.etapes.filter { it.size == 2 }.map { LatLon(it[0], it[1]) }.takeIf { it.size >= 2 } ?: listOf(points.first(), points.last())
        val mix = dto.voies?.takeIf { it.size == 6 }?.let { WayMix(it[0], it[1], it[2], it[3], it[4], it[5]) }
        Route(points, elevations, distances, dto.duree, dto.montee, dto.descente, instructions, waypoints, mix)
    }.getOrNull()

    @Serializable
    private class RouteDto(
        val trace: String,
        val altitudes: List<Int>? = null,
        val duree: Double = 0.0,
        val montee: Double = 0.0,
        val descente: Double = 0.0,
        val consignes: List<InstructionDto> = emptyList(),
        val etapes: List<List<Double>> = emptyList(),
        val voies: List<Double>? = null,
    )

    @Serializable
    private class InstructionDto(val i: Int, val m: String, val s: Int = 0)
}
