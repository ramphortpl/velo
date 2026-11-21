package fr.velogo.app.ui.segments

import fr.velogo.app.data.db.BestEffortEntity
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.data.db.SegmentEffortEntity
import fr.velogo.app.data.db.SegmentEntity
import fr.velogo.core.format.Formats

/** Un segment dans la liste : ses mesures, et ce qu'on y a déjà fait. */
data class SegmentRow(
    val id: Long,
    val name: String,
    /** « 3,2 km · ↗ 210 m · 6,5 % » */
    val facts: String,
    val efforts: Int,
    val bestMs: Long?,
)

/** Un passage sur un segment, classé parmi les autres. */
data class EffortRow(
    /** 1 pour le meilleur temps. */
    val rank: Int,
    val elapsedMs: Long,
    val speedMps: Double,
    val rideId: Long,
    val rideName: String,
    val startTimeMs: Long,
)

/** Pente moyenne du segment en %, si l'altitude de ses deux bouts est connue. */
fun SegmentEntity.gradePct(): Double? {
    val start = startAltitudeM ?: return null
    val end = endAltitudeM ?: return null
    return if (distanceM > 0.0) (end - start) / distanceM * 100.0 else null
}

/** Les mesures du segment en une ligne : « 3,2 km · ↗ 210 m · 6,5 % ». */
fun segmentFacts(distanceM: Double, ascentM: Double, gradePct: Double?): String = buildString {
    append(Formats.distanceAuto(distanceM))
    if (ascentM >= 5.0) append(" · ↗ ").append(Formats.meters(ascentM)).append(" m")
    // Sous 1 %, la pente moyenne n'apprend rien (faux plat, ou boucle qui revient à son point de départ).
    if (gradePct != null && kotlin.math.abs(gradePct) >= 1.0) append(" · ").append(Formats.decimal(gradePct, 1)).append(" %")
}

fun SegmentEntity.facts(): String = segmentFacts(distanceM, ascentM, gradePct())

/** La liste des segments, chacun avec son nombre de passages et son meilleur temps. */
fun segmentRows(segments: List<SegmentEntity>, efforts: List<SegmentEffortEntity>): List<SegmentRow> {
    val bySegment = efforts.groupBy { it.segmentId }
    return segments.map { segment ->
        val own = bySegment[segment.id].orEmpty()
        SegmentRow(segment.id, segment.name, segment.facts(), own.size, own.minOfOrNull { it.elapsedMs })
    }
}

/** Les passages d'un segment, du plus rapide au plus lent ; ceux d'une sortie supprimée entre-temps sont ignorés. */
fun effortRows(segment: SegmentEntity, efforts: List<SegmentEffortEntity>, rides: List<RideEntity>): List<EffortRow> {
    val byId = rides.associateBy { it.id }
    return efforts.sortedBy { it.elapsedMs }.mapNotNull { effort -> byId[effort.rideId]?.let { effort to it } }.mapIndexed { index, (effort, ride) ->
        EffortRow(
            rank = index + 1,
            elapsedMs = effort.elapsedMs,
            speedMps = if (effort.elapsedMs > 0) segment.distanceM / (effort.elapsedMs / 1000.0) else 0.0,
            rideId = ride.id,
            rideName = ride.name,
            startTimeMs = effort.startTimeMs,
        )
    }
}

/**
 * Nom proposé pour un nouveau segment, d'après ce qu'il est : « Côte de 3,5 km », « Descente de 850 m »,
 * « Segment de 1,8 km ». L'utilisateur peut le remplacer par le sien.
 */
fun suggestedSegmentName(distanceM: Double, gradePct: Double?): String {
    val kind = when {
        gradePct != null && gradePct >= 3.0 -> "Côte"
        gradePct != null && gradePct <= -3.0 -> "Descente"
        else -> "Segment"
    }
    return "$kind de ${Formats.distanceAuto(distanceM)}"
}

/** « Record », « 2e temps », « 3e temps »… */
fun rankLabel(rank: Int): String = if (rank == 1) "Record" else "${rank}e temps"

/** Un passage d'une sortie sur un segment, classé parmi tous les passages de ce segment. */
data class RideSegmentRow(
    val segmentId: Long,
    val name: String,
    val elapsedMs: Long,
    val startTimeMs: Long,
    /** 1 si c'est le meilleur temps du segment. */
    val rank: Int,
    /** Nombre total de passages sur ce segment, toutes sorties confondues. */
    val total: Int,
)

/** Les passages de la sortie [rideId] sur les segments, dans l'ordre où ils ont été roulés. */
fun rideSegmentRows(
    rideId: Long,
    segments: List<SegmentEntity>,
    efforts: List<SegmentEffortEntity>,
    /** Les sorties qui existent encore : les passages des autres ne comptent plus dans le classement. */
    rideIds: Set<Long>,
): List<RideSegmentRow> {
    val byId = segments.associateBy { it.id }
    val out = ArrayList<RideSegmentRow>()
    for ((segmentId, list) in efforts.filter { it.rideId in rideIds }.groupBy { it.segmentId }) {
        val segment = byId[segmentId] ?: continue
        val ranked = list.sortedBy { it.elapsedMs }
        ranked.forEachIndexed { index, effort ->
            if (effort.rideId == rideId) out.add(RideSegmentRow(segmentId, segment.name, effort.elapsedMs, effort.startTimeMs, index + 1, ranked.size))
        }
    }
    return out.sortedBy { it.startTimeMs }
}

/** Les distances de référence sur lesquelles la sortie [rideId] détient le meilleur temps : (« 10 km », « 21:34 »). */
fun rideBestRows(rideId: Long, efforts: List<BestEffortEntity>, rideIds: Set<Long>): List<Pair<String, String>> =
    efforts.filter { it.rideId in rideIds }.groupBy { it.distanceM }.toSortedMap().mapNotNull { (distance, list) ->
        val best = list.minBy { it.durationMs }
        // Un record ne se fête que s'il y a quelque chose à battre : au moins une autre sortie sur cette distance.
        if (best.rideId == rideId && list.size > 1) "${distance / 1000} km" to Formats.duration(best.durationMs) else null
    }
