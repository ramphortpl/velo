package fr.velogo.app.location

import android.Manifest
import android.app.Application
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.GnssStatusBuilder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * La source de positions face au `LocationManager` d'Android simulé par Robolectric, sur trois versions
 * d'Android : la plus ancienne que l'appli accepte (8.0), la dernière d'avant le fournisseur « fused » (11) et
 * celle du téléphone de référence (15). Les appels à Android ne sont pas les mêmes d'une version à l'autre :
 * c'est le seul moyen de les dérouler tous sans avoir ces téléphones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 35])
class GpsSourceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = app.getSystemService(LocationManager::class.java)
    private val shadow get() = shadowOf(manager)
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + SupervisorJob())
    private lateinit var gps: GpsSource
    private var listening: Job? = null

    /** Tout ce que reçoit la carte ([positions]) et ce que reçoivent l'enregistrement et le guidage ([fixes]). */
    private val positions = CopyOnWriteArrayList<GpsFix>()
    private val fixes = CopyOnWriteArrayList<GpsFix>()
    private val commands = CopyOnWriteArrayList<String>()

    /** D'où vient la position approchée sur cette version d'Android. */
    private val assist = if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER

    private val corte = 42.3063 to 9.1500

    @After
    fun tearDown() {
        scope.cancel()
        if (::gps.isInitialized) idle()
    }

    private fun listen(
        vararg permissions: String = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        gpsPresent: Boolean = true,
    ) {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, assist)) shadow.setProviderEnabled(provider, true)
        if (!gpsPresent) shadow.removeProvider(LocationManager.GPS_PROVIDER)
        if (permissions.isNotEmpty()) shadowOf(app).grantPermissions(*permissions)
        gps = GpsSource(app, scope)
        gps.onCommand = { commands += it }
        listening = scope.launch {
            launch { gps.positions.collect { positions += it } }
            launch { gps.fixes.collect { fixes += it } }
        }
        idle()
    }

    /** Laisse le fil du GPS traiter ce qui l'attend. */
    private fun idle() = shadowOf(gps.looper).idle()

    /** Fait passer le temps du téléphone, seconde par seconde comme en vrai. */
    private fun wait(seconds: Int) = repeat(seconds) { shadowOf(gps.looper).idleFor(Duration.ofSeconds(1)) }

    private fun location(provider: String, accuracyM: Float?, dLat: Double = 0.0, altitudeM: Double? = null) = Location(provider).apply {
        latitude = corte.first + dLat
        longitude = corte.second
        if (accuracyM != null) accuracy = accuracyM
        if (altitudeM != null) altitude = altitudeM
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun send(provider: String, accuracyM: Float? = 4f, dLat: Double = 0.0, altitudeM: Double? = null) {
        shadow.simulateLocation(provider, location(provider, accuracyM, dLat, altitudeM))
        idle()
    }

    private fun listeners(provider: String) = shadow.getLocationUpdateListeners(provider)

    // --- Le GPS seul, comme avant --------------------------------------------------------------------------

    @Test
    fun `une position du GPS arrive a la carte et a l enregistrement`() {
        listen()
        assertEquals(LocationAccess.PRECISE, gps.access())
        assertEquals(1, listeners(LocationManager.GPS_PROVIDER).size)
        assertTrue(gps.status.value.listening)
        assertFalse(gps.status.value.hasFix)

        wait(1)
        send(LocationManager.GPS_PROVIDER, accuracyM = 5f)

        assertEquals(1, positions.size)
        assertEquals(1, fixes.size)
        val fix = fixes.single()
        assertFalse(fix.approximate)
        assertEquals(corte.first, fix.fix.lat, 1e-9)
        assertEquals(5.0, fix.fix.accuracyM!!, 1e-6)
        assertEquals(SystemClock.elapsedRealtime(), fix.fix.monoMs)
        assertTrue(gps.status.value.hasFix)
        assertEquals(5.0, gps.status.value.accuracyM!!, 1e-6)
        assertNull(gps.status.value.searchingS)
        assertEquals(fix, gps.last)
    }

    @Test
    fun `GPS pret tout de suite - la position approchee n est jamais montree et n est plus demandee`() {
        listen()
        // Au début, on écoute les deux : le GPS, et la position approchée au cas où il tarderait.
        assertEquals(1, listeners(assist).size)
        wait(1)
        send(assist, accuracyM = 30f)
        assertTrue("rien pendant le délai laissé au GPS", positions.isEmpty())
        wait(1)
        send(LocationManager.GPS_PROVIDER)
        assertEquals(1, positions.size)
        assertFalse(positions.single().approximate)
        // Le GPS répond : on cesse de demander la position approchée, et celle qui attendait est oubliée.
        assertTrue(listeners(assist).isEmpty())
        repeat(10) {
            wait(1)
            send(LocationManager.GPS_PROVIDER, dLat = it * 1e-5)
        }
        assertEquals(11, positions.size)
        assertTrue(positions.none { it.approximate })
        assertEquals(11, fixes.size)
    }

    // --- La position approchée, en attendant les satellites --------------------------------------------------

    @Test
    fun `GPS muet - la carte recoit la position approchee et l enregistrement rien`() {
        listen()
        wait(2)
        send(assist, accuracyM = 30f)
        assertTrue(positions.isEmpty())
        // Le délai laissé au GPS est écoulé : la position reçue entre-temps s'affiche.
        wait(3)
        assertEquals(1, positions.size)
        val shown = positions.single()
        assertTrue(shown.approximate)
        assertEquals(30.0, shown.fix.accuracyM!!, 1e-6)
        assertNull(shown.courseDeg)
        assertFalse(shown.location.hasBearing())
        assertTrue("l'enregistrement et le guidage ne voient que le GPS", fixes.isEmpty())

        val status = gps.status.value
        assertFalse(status.hasFix)
        assertTrue(status.hasApproximate)
        assertEquals(30.0, status.approximateAccuracyM!!, 1e-6)
        assertEquals(5, status.searchingS)
        assertEquals(shown, gps.last)

        // Les suivantes arrivent directement.
        wait(4)
        send(assist, accuracyM = 25f, dLat = 1e-4)
        assertEquals(2, positions.size)
        assertTrue(fixes.isEmpty())

        // Les satellites sont trouvés : le GPS reprend la main.
        wait(1)
        send(LocationManager.GPS_PROVIDER, accuracyM = 6f)
        assertEquals(3, positions.size)
        assertFalse(positions.last().approximate)
        assertEquals(1, fixes.size)
        assertTrue(gps.status.value.hasFix)
        assertFalse(gps.status.value.hasApproximate)
        assertTrue(listeners(assist).isEmpty())
    }

    @Test
    fun `GPS perdu en route - la position approchee ne revient qu apres trente secondes et si elle est fine`() {
        listen()
        repeat(5) {
            wait(1)
            send(LocationManager.GPS_PROVIDER)
        }
        assertEquals(5, fixes.size)
        // Six secondes de silence : l'écran ne dit plus que le GPS fonctionne.
        wait(7)
        assertFalse(gps.status.value.hasFix)
        assertEquals(7, gps.status.value.searchingS)
        assertTrue(listeners(assist).isEmpty())
        // Trente secondes : on redemande la position approchée.
        wait(24)
        assertEquals(1, listeners(assist).size)
        // Grossière (une antenne à un kilomètre) : ignorée, la carte ne saute pas.
        send(assist, accuracyM = 900f)
        assertEquals(5, positions.size)
        // Fine (Wi-Fi) : montrée.
        wait(4)
        send(assist, accuracyM = 35f)
        assertEquals(6, positions.size)
        assertTrue(positions.last().approximate)
        assertEquals(5, fixes.size)
    }

    @Test
    fun `position approchee perimee ressortie par le telephone - ignoree`() {
        listen()
        wait(200)
        val old = location(assist, 30f).apply { elapsedRealtimeNanos = (SystemClock.elapsedRealtime() - 150_000) * 1_000_000 }
        shadow.simulateLocation(assist, old)
        idle()
        wait(2)
        assertTrue(positions.isEmpty())
        assertFalse(gps.status.value.hasApproximate)
        // Une position fraîche, elle, s'affiche.
        wait(4)
        send(assist, accuracyM = 30f)
        assertEquals(1, positions.size)
    }

    @Test
    fun `position approchee sans precision annoncee - ignoree`() {
        listen()
        wait(6)
        send(assist, accuracyM = null)
        wait(2)
        assertTrue(positions.isEmpty())
    }

    // --- Autorisations ----------------------------------------------------------------------------------------

    @Test
    fun `sans autorisation rien n est ecoute`() {
        listen(permissions = emptyArray())
        assertEquals(LocationAccess.NONE, gps.access())
        assertFalse(gps.hasPermission())
        assertTrue(listeners(LocationManager.GPS_PROVIDER).isEmpty())
        assertTrue(listeners(assist).isEmpty())
        assertFalse(gps.status.value.listening)
        assertNull(gps.lastKnown())
    }

    @Test
    fun `autorisation approximative seule - la position approchee s affiche tout de suite et le GPS n est pas demande`() {
        listen(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(LocationAccess.APPROXIMATE, gps.access())
        assertFalse(gps.hasPermission())
        assertTrue(listeners(LocationManager.GPS_PROVIDER).isEmpty())
        assertEquals(1, listeners(assist).size)
        wait(1)
        send(assist, accuracyM = 2_000f)
        assertEquals(1, positions.size)
        assertTrue(positions.single().approximate)
        assertTrue(fixes.isEmpty())
        assertTrue(gps.status.value.hasApproximate)
        assertNull(gps.status.value.searchingS)
    }

    @Test
    fun `l autorisation exacte accordee ensuite allume le GPS`() {
        listen(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(listeners(LocationManager.GPS_PROVIDER).isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gps.onPermissionChanged()
        idle()
        assertEquals(LocationAccess.PRECISE, gps.access())
        assertEquals(1, listeners(LocationManager.GPS_PROVIDER).size)
        wait(1)
        send(LocationManager.GPS_PROVIDER)
        assertEquals(1, fixes.size)
    }

    // --- Réglages du téléphone --------------------------------------------------------------------------------

    @Test
    fun `GPS coupe alors que la localisation est active - on le dit et la position approchee sert quand meme`() {
        listen()
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        idle()
        wait(5)
        assertFalse(gps.isGpsEnabled())
        assertTrue(gps.isGpsPresent())
        assertTrue(gps.isLocationEnabled())
        val status = gps.status.value
        assertTrue(status.locationEnabled)
        assertTrue(status.gpsPresent)
        assertFalse(status.gpsEnabled)
        send(assist, accuracyM = 40f)
        assertEquals(1, positions.size)
        assertTrue(positions.single().approximate)

        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        idle()
        wait(5)
        assertTrue(gps.status.value.gpsEnabled)
    }

    @Test
    fun `appareil sans GPS - on le dit et seule la position approchee arrive`() {
        listen(gpsPresent = false)
        assertFalse(gps.isGpsPresent())
        assertFalse(gps.status.value.gpsPresent)
        assertFalse(gps.status.value.gpsEnabled)
        wait(5)
        send(assist, accuracyM = 60f)
        assertEquals(1, positions.size)
        assertTrue(positions.single().approximate)
        assertTrue(commands.isEmpty())
        wait(30)
        assertTrue("pas d'ordre envoyé à un GPS qui n'existe pas", commands.isEmpty())
    }

    @Test
    fun `GPS pas encore pret au lancement - il est demande des qu il apparait`() {
        listen(gpsPresent = false)
        assertTrue(listeners(LocationManager.GPS_PROVIDER).isEmpty())
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        wait(6)
        assertEquals(1, listeners(LocationManager.GPS_PROVIDER).size)
        assertTrue(gps.status.value.gpsPresent)
        send(LocationManager.GPS_PROVIDER)
        assertEquals(1, fixes.size)
    }

    @Test
    fun `satellites captes et utilises`() {
        listen()
        assertNull(gps.status.value.satellitesSeen)
        // Quatre satellites connus : deux servent au calcul, un troisième est reçu, le dernier ne l'est pas.
        val satellites = listOf(Triple(3, 32f, true), Triple(7, 28f, true), Triple(11, 19f, false), Triple(14, 0f, false))
        val status = if (Build.VERSION.SDK_INT >= 30) {
            GnssStatus.Builder().apply {
                for ((id, cn0, used) in satellites) {
                    addSatellite(GnssStatus.CONSTELLATION_GPS, id, cn0, 40f, 120f, used, true, used, false, 0f, false, 0f)
                }
            }.build()
        } else {
            GnssStatusBuilder.buildFrom(
                *satellites.map { (id, cn0, used) ->
                    GnssStatusBuilder.GnssSatelliteInfo.builder()
                        .setConstellation(GnssStatus.CONSTELLATION_GPS).setSvid(id).setCn0DbHz(cn0)
                        .setElevation(40f).setAzimuth(120f).setHasEphemeris(used).setHasAlmanac(true).setUsedInFix(used).build()
                }.toTypedArray()
            )
        }
        shadow.simulateGnssStatus(status)
        idle()
        assertEquals(3, gps.status.value.satellitesSeen)
        assertEquals(2, gps.status.value.satellitesUsed)
    }

    // --- Aide au GPS ------------------------------------------------------------------------------------------

    @Test
    fun `apres vingt secondes de recherche le telephone est prie d aider le GPS`() {
        listen()
        wait(19)
        assertTrue(commands.isEmpty())
        wait(2)
        assertEquals(listOf("force_time_injection", "force_psds_injection", "force_xtra_injection"), commands.toList())
        // Pas de harcèlement : rien de plus pendant les minutes qui suivent.
        wait(120)
        assertEquals(3, commands.size)
    }

    @Test
    fun `GPS qui repond - aucune aide demandee`() {
        listen()
        repeat(40) {
            wait(1)
            send(LocationManager.GPS_PROVIDER, dLat = it * 1e-5)
        }
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `relancer le GPS - aide immediate et nouvelle ecoute`() {
        listen()
        wait(3)
        val before = listeners(LocationManager.GPS_PROVIDER).single()
        gps.kick()
        idle()
        assertEquals(listOf("force_time_injection", "force_psds_injection", "force_xtra_injection"), commands.toList())
        val after = listeners(LocationManager.GPS_PROVIDER).single()
        assertTrue("l'écoute a été refaite", before !== after)
        assertEquals(0, gps.status.value.searchingS)
        wait(1)
        send(LocationManager.GPS_PROVIDER)
        assertEquals(1, fixes.size)
    }

    @Test
    fun `reinitialiser le GPS - il oublie tout puis recoit l aide`() {
        listen()
        wait(3)
        gps.kick(reset = true)
        idle()
        assertEquals(listOf("delete_aiding_data", "force_time_injection", "force_psds_injection", "force_xtra_injection"), commands.toList())
        // La relance suivante, ordinaire, ne réinitialise pas de nouveau.
        commands.clear()
        gps.kick()
        idle()
        assertFalse(commands.contains("delete_aiding_data"))
    }

    // --- Horloges ---------------------------------------------------------------------------------------------

    @Test
    fun `instant fantaisiste annonce par la puce GPS - on prend celui de la reception`() {
        listen()
        wait(30)
        val now = SystemClock.elapsedRealtime()
        // Trois heures de retard (puce qui compte mal après une veille), puis une heure d'avance.
        for (offsetMs in listOf(-3 * 3_600_000L, 3_600_000L)) {
            val bogus = location(LocationManager.GPS_PROVIDER, 4f).apply { elapsedRealtimeNanos = (now + offsetMs).coerceAtLeast(1) * 1_000_000 }
            shadow.simulateLocation(LocationManager.GPS_PROVIDER, bogus)
            idle()
        }
        assertTrue(fixes.isNotEmpty())
        for (fix in fixes) assertTrue("${fix.fix.monoMs} vs $now", abs(fix.fix.monoMs - now) <= 1_000)
    }

    @Test
    fun `instant plausible - on garde celui du GPS`() {
        listen()
        wait(30)
        val now = SystemClock.elapsedRealtime()
        val late = location(LocationManager.GPS_PROVIDER, 4f).apply { elapsedRealtimeNanos = (now - 700) * 1_000_000 }
        shadow.simulateLocation(LocationManager.GPS_PROVIDER, late)
        idle()
        assertEquals(now - 700, fixes.single().fix.monoMs)
    }

    // --- Altitude ---------------------------------------------------------------------------------------------

    @Test
    fun `altitude du GPS ramenee au niveau de la mer`() {
        listen()
        wait(1)
        send(LocationManager.GPS_PROVIDER, altitudeM = 500.0)
        val altitude = fixes.single().fix.altitudeM
        assertNotNull(altitude)
        // À Corte, le géoïde est 50,6 m au-dessus de l'ellipsoïde : 500 m « GPS » font 449,4 m au-dessus de la mer.
        // (Le simple repli de l'appli, 48 m de moins partout en France, donnerait 452 m : c'est bien le modèle
        // de géoïde qui a répondu, sur les trois versions d'Android.)
        assertEquals(449.4, altitude!!, 0.5)
        // Une position sans altitude n'en reçoit pas une inventée.
        wait(1)
        send(LocationManager.GPS_PROVIDER)
        assertNull(fixes.last().fix.altitudeM)
    }

    // --- Fin de l'écoute --------------------------------------------------------------------------------------

    @Test
    fun `plus personne n ecoute - tout s eteint`() {
        listen()
        wait(1)
        send(LocationManager.GPS_PROVIDER)
        assertEquals(1, listeners(LocationManager.GPS_PROVIDER).size)
        listening!!.cancel()
        // L'écoute survit trois secondes à son dernier auditeur (changement d'écran), pas plus.
        scheduler.advanceTimeBy(3_100)
        idle()
        assertTrue(listeners(LocationManager.GPS_PROVIDER).isEmpty())
        assertTrue(listeners(assist).isEmpty())
        assertFalse(gps.status.value.listening)
        // La dernière position reste connue, pour la météo.
        assertNotNull(gps.lastKnown())
    }
}
