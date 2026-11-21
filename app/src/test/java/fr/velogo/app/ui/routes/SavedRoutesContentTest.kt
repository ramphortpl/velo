package fr.velogo.app.ui.routes

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import fr.velogo.app.ui.theme.ThemeMode
import fr.velogo.app.ui.theme.VeloGoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L'onglet « Trajets » sous le doigt : ouvrir, partager, supprimer (avec confirmation), en préparer un nouveau.
 *
 * Le renommage n'est pas ici : sur l'ordinateur, une fenêtre qui contient un champ de saisie n'est jamais « au
 * repos » pour l'outil de test (vérifié avec une fenêtre vide de tout code de l'appli), et le test n'avance plus.
 * Il est vérifié sur un vrai Android par l'essai sur émulateur, et côté données par `SavedRouteRepositoryTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
class SavedRoutesContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val rows = listOf(
        SavedRouteRow(7, "Tour du lac", "41,8 km", "2 h 03 · ↗ 310 m", "Enregistré aujourd'hui · VTC", null),
        SavedRouteRow(9, "Col de Vizzavona", "63,2 km", "3 h 35 · ↗ 1240 m", "Enregistré hier · Route", null),
    )

    /** Ce que l'écran a demandé, dans l'ordre. */
    private val asked = ArrayList<String>()

    private fun show(list: List<SavedRouteRow>? = rows) {
        compose.setContent {
            VeloGoTheme(mode = ThemeMode.LIGHT) {
                SavedRoutesContent(
                    rows = list,
                    onOpen = { asked += "ouvrir $it" },
                    onRename = { id, name -> asked += "renommer $id en « $name »" },
                    onShare = { asked += "partager ${it.id}" },
                    onDelete = { asked += "supprimer $it" },
                    onNew = { asked += "nouveau" },
                    contentPadding = PaddingValues(bottom = 80.dp),
                )
            }
        }
    }

    @Test
    fun `un appui sur un trajet le rouvre`() {
        show()
        compose.onNodeWithText("Col de Vizzavona").performClick()
        assertEquals(listOf("ouvrir 9"), asked)
    }

    @Test
    fun `supprimer demande confirmation`() {
        show()
        compose.onNodeWithContentDescription("Actions pour Col de Vizzavona").performClick()
        compose.onNodeWithText("Supprimer").performClick()
        compose.onNodeWithText("Supprimer ce trajet ?").assertIsDisplayed()
        compose.onNodeWithText("Annuler").performClick()
        assertEquals("annuler ne supprime rien", emptyList<String>(), asked)

        compose.onNodeWithContentDescription("Actions pour Col de Vizzavona").performClick()
        compose.onNodeWithText("Supprimer").performClick()
        compose.onNodeWithText("Supprimer").performClick()
        assertEquals(listOf("supprimer 9"), asked)
    }

    @Test
    fun `partager part du menu de la ligne`() {
        show()
        compose.onNodeWithContentDescription("Actions pour Tour du lac").performClick()
        compose.onNodeWithText("Partager").performClick()
        assertEquals(listOf("partager 7"), asked)
    }

    @Test
    fun `sans trajet, l'ecran explique et propose d'en preparer un`() {
        show(emptyList())
        compose.onNodeWithText("Aucun trajet enregistré").assertIsDisplayed()
        compose.onAllNodesWithText("Nouveau").assertCountEquals(0)
        compose.onNodeWithText("Préparer un itinéraire").performClick()
        assertEquals(listOf("nouveau"), asked)
    }

    @Test
    fun `avec des trajets, le bouton du haut en prepare un nouveau`() {
        show()
        compose.onNodeWithText("Nouveau").performClick()
        assertEquals(listOf("nouveau"), asked)
    }

    @Test
    fun `tant que la liste n'est pas lue, rien n'est annonce`() {
        show(null)
        compose.onNodeWithText("Mes trajets").assertIsDisplayed()
        compose.onAllNodesWithText("Aucun trajet enregistré").assertCountEquals(0)
    }
}
