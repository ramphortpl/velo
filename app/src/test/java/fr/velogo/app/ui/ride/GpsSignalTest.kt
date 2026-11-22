package fr.velogo.app.ui.ride

import fr.velogo.app.location.GpsStatus
import fr.velogo.app.location.LocationAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ce que l'écran dit de l'état du GPS : la pastille et la feuille « Signal GPS ». */
class GpsSignalTest {
    private val precise = LocationAccess.PRECISE
    private val listening = GpsStatus(listening = true)

    @Test
    fun `pastille - GPS qui fonctionne`() {
        assertEquals(GpsChipModel(GpsTone.GOOD, "GPS ±4 m"), gpsChip(precise, listening.copy(hasFix = true, accuracyM = 4.2)))
        assertEquals(GpsChipModel(GpsTone.GOOD, "GPS ±15 m"), gpsChip(precise, listening.copy(hasFix = true, accuracyM = 15.0)))
        // Au-delà de 15 m, orange : utilisable, mais on le signale.
        assertEquals(GpsChipModel(GpsTone.WEAK, "GPS ±22 m"), gpsChip(precise, listening.copy(hasFix = true, accuracyM = 22.0)))
        // Au-delà de 35 m, rouge : le moteur de sortie n'enregistre plus ces positions.
        assertEquals(GpsChipModel(GpsTone.BAD, "GPS ±48 m"), gpsChip(precise, listening.copy(hasFix = true, accuracyM = 48.0)))
        // Un GPS qui ne dit pas sa précision fonctionne quand même.
        assertEquals(GpsChipModel(GpsTone.GOOD, "GPS"), gpsChip(precise, listening.copy(hasFix = true)))
    }

    @Test
    fun `pastille - recherche des satellites`() {
        // Rien du tout : rouge.
        assertEquals(GpsChipModel(GpsTone.BAD, "Recherche GPS"), gpsChip(precise, listening))
        assertEquals(GpsChipModel(GpsTone.BAD, "Recherche GPS"), gpsChip(precise, GpsStatus()))
        // La carte montre une position approchée : orange.
        assertEquals(GpsChipModel(GpsTone.WEAK, "Recherche GPS"), gpsChip(precise, listening.copy(hasApproximate = true, approximateAccuracyM = 40.0)))
    }

    @Test
    fun `pastille - ce qui empeche le GPS de fonctionner`() {
        assertEquals(GpsChipModel(GpsTone.BAD, "GPS coupé"), gpsChip(precise, listening.copy(locationEnabled = false)))
        assertEquals(GpsChipModel(GpsTone.BAD, "GPS coupé"), gpsChip(precise, listening.copy(gpsEnabled = false, hasApproximate = true)))
        assertEquals(GpsChipModel(GpsTone.BAD, "Pas de GPS"), gpsChip(precise, listening.copy(gpsPresent = false, gpsEnabled = false)))
        assertEquals(GpsChipModel(GpsTone.WEAK, "Approximatif"), gpsChip(LocationAccess.APPROXIMATE, listening.copy(hasApproximate = true)))
        // La localisation coupée passe avant tout le reste.
        assertEquals(GpsChipModel(GpsTone.BAD, "GPS coupé"), gpsChip(LocationAccess.APPROXIMATE, listening.copy(locationEnabled = false)))
    }

    @Test
    fun `feuille - localisation coupee`() {
        val sheet = gpsSheet(precise, listening.copy(locationEnabled = false))
        assertEquals("La localisation du téléphone est coupée", sheet.title)
        assertEquals(GpsRemedy.LOCATION_SETTINGS, sheet.remedy)
        assertEquals("Activer la localisation", sheet.remedyLabel)
        assertFalse(sheet.canReset)
    }

    @Test
    fun `feuille - position approximative seulement`() {
        val sheet = gpsSheet(LocationAccess.APPROXIMATE, listening.copy(hasApproximate = true, approximateAccuracyM = 2_000.0))
        assertEquals("Position approximative seulement", sheet.title)
        assertEquals(GpsRemedy.ALLOW_PRECISE, sheet.remedy)
        assertEquals(listOf(GpsLine("Position approchée", "à 2\u00A0km près")), sheet.lines)
        // Un nombre rond de kilomètres s'écrit sans décimale, les autres avec une seule.
        val other = gpsSheet(LocationAccess.APPROXIMATE, listening.copy(hasApproximate = true, approximateAccuracyM = 1_540.0))
        assertEquals("à 1,5\u00A0km près", other.lines.single().value)
    }

    @Test
    fun `feuille - GPS coupe alors que la localisation est active`() {
        val sheet = gpsSheet(precise, listening.copy(gpsEnabled = false))
        assertEquals("Le GPS du téléphone est coupé", sheet.title)
        assertTrue(sheet.text.contains("Haute précision"))
        assertEquals(GpsRemedy.LOCATION_SETTINGS, sheet.remedy)
        assertEquals("Ouvrir les réglages de localisation", sheet.remedyLabel)
    }

    @Test
    fun `feuille - appareil sans GPS`() {
        val sheet = gpsSheet(precise, listening.copy(gpsPresent = false, gpsEnabled = false))
        assertEquals("Cet appareil n'a pas de GPS", sheet.title)
        assertNull(sheet.remedy)
    }

    @Test
    fun `feuille - GPS qui fonctionne`() {
        val good = gpsSheet(precise, listening.copy(hasFix = true, accuracyM = 4.0, satellitesSeen = 19, satellitesUsed = 12))
        assertEquals(GpsTone.GOOD, good.tone)
        assertEquals("Le GPS fonctionne bien", good.title)
        assertEquals("Ta position est connue à 4\u00A0m près.", good.text)
        assertEquals(listOf(GpsLine("Satellites", "19 captés, 12 utilisés")), good.lines)
        assertNull(good.remedy)

        val medium = gpsSheet(precise, listening.copy(hasFix = true, accuracyM = 22.0))
        assertEquals(GpsTone.WEAK, medium.tone)
        assertEquals("Signal GPS moyen", medium.title)
        assertTrue(medium.lines.isEmpty())

        val weak = gpsSheet(precise, listening.copy(hasFix = true, accuracyM = 48.0, satellitesSeen = 1, satellitesUsed = 1))
        assertEquals(GpsTone.BAD, weak.tone)
        assertEquals("Signal GPS trop faible", weak.title)
        assertTrue(weak.text, weak.text.contains("48\u00A0m près") && weak.text.contains("il faut 35\u00A0m ou mieux"))
        assertEquals(listOf(GpsLine("Satellites", "1 capté, 1 utilisé")), weak.lines)

        assertEquals("Le GPS fonctionne", gpsSheet(precise, listening.copy(hasFix = true)).title)
    }

    @Test
    fun `feuille - recherche sans aucune position`() {
        val sheet = gpsSheet(precise, listening.copy(satellitesSeen = 0, satellitesUsed = 0, searchingS = 45))
        assertEquals(GpsTone.BAD, sheet.tone)
        assertEquals("Le GPS cherche ses satellites", sheet.title)
        assertTrue(sheet.text, sheet.text.startsWith("VeloGo n'a pas encore de position."))
        assertEquals(listOf(GpsLine("Satellites", "aucun capté pour l'instant"), GpsLine("Recherche", "depuis 45 s")), sheet.lines)
        assertEquals(GpsRemedy.RESTART, sheet.remedy)
        assertEquals("Relancer le GPS", sheet.remedyLabel)
        assertFalse(sheet.canReset)
        assertFalse(sheet.tip!!.contains("Réinitialiser"))
    }

    @Test
    fun `feuille - recherche avec une position approchee`() {
        val sheet = gpsSheet(precise, listening.copy(hasApproximate = true, approximateAccuracyM = 38.0, satellitesSeen = 3, satellitesUsed = 0, searchingS = 12))
        assertEquals(GpsTone.WEAK, sheet.tone)
        assertTrue(sheet.text, sheet.text.startsWith("En attendant, la carte montre une position approchée, déduite du Wi-Fi et du réseau (à 38\u00A0m près)."))
        assertTrue(sheet.text, sheet.text.contains("il faut le GPS"))
        assertEquals(listOf(GpsLine("Satellites", "3 captés, 0 utilisé"), GpsLine("Recherche", "depuis 12 s")), sheet.lines)

        // Précision inconnue : on ne l'invente pas.
        val unknown = gpsSheet(precise, listening.copy(hasApproximate = true, searchingS = 12))
        assertTrue(unknown.text, unknown.text.startsWith("En attendant, la carte montre une position approchée, déduite du Wi-Fi et du réseau. "))
    }

    @Test
    fun `feuille - recherche qui dure - la reinitialisation est proposee`() {
        assertFalse(gpsSheet(precise, listening.copy(searchingS = 89)).canReset)
        val long = gpsSheet(precise, listening.copy(searchingS = 150))
        assertTrue(long.canReset)
        assertTrue(long.tip!!.contains("« Réinitialiser le GPS »"))
        assertEquals(GpsLine("Recherche", "depuis 2 min 30 s"), long.lines.single())
        assertEquals(GpsLine("Recherche", "depuis 1 min 05 s"), gpsSheet(precise, listening.copy(searchingS = 65)).lines.single())
    }

    @Test
    fun `feuille - sans information sur les satellites la ligne est omise`() {
        val sheet = gpsSheet(precise, listening.copy(searchingS = 3))
        assertEquals(listOf(GpsLine("Recherche", "depuis 3 s")), sheet.lines)
    }
}
