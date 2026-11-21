package fr.velogo.app.ui.stats

import fr.velogo.app.data.db.BestEffortEntity
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.ui.util.Dates
import fr.velogo.core.format.Formats
import fr.velogo.core.stats.Period
import fr.velogo.core.stats.PeriodTotals
import fr.velogo.core.stats.RecordKind
import fr.velogo.core.stats.RideStats
import fr.velogo.core.stats.RideSummary
import java.time.LocalDate
import java.time.ZoneId

/** Une ligne de record ou de meilleur temps : ce qu'elle dit, et la sortie où elle a été établie. */
data class RecordRow(val label: String, val value: String, val detail: String, val rideId: Long)

/** Ce que l'écran des statistiques affiche. */
data class StatsUiState(
    /** Faux tant que les sorties ne sont pas lues. */
    val loaded: Boolean = false,
    val period: Period = Period.WEEK,
    /** Les périodes, de la plus ancienne à la plus récente (celle d'aujourd'hui en dernier). */
    val buckets: List<PeriodTotals> = emptyList(),
    /** Indice de la période choisie dans [buckets]. */
    val selected: Int = 0,
    val allTime: PeriodTotals? = null,
    val records: List<RecordRow> = emptyList(),
    val bests: List<RecordRow> = emptyList(),
) {
    val isEmpty: Boolean get() = (allTime?.rides ?: 0) == 0
    val current: PeriodTotals? get() = buckets.getOrNull(selected)
    val previous: PeriodTotals? get() = buckets.getOrNull(selected - 1)
}

fun RideEntity.summary() = RideSummary(id, startTimeMs, distanceM, movingMs, ascentM, maxSpeedMps, maxAltitudeM)

/**
 * Prépare l'écran des statistiques. [back] dit de combien de périodes on recule depuis celle d'aujourd'hui
 * (0 = la semaine, le mois ou l'année en cours).
 */
fun buildStats(
    rides: List<RideEntity>,
    efforts: List<BestEffortEntity>,
    period: Period,
    back: Int,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): StatsUiState {
    val summaries = rides.map { it.summary() }
    val count = when (period) {
        Period.WEEK -> RideStats.span(summaries, period, today, zone, min = 8, max = 12)
        Period.MONTH -> RideStats.span(summaries, period, today, zone, min = 6, max = 12)
        Period.YEAR -> RideStats.span(summaries, period, today, zone, min = 1, max = 8)
    }
    val buckets = RideStats.buckets(summaries, period, today, zone, count)
    val byId = rides.associateBy { it.id }
    val records = RideStats.records(summaries).map { record ->
        RecordRow(
            label = when (record.kind) {
                RecordKind.DISTANCE -> "Plus longue sortie"
                RecordKind.ASCENT -> "Plus gros dénivelé"
                RecordKind.DURATION -> "Plus longtemps en selle"
                RecordKind.AVG_SPEED -> "Meilleure moyenne"
                RecordKind.MAX_SPEED -> "Vitesse la plus haute"
                RecordKind.ALTITUDE -> "Point le plus haut"
            },
            value = when (record.kind) {
                RecordKind.DISTANCE -> "${Formats.distanceKm(record.value)} km"
                RecordKind.ASCENT, RecordKind.ALTITUDE -> "${Formats.meters(record.value)} m"
                RecordKind.DURATION -> Formats.durationWords(record.value.toLong())
                RecordKind.AVG_SPEED, RecordKind.MAX_SPEED -> "${Formats.speedKmh(record.value)} km/h"
            },
            detail = listOfNotNull(
                Dates.longDay(record.timeMs),
                "sorties de 10 km et plus".takeIf { record.kind == RecordKind.AVG_SPEED },
            ).joinToString(" · "),
            rideId = record.rideId,
        )
    }
    // Le meilleur temps de chaque distance, toutes sorties confondues (les sorties supprimées n'en ont plus).
    val bests = efforts.filter { it.rideId in byId }.groupBy { it.distanceM }.toSortedMap().map { (distance, list) ->
        val best = list.minBy { it.durationMs }
        RecordRow(
            label = "${distance / 1000} km",
            value = Formats.duration(best.durationMs),
            detail = "${Formats.speedKmh(distance / (best.durationMs / 1000.0))} km/h · ${Dates.longDay(best.startTimeMs)}",
            rideId = best.rideId,
        )
    }
    return StatsUiState(
        loaded = true,
        period = period,
        buckets = buckets,
        selected = (buckets.size - 1 - back).coerceIn(0, buckets.size - 1),
        allTime = RideStats.allTime(summaries, zone),
        records = records,
        bests = bests,
    )
}

/** Nom de la période, en entier : « Semaine du 16 au 22 novembre 2026 », « Novembre 2026 », « Année 2026 ». */
fun periodTitle(period: Period, start: LocalDate): String = when (period) {
    Period.WEEK -> "Semaine du ${Dates.weekRange(start)}"
    Period.MONTH -> Dates.monthYear(start)
    Period.YEAR -> "Année ${start.year}"
}

/** Étiquette courte sous une barre du graphique : « 16 nov. », « nov. », « 2026 ». */
fun periodLabel(period: Period, start: LocalDate): String = when (period) {
    Period.WEEK -> Dates.dayShortMonth(start)
    Period.MONTH -> Dates.shortMonth(start)
    Period.YEAR -> start.year.toString()
}

/** « 12,4 km de plus que la semaine précédente », ou null s'il n'y a rien à comparer. */
fun comparison(period: Period, current: PeriodTotals?, previous: PeriodTotals?): String? {
    if (current == null || previous == null || (current.rides == 0 && previous.rides == 0)) return null
    val delta = current.distanceM - previous.distanceM
    val before = when (period) {
        Period.WEEK -> "la semaine précédente"
        Period.MONTH -> "le mois précédent"
        Period.YEAR -> "l'année précédente"
    }
    return when {
        kotlin.math.abs(delta) < 500.0 -> "Autant que $before"
        delta > 0 -> "${Formats.distanceAuto(delta)} de plus que $before"
        else -> "${Formats.distanceAuto(-delta)} de moins que $before"
    }
}
