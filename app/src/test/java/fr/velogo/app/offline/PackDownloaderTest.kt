package fr.velogo.app.offline

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.File

/**
 * Ce que l'appli dit d'un téléchargement de carte qu'Android n'a pas encore commencé, et ce qu'elle fait quand on
 * change « Wi-Fi uniquement » pendant qu'il attend.
 *
 * Le gestionnaire de téléchargements et le réseau sont ceux, simulés, de Robolectric : aucun fichier n'est
 * réellement téléchargé.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackDownloaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val directory = File(context.cacheDir, "cartes")

    private val base = PackFile(PackFile.BASE, "fond-corse.pmtiles", "fond-corse-f1.pmtiles", 47_000_000, "f1")
    private val relief = PackFile(PackFile.RELIEF, "relief-corse.pmtiles", "relief-corse-r1.pmtiles", 32_000_000, "r1")

    @After
    fun stop() {
        scope.cancel()
    }

    @Suppress("DEPRECATION") // Les types de réseau de NetworkInfo : c'est par eux que Robolectric simule le réseau.
    private companion object {
        const val MOBILE = ConnectivityManager.TYPE_MOBILE
        const val WIFI = ConnectivityManager.TYPE_WIFI
    }

    private fun downloader() = PackDownloader(context, OfflineStore(context, scope))

    /** Le réseau du téléphone : Wi-Fi, données mobiles (en France ou à l'étranger), ou rien du tout (null). */
    @Suppress("DEPRECATION") // NetworkInfo : c'est par lui que Robolectric décrit le réseau simulé.
    private fun network(type: Int?, roaming: Boolean = false) {
        shadowOf(connectivity).setActiveNetworkInfo(
            type?.let { ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, it, 0, true, NetworkInfo.State.CONNECTED) }
        )
        val active = connectivity.activeNetwork ?: return
        // Comme sur un vrai téléphone : un réseau qui n'est pas en itinérance le dit dans ses caractéristiques.
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!roaming) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        shadowOf(connectivity).setNetworkCapabilities(active, capabilities)
    }

    /** Les demandes que le gestionnaire de téléchargements a encore, avec leur numéro (le simulé compte à partir de 0). */
    private fun requests(): Map<Long, DownloadManager.Request> =
        (0L..20L).mapNotNull { id -> shadowOf(downloads).getRequest(id)?.let { id to it } }.toMap()

    private fun requestFor(file: PackFile): Pair<Long, DownloadManager.Request> =
        requests().entries.single { shadowOf(it.value).uri.toString().endsWith(file.remoteName) }.toPair()

    @Test
    fun `la raison de l'attente se lit dans le reseau`() {
        val wifi = NetworkState(connected = true)
        val mobile = NetworkState(connected = true, metered = true)
        assertNull(pendingReason(wifiOnly = true, wifi))
        assertEquals(WAITING_FOR_WIFI, pendingReason(wifiOnly = true, mobile))
        // Forfait autorisé : rien ne retient le téléchargement.
        assertNull(pendingReason(wifiOnly = false, mobile))
        assertEquals(WAITING_FOR_NETWORK, pendingReason(wifiOnly = false, NetworkState(connected = false)))
        assertEquals(WAITING_FOR_NETWORK, pendingReason(wifiOnly = true, NetworkState(connected = false)))
        // À l'étranger, Android ne télécharge rien, forfait autorisé ou non ; le Wi-Fi reste le premier conseil.
        val abroad = NetworkState(connected = true, metered = true, roaming = true)
        assertEquals(WAITING_ROAMING, pendingReason(wifiOnly = false, abroad))
        assertEquals(WAITING_FOR_WIFI, pendingReason(wifiOnly = true, abroad))
        // Réseau inconnu : on ne prétend rien.
        assertNull(pendingReason(wifiOnly = true, NetworkState.UNKNOWN))
    }

    @Test
    fun `en donnees mobiles un telechargement reserve au wifi dit qu'il l'attend`() = runBlocking {
        network(MOBILE)
        val downloader = downloader()
        assertTrue(downloader.enqueue(listOf(base), directory, wifiOnly = true) { "VeloGo · Corse · fond de carte" })

        val waiting = downloader.progress.value.getValue(base.name)
        assertEquals(DownloadProgress.Status.WAITING, waiting.status)
        assertEquals(WAITING_FOR_WIFI, waiting.message)
        assertFalse("la demande faite à Android doit refuser le forfait", shadowOf(requestFor(base).second).allowedOverMetered)

        // Le Wi-Fi revient : plus rien ne le retient, il « va démarrer ».
        network(WIFI)
        assertTrue(downloader.sync())
        val queued = downloader.progress.value.getValue(base.name)
        assertEquals(DownloadProgress.Status.QUEUED, queued.status)
        assertNull(queued.message)
    }

    @Test
    fun `sans aucun reseau le telechargement attend le reseau`() = runBlocking {
        network(null)
        val downloader = downloader()
        downloader.enqueue(listOf(base), directory, wifiOnly = false) { "VeloGo · Corse · fond de carte" }
        val waiting = downloader.progress.value.getValue(base.name)
        assertEquals(DownloadProgress.Status.WAITING, waiting.status)
        assertEquals(WAITING_FOR_NETWORK, waiting.message)
    }

    @Test
    fun `a l'etranger rien ne part meme forfait autorise`() = runBlocking {
        network(MOBILE, roaming = true)
        val downloader = downloader()
        downloader.enqueue(listOf(base), directory, wifiOnly = false) { "VeloGo · Corse · fond de carte" }
        val waiting = downloader.progress.value.getValue(base.name)
        assertEquals(DownloadProgress.Status.WAITING, waiting.status)
        assertEquals(WAITING_ROAMING, waiting.message)
        assertFalse(shadowOf(requestFor(base).second).allowedOverRoaming)
    }

    @Test
    fun `autoriser le forfait relance ce qui attendait le wifi`() = runBlocking {
        network(MOBILE)
        downloader().enqueue(listOf(base, relief), directory, wifiOnly = true) { file ->
            "VeloGo · Corse · " + if (file.type == PackFile.BASE) "fond de carte" else "relief"
        }
        val (oldBase, baseRequest) = requestFor(base)
        val (oldRelief, reliefRequest) = requestFor(relief)
        // Le fond de carte n'a rien reçu ; le relief avait commencé en Wi-Fi avant de le perdre.
        shadowOf(baseRequest).status = DownloadManager.STATUS_PENDING
        shadowOf(reliefRequest).status = DownloadManager.STATUS_PAUSED
        shadowOf(reliefRequest).bytesSoFar = 5_000_000

        // Une appli rouverte entre-temps : elle ne connaît ces téléchargements que par ce qu'elle en a noté.
        val downloader = downloader()
        downloader.applyWifiOnly(false)

        val (newBase, replaced) = requestFor(base)
        assertTrue("le téléchargement qui n'avait pas commencé doit être redemandé", newBase != oldBase)
        assertTrue("redemandé, il doit accepter le forfait", shadowOf(replaced).allowedOverMetered)
        assertEquals("VeloGo · Corse · fond de carte", shadowOf(replaced).title.toString())
        // Celui qui avait commencé n'est pas touché : le redemander ferait perdre ce qui est arrivé.
        assertEquals(oldRelief, requestFor(relief).first)
        assertFalse(shadowOf(requestFor(relief).second).allowedOverMetered)
        assertEquals(2, requests().size)

        val progress = downloader.progress.value
        assertEquals(DownloadProgress.Status.QUEUED, progress.getValue(base.name).status)
        assertEquals(DownloadProgress.Status.WAITING, progress.getValue(relief.name).status)

        // Le même réglage redonné ne redemande rien.
        downloader.applyWifiOnly(false)
        assertEquals(newBase, requestFor(base).first)
    }

    @Test
    fun `revenir au wifi uniquement retient ce qui n'a pas commence`() = runBlocking {
        network(MOBILE)
        val downloader = downloader()
        downloader.enqueue(listOf(base), directory, wifiOnly = false) { "VeloGo · Corse · fond de carte" }
        assertEquals(DownloadProgress.Status.QUEUED, downloader.progress.value.getValue(base.name).status)
        shadowOf(requestFor(base).second).status = DownloadManager.STATUS_PENDING

        downloader.applyWifiOnly(true)

        assertFalse(shadowOf(requestFor(base).second).allowedOverMetered)
        val waiting = downloader.progress.value.getValue(base.name)
        assertEquals(DownloadProgress.Status.WAITING, waiting.status)
        assertEquals(WAITING_FOR_WIFI, waiting.message)
    }
}
