package fr.velogo.app.weather

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** La mémoire des prévisions : quand le réseau est sollicité, et ce qui reste sans lui. */
class WeatherRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val start = 1_795_208_400_000L // une heure ronde
    private var now = start + 5 * 60_000L
    private var online = true
    private var answer: (List<String>) -> String = { latitudes -> forecast(latitudes) }
    private val requests = ArrayList<okhttp3.HttpUrl>()

    /** Faux serveur : répond sans réseau, à la manière d'Open-Meteo (un objet pour un lieu, un tableau pour plusieurs). */
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val url = chain.request().url
        requests.add(url)
        if (!online) throw IOException("pas de réseau")
        val latitudes = url.queryParameter("latitude")!!.split(",")
        Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(answer(latitudes).toResponseBody("application/json".toMediaType()))
            .build()
    }).build()

    private fun repository() = WeatherRepository(OpenMeteo(http), folder.root) { now }

    private fun place(latitude: String): String {
        val t = start / 1000
        val hours = (0..7).map { t + it * 3600 }
        return """{"latitude": $latitude, "longitude": 9.15,
            "current": {"time": ${now / 1000}, "temperature_2m": 11.5, "weather_code": 2, "is_day": 1},
            "hourly": {"time": $hours, "temperature_2m": ${hours.indices.map { 10.0 + it }}},
            "minutely_15": {"time": [${t + 900}, ${t + 1800}], "precipitation": [0.0, 0.3]}}"""
    }

    private fun forecast(latitudes: List<String>): String =
        if (latitudes.size == 1) place(latitudes[0]) else latitudes.joinToString(",", "[", "]") { place(it) }

    private val corte = LatLon(42.306, 9.150)

    @Test
    fun `le reseau n'est sollicite que si les previsions ont vieilli`() = runBlocking<Unit> {
        val repository = repository()
        val first = repository.here(corte)!!
        assertFalse(first.stale)
        assertEquals(11.5, first.report.current!!.temperatureC!!, 1e-9)
        assertEquals(1, requests.size)
        // La position envoyée est arrondie au centième de degré (un kilomètre environ).
        assertEquals("42.31", requests[0].queryParameter("latitude"))
        assertEquals("9.15", requests[0].queryParameter("longitude"))
        assertEquals("meteofrance_seamless", requests[0].queryParameter("models"))

        // Cinq minutes plus tard, à deux kilomètres de là : rien n'est redemandé.
        now += 5 * 60_000L
        val near = Geo.offset(corte.lat, corte.lon, 90.0, 2_000.0)
        assertFalse(repository.here(near)!!.stale)
        assertEquals(1, requests.size)

        // Au bout de dix minutes, ou après un grand déplacement, ou sur demande : nouvelle requête.
        now += 6 * 60_000L
        repository.here(corte)
        assertEquals(2, requests.size)
        repository.here(Geo.offset(corte.lat, corte.lon, 0.0, 9_000.0))
        assertEquals(3, requests.size)
        repository.here(Geo.offset(corte.lat, corte.lon, 0.0, 9_000.0), force = true)
        assertEquals(4, requests.size)
    }

    @Test
    fun `sans reseau les dernieres previsions restent utilisables`() = runBlocking<Unit> {
        val repository = repository()
        assertNotNull(repository.here(corte))
        online = false
        now += 2 * 3_600_000L
        val later = repository.here(corte)!!
        assertTrue(later.stale)
        // Deux heures après, le « temps qu'il fait » est la prévision de l'heure, pas le relevé d'alors.
        assertEquals(12.0, later.report.now(now)!!.temperatureC!!, 1e-9)

        // Trop loin, ou au-delà de ce que couvrent les prévisions : plus rien.
        assertNull(repository.here(Geo.offset(corte.lat, corte.lon, 0.0, 60_000.0)))
        now += 12 * 3_600_000L
        assertNull(repository.here(corte))
        assertNull(repository.cached(corte))
    }

    @Test
    fun `les previsions survivent a la fermeture de l'appli`() = runBlocking<Unit> {
        repository().here(corte)
        online = false
        now += 30 * 60_000L
        // Nouvelle instance, comme au lancement suivant : le fichier est relu, sans réseau.
        val reopened = repository()
        val cached = reopened.cached(corte)!!
        assertEquals(42.31, cached.lat, 1e-9)
        assertEquals(start + 5 * 60_000L, cached.fetchedAtMs)
        assertEquals(1, requests.size)
        assertTrue(reopened.here(corte)!!.stale)
    }

    @Test
    fun `sans reseau ni memoire il n'y a pas de meteo`() = runBlocking<Unit> {
        online = false
        val repository = repository()
        assertNull(repository.cached(corte))
        assertNull(repository.here(corte))
    }

    @Test
    fun `un refus du service ne fait pas perdre ce qu'on avait`() = runBlocking<Unit> {
        val repository = repository()
        repository.here(corte)
        now += 20 * 60_000L
        answer = { """{"error": true, "reason": "Daily API request limit exceeded. Please try again tomorrow."}""" }
        assertTrue(repository.here(corte)!!.stale)
    }

    @Test
    fun `plusieurs lieux en une seule requete`() = runBlocking<Unit> {
        val repository = repository()
        val points = listOf(corte, LatLon(42.25, 9.17), LatLon(42.21, 9.19))
        val reports = repository.along(points)
        assertEquals(listOf(42.31, 42.25, 42.21), reports.map { it.lat })
        assertEquals(1, requests.size)
        assertEquals("42.31,42.25,42.21", requests[0].queryParameter("latitude"))

        // Une réponse incomplète est refusée plutôt qu'attribuée aux mauvais endroits.
        answer = { latitudes -> forecast(latitudes.take(2)) }
        assertThrows(WeatherException::class.java) { runBlocking { repository.along(points) } }
        online = false
        assertThrows(IOException::class.java) { runBlocking { repository.along(points) } }
    }
}
