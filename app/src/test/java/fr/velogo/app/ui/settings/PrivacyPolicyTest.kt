package fr.velogo.app.ui.settings

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** La politique de confidentialité : le texte du dépôt est bien celui que l'appli embarque et sait afficher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivacyPolicyTest {

    /** Le fichier du dépôt, d'où que les tests soient lancés (racine du projet ou dossier du module). */
    private val repositoryFile: File =
        listOf(File("docs/CONFIDENTIALITE.md"), File("../docs/CONFIDENTIALITE.md")).first { it.isFile }

    @Test
    fun `lecture - titres, paragraphes et listes`() {
        val blocks = parsePolicy(
            """
            # Titre

            Première ligne
            suite de la même phrase.

            ## Rubrique
            - un
            - deux

            Fin.
            """.trimIndent()
        )
        assertEquals(
            listOf(
                PolicyBlock.Title("Titre"),
                PolicyBlock.Paragraph("Première ligne suite de la même phrase."),
                PolicyBlock.Heading("Rubrique"),
                PolicyBlock.Bullet("un"),
                PolicyBlock.Bullet("deux"),
                PolicyBlock.Paragraph("Fin."),
            ),
            blocks,
        )
    }

    @Test
    fun `le texte du depot se lit sans reste de mise en forme`() {
        val blocks = parsePolicy(repositoryFile.readText())
        assertEquals("un seul titre", 1, blocks.count { it is PolicyBlock.Title })
        assertTrue(blocks.first() is PolicyBlock.Title)
        val headings = blocks.filterIsInstance<PolicyBlock.Heading>().map { it.text }
        // Les rubriques que Google Play demande d'y trouver : données, partage, conservation, contact.
        for (wanted in listOf("Ta position", "Ce qui sort du téléphone, et vers qui", "Conservation et suppression", "Contact")) {
            assertTrue("rubrique « $wanted »", wanted in headings)
        }
        val texts = blocks.map {
            when (it) {
                is PolicyBlock.Title -> it.text
                is PolicyBlock.Heading -> it.text
                is PolicyBlock.Paragraph -> it.text
                is PolicyBlock.Bullet -> it.text
            }
        }
        // L'afficheur de l'appli ne connaît que les titres, les paragraphes et les listes.
        for (text in texts) {
            for (mark in listOf("**", "](", "`", "<", "|")) assertFalse("« $mark » dans : $text", mark in text)
            assertFalse(text.startsWith("#"))
        }
        assertTrue(texts.any { "Open-Meteo" in it } && texts.any { "IGN" in it } && texts.any { "GitHub" in it })
    }

    @Test
    fun `l appli embarque le texte du depot`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bundled = context.assets.open(POLICY_ASSET).bufferedReader().use { it.readText() }
        assertEquals(repositoryFile.readText(), bundled)
    }
}
