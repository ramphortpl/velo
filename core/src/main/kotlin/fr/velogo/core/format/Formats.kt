package fr.velogo.core.format

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Mise en forme des nombres à la française (virgule décimale, unités métriques).
 * Écrit « à la main » pour ne dépendre ni du téléphone ni de sa langue.
 */
object Formats {
    const val NO_VALUE = "–"
    private const val MINUS = "−"
    /** Espace fine insécable, séparateur de milliers. */
    private const val THIN_NBSP = ' '

    /** Nombre décimal avec virgule, arrondi à [decimals] décimales. */
    fun decimal(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return NO_VALUE
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = (abs(value) * factor).roundToLong()
        val whole = scaled / factor
        val fraction = scaled % factor
        val sign = if (value < 0 && scaled != 0L) MINUS else ""
        return if (decimals == 0) sign + grouped(whole)
        else sign + grouped(whole) + "," + fraction.toString().padStart(decimals, '0')
    }

    private fun grouped(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 4) return digits
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(THIN_NBSP)
            sb.append(c)
        }
        return sb.toString()
    }

    /** Vitesse en km/h à une décimale, à partir de m/s. */
    fun speedKmh(mps: Double?): String = if (mps == null) NO_VALUE else decimal(mps * 3.6, 1)

    /** Distance en km : deux décimales sous 100 km, une au-delà. */
    fun distanceKm(meters: Double?): String {
        if (meters == null) return NO_VALUE
        val km = meters / 1000.0
        return if (km < 99.995) decimal(km, 2) else decimal(km, 1)
    }

    /** Distance courte et lisible : « 850 m » ou « 12,3 km ». */
    fun distanceAuto(meters: Double): String =
        if (meters < 995) "${decimal(meters, 0)} m" else "${decimal(meters / 1000.0, if (meters < 99_950) 1 else 0)} km"

    /** Altitude ou dénivelé en mètres entiers. */
    fun meters(value: Double?): String = if (value == null) NO_VALUE else decimal(value, 0)

    /** Pente en % à une décimale près (pas de 0,5), avec signe. */
    fun slope(pct: Double?): String {
        if (pct == null) return NO_VALUE
        val rounded = (pct * 2).roundToLong() / 2.0
        if (rounded == 0.0) return "0,0"
        return (if (rounded > 0) "+" else MINUS) + decimal(abs(rounded), 1)
    }

    /** Durée « h:mm:ss » (ou « m:ss » sous une heure). */
    fun duration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "$m:${s.toString().padStart(2, '0')}"
    }

    /** Durée en toutes lettres : « 1 h 05 », « 42 min ». */
    fun durationWords(ms: Long): String {
        val totalMinutes = (ms.coerceAtLeast(0) + 30_000) / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "$h h ${m.toString().padStart(2, '0')}" else "$m min"
    }
}
