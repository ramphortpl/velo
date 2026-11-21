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

    /** « aujourd'hui », « hier » ou « le 12 novembre » */
    fun relativeDay(ms: Long): String {
        val date = at(ms).toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> "aujourd'hui"
            today.minusDays(1) -> "hier"
            else -> "le " + date.format(dayMonth)
        }
    }

    /** « Novembre 2026 » */
    fun month(ms: Long): String = at(ms).format(month).replaceFirstChar { it.uppercase(Locale.FRANCE) }

    /** Clé de regroupement par mois (année × 100 + mois). */
    fun monthKey(ms: Long): Int = at(ms).let { it.year * 100 + it.monthValue }

    /** « 2026-11-20_14h05 » pour les noms de fichiers. */
    fun fileStamp(ms: Long): String = at(ms).format(fileStamp)
}
