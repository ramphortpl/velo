package fr.velogo.app.weather

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.weather.WeatherReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Des prévisions, et si elles viennent d'être récupérées ou sortent de la mémoire faute de réseau. */
data class Forecast(val report: WeatherReport, val stale: Boolean)

/**
 * Les prévisions météo et leur mémoire.
 *
 * Celles du lieu où l'on se trouve sont gardées sur le téléphone : au lancement suivant, ou sans réseau en pleine
 * sortie, on retrouve les dernières connues (des prévisions récupérées il y a deux heures disent encore très bien
 * le temps qu'il fait). Le réseau n'est sollicité que si elles ont plus de dix minutes ou si l'on a bougé de
 * plusieurs kilomètres.
 */
class WeatherRepository(
    private val api: OpenMeteo,
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val position: LatLon, val report: WeatherReport)

    private val mutex = Mutex()
    private var here: Entry? = null
    private var diskRead = false

    /** Dernières prévisions connues près de [position], sans toucher au réseau ; null si elles ne valent plus rien. */
    suspend fun cached(position: LatLon): WeatherReport? = mutex.withLock {
        usable(position, MAX_STALE_DISTANCE_M)?.report
    }

    /** Les prévisions gardées pour [position] si elles sont assez récentes pour ne rien redemander ; null sinon. */
    suspend fun fresh(position: LatLon): WeatherReport? = mutex.withLock { freshEntry(position)?.report }

    private suspend fun freshEntry(position: LatLon): Entry? =
        usable(position, FRESH_DISTANCE_M)?.takeIf { clock() - it.report.fetchedAtMs < FRESH_MS }

    /**
     * Prévisions pour [position] : celles en mémoire si elles sont fraîches, sinon celles du réseau, sinon les
     * dernières connues tant qu'elles couvrent encore l'heure qu'il est. Null s'il n'y a ni réseau ni mémoire.
     */
    suspend fun here(position: LatLon, force: Boolean = false): Forecast? = mutex.withLock {
        val now = clock()
        if (!force) freshEntry(position)?.let { return@withLock Forecast(it.report, stale = false) }
        try {
            val body = api.fetch(listOf(position))
            val report = OpenMeteo.parse(body, now).first()
            here = Entry(position, report)
            save(position, now, body)
            Forecast(report, stale = false)
        } catch (e: IOException) {
            usable(position, MAX_STALE_DISTANCE_M)?.let { Forecast(it.report, stale = true) }
        }
    }

    /** Prévisions pour une série de lieux (le long d'un itinéraire). Lève une [IOException] sans réseau. */
    suspend fun along(points: List<LatLon>): List<WeatherReport> {
        val now = clock()
        val reports = OpenMeteo.parse(api.fetch(points), now)
        if (reports.size != points.size) throw WeatherException("Réponse météo incomplète")
        return reports
    }

    /** Les prévisions gardées, si elles sont assez proches de [position] et couvrent encore l'heure qu'il est. */
    private suspend fun usable(position: LatLon, maxDistanceM: Double): Entry? {
        if (!diskRead) {
            diskRead = true
            if (here == null) here = load()
        }
        val entry = here ?: return null
        if (Geo.distanceM(entry.position, position) > maxDistanceM) return null
        return entry.takeIf { it.report.now(clock()) != null }
    }

    // Fichier : une ligne « latitude;longitude;heure de réception », puis la réponse d'Open-Meteo telle quelle.
    private suspend fun save(position: LatLon, fetchedAtMs: Long, body: String) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val temporary = File(directory, "$FILE.tmp")
            temporary.writeText("${position.lat};${position.lon};$fetchedAtMs\n$body")
            if (!temporary.renameTo(File(directory, FILE))) temporary.delete()
        }
    }

    private suspend fun load(): Entry? = withContext(Dispatchers.IO) {
        runCatching {
            val text = File(directory, FILE).takeIf { it.isFile }?.readText() ?: return@runCatching null
            val header = text.substringBefore('\n').split(';')
            val report = OpenMeteo.parse(text.substringAfter('\n'), header[2].toLong()).first()
            Entry(LatLon(header[0].toDouble(), header[1].toDouble()), report)
        }.getOrNull()
    }

    companion object {
        private const val FILE = "ici.txt"
        /** En dessous de dix minutes, on ne redemande rien : les modèles ne sont pas recalculés plus souvent. */
        const val FRESH_MS = 10 * 60_000L
        private const val FRESH_DISTANCE_M = 4_000.0
        private const val MAX_STALE_DISTANCE_M = 25_000.0
    }
}
