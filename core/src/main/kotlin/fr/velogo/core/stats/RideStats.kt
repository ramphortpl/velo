package fr.velogo.core.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Ce qu'il faut savoir d'une sortie pour les statistiques. */
data class RideSummary(
    val id: Long,
    val startTimeMs: Long,
    val distanceM: Double,
    val movingMs: Long,
    val ascentM: Double,
    val maxSpeedMps: Double = 0.0,
    val maxAltitudeM: Double? = null,
) {
    val avgSpeedMps: Double get() = if (movingMs >= 1_000) distanceM / (movingMs / 1000.0) else 0.0
}

enum class Period { WEEK, MONTH, YEAR }

/** Les totaux d'une période (une semaine, un mois ou une année). */
data class PeriodTotals(
    /** Premier jour de la période. */
    val start: LocalDate,
    val rides: Int = 0,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val ascentM: Double = 0.0,
    /** Distance de la plus longue sortie de la période. */
    val longestM: Double = 0.0,
) {
    val avgSpeedMps: Double get() = if (movingMs >= 1_000) distanceM / (movingMs / 1000.0) else 0.0
}

enum class RecordKind { DISTANCE, ASCENT, DURATION, AVG_SPEED, MAX_SPEED, ALTITUDE }

/** Un record personnel : sa valeur (en mètres, millisecondes ou m/s selon [kind]) et la sortie où il a été établi. */
data class Record(val kind: RecordKind, val value: Double, val rideId: Long, val timeMs: Long)

/** Totaux par période et records, à partir de la liste des sorties. */
object RideStats {

    /** Premier jour de la période qui contient [date] : le lundi, le 1er du mois ou le 1er janvier. */
    fun periodStart(date: LocalDate, period: Period): LocalDate = when (period) {
        Period.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Period.MONTH -> date.withDayOfMonth(1)
        Period.YEAR -> date.withDayOfYear(1)
    }

    private fun next(start: LocalDate, period: Period): LocalDate = when (period) {
        Period.WEEK -> start.plusWeeks(1)
        Period.MONTH -> start.plusMonths(1)
        Period.YEAR -> start.plusYears(1)
    }

    private fun previous(start: LocalDate, period: Period): LocalDate = when (period) {
        Period.WEEK -> start.minusWeeks(1)
        Period.MONTH -> start.minusMonths(1)
        Period.YEAR -> start.minusYears(1)
    }

    /**
     * Les [count] dernières périodes jusqu'à celle d'aujourd'hui comprise, de la plus ancienne à la plus récente.
     * Les périodes sans sortie sont présentes, à zéro : un graphique doit montrer les semaines creuses.
     */
    fun buckets(rides: List<RideSummary>, period: Period, today: LocalDate, zone: ZoneId, count: Int): List<PeriodTotals> {
        require(count >= 1)
        val starts = ArrayList<LocalDate>(count)
        var start = periodStart(today, period)
        repeat(count) {
            starts.add(start)
            start = previous(start, period)
        }
        starts.reverse()
        val byStart = rides.groupBy { periodStart(Instant.ofEpochMilli(it.startTimeMs).atZone(zone).toLocalDate(), period) }
        return starts.map { total(it, byStart[it].orEmpty()) }
    }

    /** Nombre de périodes à montrer pour aller de la première sortie à aujourd'hui (au moins [min], au plus [max]). */
    fun span(rides: List<RideSummary>, period: Period, today: LocalDate, zone: ZoneId, min: Int, max: Int): Int {
        val first = rides.minOfOrNull { it.startTimeMs } ?: return min
        var start = periodStart(Instant.ofEpochMilli(first).atZone(zone).toLocalDate(), period)
        val last = periodStart(today, period)
        var n = 1
        while (start.isBefore(last) && n < max) {
            start = next(start, period)
            n++
        }
        return n.coerceIn(min, max)
    }

    /** Totaux depuis la toute première sortie. */
    fun allTime(rides: List<RideSummary>, zone: ZoneId): PeriodTotals {
        val first = rides.minOfOrNull { it.startTimeMs }
        val start = if (first != null) Instant.ofEpochMilli(first).atZone(zone).toLocalDate() else LocalDate.MIN
        return total(start, rides)
    }

    private fun total(start: LocalDate, rides: List<RideSummary>) = PeriodTotals(
        start = start,
        rides = rides.size,
        distanceM = rides.sumOf { it.distanceM },
        movingMs = rides.sumOf { it.movingMs },
        ascentM = rides.sumOf { it.ascentM },
        longestM = rides.maxOfOrNull { it.distanceM } ?: 0.0,
    )

    /**
     * Les records personnels. La meilleure vitesse moyenne ne compte que les sorties d'au moins
     * [minAvgDistanceM] : une descente de deux kilomètres n'est pas un record de vitesse.
     */
    fun records(rides: List<RideSummary>, minAvgDistanceM: Double = 10_000.0): List<Record> {
        val out = ArrayList<Record>(6)
        fun best(kind: RecordKind, candidates: List<RideSummary>, value: (RideSummary) -> Double?) {
            val top = candidates.mapNotNull { ride -> value(ride)?.let { ride to it } }.filter { it.second > 0.0 }.maxByOrNull { it.second } ?: return
            out.add(Record(kind, top.second, top.first.id, top.first.startTimeMs))
        }
        best(RecordKind.DISTANCE, rides) { it.distanceM }
        best(RecordKind.ASCENT, rides) { it.ascentM }
        best(RecordKind.DURATION, rides) { it.movingMs.toDouble() }
        best(RecordKind.AVG_SPEED, rides.filter { it.distanceM >= minAvgDistanceM }) { it.avgSpeedMps }
        best(RecordKind.MAX_SPEED, rides) { it.maxSpeedMps }
        best(RecordKind.ALTITUDE, rides) { it.maxAltitudeM }
        return out
    }
}
