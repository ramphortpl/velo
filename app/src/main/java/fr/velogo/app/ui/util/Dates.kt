package fr.velogo.app.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dates et heures en français. */
object Dates {
    private val fullDay = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)
    private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRANCE)
    private val shortDayYear = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRANCE)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", Locale.FRANCE)
    private val numericDay = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)
    private val month = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)
    private val time = DateTimeFormatter.ofPattern("H:mm", Locale.FRANCE)
    private val fileStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH'h'mm", Locale.FRANCE)
    private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.FRANCE)

    private fun at(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    /** « 14:05 » */
    fun time(ms: Long): String = at(ms).format(time)

    /** « 14 h » */
    fun hour(ms: Long): String = "${at(ms).hour} h"

    /** « sam. » */
    fun weekdayShort(ms: Long): String = at(ms).format(weekday)

    /** Numéro du jour dans l'année : sert à repérer le passage au lendemain. */
    fun dayOfYear(ms: Long): Int = at(ms).dayOfYear

    /** « Vendredi 20 novembre 2026 » */
    fun fullDay(ms: Long): String = at(ms).format(fullDay).replaceFirstChar { it.uppercase(Locale.FRANCE) }

    /** « ven. 20 nov. » (avec l'année si ce n'est pas l'année en cours) */
    fun shortDay(ms: Long): String {
        val date = at(ms)
        return date.format(if (date.year == LocalDate.now().year) shortDay else shortDayYear)
    }

    /** « aujourd'hui », « hier », « le 12 novembre » ou, une autre année, « le 12 novembre 2025 » */
    fun relativeDay(ms: Long, today: LocalDate = LocalDate.now()): String {
        val date = at(ms).toLocalDate()
        return when {
            date == today -> "aujourd'hui"
            date == today.minusDays(1) -> "hier"
            date.year == today.year -> "le " + date.format(dayMonth)
            else -> "le " + date.format(dayLongMonthYear)
        }
    }

    /**
     * Le même, en plus court pour une ligne de liste : une autre année, la date s'écrit en chiffres
     * (« le 12/11/2025 »).
     */
    fun relativeDayShort(ms: Long, today: LocalDate = LocalDate.now()): String {
        val date = at(ms).toLocalDate()
        return if (date.year == today.year || date == today.minusDays(1)) relativeDay(ms, today) else "le " + date.format(numericDay)
    }

    /** « Novembre 2026 » */
    fun month(ms: Long): String = at(ms).format(month).replaceFirstChar { it.uppercase(Locale.FRANCE) }

    /** Clé de regroupement par mois (année × 100 + mois). */
    fun monthKey(ms: Long): Int = at(ms).let { it.year * 100 + it.monthValue }

    private val dayShortMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
    private val shortMonth = DateTimeFormatter.ofPattern("MMM", Locale.FRANCE)
    private val longMonthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)
    private val dayLongMonthYear = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)

    /** « 16 nov. » */
    fun dayShortMonth(date: LocalDate): String = date.format(dayShortMonth)

    /** « nov. » */
    fun shortMonth(date: LocalDate): String = date.format(shortMonth)

    /** « Novembre 2026 » */
    fun monthYear(date: LocalDate): String = date.format(longMonthYear).replaceFirstChar { it.uppercase(Locale.FRANCE) }

    /** La semaine qui commence le [monday] : « 16 au 22 novembre 2026 », « 30 novembre au 6 décembre 2026 ». */
    fun weekRange(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        val from = if (monday.month == sunday.month) monday.dayOfMonth.toString() else monday.format(dayMonth)
        return "$from au ${sunday.format(dayLongMonthYear)}"
    }

    /** « 20 novembre 2026 » */
    fun longDay(ms: Long): String = at(ms).format(dayLongMonthYear)

    /** « 2026-11-20_14h05 » pour les noms de fichiers. */
    fun fileStamp(ms: Long): String = at(ms).format(fileStamp)
}
