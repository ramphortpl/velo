package fr.velogo.app.offline

import fr.velogo.app.ui.offline.RegionStatus
import fr.velogo.app.ui.offline.regionRow
import fr.velogo.core.geo.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Ce que l'écran des cartes hors ligne affiche pour une région, selon l'état de ses trois fichiers. */
class RegionStatusTest {

    private fun file(type: String, size: Long, sha: String) = PackFile(type, "$type-corse.pmtiles", "$type-corse-$sha.pmtiles", size, sha)

    private val base = file("fond", 47_000_000, "f1")
    private val bike = file("velo", 2_000_000, "v1")
    private val relief = file("relief", 32_000_000, "r1")
    private val region = RegionPack("corse", "Corse", BoundingBox(41.3, 8.5, 43.0, 9.6), listOf("E5_N40"), listOf(base, bike, relief))

    private fun installed(vararg files: PackFile, sha: (PackFile) -> String = { it.sha256 }) =
        files.associate { it.name to InstalledFile(it.name, File(it.name), sha(it), it.size) }

    private fun progress(file: PackFile, status: DownloadProgress.Status, done: Long = 0, message: String? = null) =
        file.name to DownloadProgress(file.name, status, done, file.size, message)

    @Test
    fun `region absente puis installee`() {
        assertEquals(RegionStatus.AVAILABLE, regionRow(region, emptyMap(), emptyMap()).status)
        val row = regionRow(region, installed(base, bike, relief), emptyMap())
        assertEquals(RegionStatus.INSTALLED, row.status)
        assertEquals(81_000_000L, row.sizeBytes)
    }

    @Test
    fun `l'avancement additionne les fichiers finis et ceux en cours`() {
        val row = regionRow(
            region, installed(bike),
            mapOf(progress(base, DownloadProgress.Status.RUNNING, 10_000_000), progress(relief, DownloadProgress.Status.QUEUED)),
        )
        assertEquals(RegionStatus.DOWNLOADING, row.status)
        assertEquals(12_000_000L, row.downloadedBytes)
    }

    @Test
    fun `attente du wifi verification et echec`() {
        val waiting = regionRow(region, emptyMap(), mapOf(progress(base, DownloadProgress.Status.WAITING, message = "En attente du Wi-Fi")))
        assertEquals(RegionStatus.WAITING, waiting.status)
        assertEquals("En attente du Wi-Fi", waiting.message)

        val verifying = regionRow(region, installed(bike, relief), mapOf(progress(base, DownloadProgress.Status.VERIFYING, base.size)))
        assertEquals(RegionStatus.VERIFYING, verifying.status)

        val failed = regionRow(region, installed(bike), mapOf(progress(base, DownloadProgress.Status.FAILED, message = "Plus assez de place sur le téléphone.")))
        assertEquals(RegionStatus.FAILED, failed.status)
        assertEquals("Plus assez de place sur le téléphone.", failed.message)
    }

    @Test
    fun `une nouvelle version du fond propose une mise a jour de sa seule taille`() {
        val row = regionRow(region, installed(base, bike, relief) { if (it == base) "ancienne" else it.sha256 }, emptyMap())
        assertEquals(RegionStatus.UPDATE_AVAILABLE, row.status)
        assertEquals(47_000_000L, row.updateBytes)
    }
}
