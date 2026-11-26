package fr.velogo.app.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import fr.velogo.app.R
import fr.velogo.app.ui.components.flatBarColors

/** Un morceau de la politique de confidentialité, tel qu'il s'affiche. */
sealed interface PolicyBlock {
    /** Le titre du document (« # … »). */
    data class Title(val text: String) : PolicyBlock
    /** Un titre de rubrique (« ## … »). */
    data class Heading(val text: String) : PolicyBlock
    data class Paragraph(val text: String) : PolicyBlock
    /** Un élément de liste (« - … »). */
    data class Bullet(val text: String) : PolicyBlock
}

/**
 * Lit le texte de la politique de confidentialité (`docs/CONFIDENTIALITE.md`, recopié dans l'appli à la
 * fabrication) : des titres, des paragraphes et des listes, rien d'autre. Les lignes qui se suivent sans ligne
 * vide forment un seul paragraphe.
 */
fun parsePolicy(markdown: String): List<PolicyBlock> {
    val blocks = mutableListOf<PolicyBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += PolicyBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
    }
    for (raw in markdown.lineSequence()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("## ") -> { flush(); blocks += PolicyBlock.Heading(line.removePrefix("## ").trim()) }
            line.startsWith("# ") -> { flush(); blocks += PolicyBlock.Title(line.removePrefix("# ").trim()) }
            line.startsWith("- ") -> { flush(); blocks += PolicyBlock.Bullet(line.removePrefix("- ").trim()) }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
    }
    flush()
    return blocks
}

/** Nom du fichier de la politique dans les ressources de l'appli. */
const val POLICY_ASSET = "confidentialite.md"

/** La politique de confidentialité, lisible dans l'appli sans réseau (Réglages > Confidentialité). */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember {
        runCatching { context.assets.open(POLICY_ASSET).bufferedReader().use { parsePolicy(it.readText()) } }.getOrDefault(emptyList())
    }
    PrivacyContent(blocks, onBack)
}

/** Le contenu de l'écran, sans accès aux fichiers : c'est lui qu'on vérifie en image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyContent(blocks: List<PolicyBlock>, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confidentialité") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Retour") }
                },
                colors = flatBarColors(),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = 4.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp,
            ),
        ) {
            items(blocks) { block ->
                when (block) {
                    is PolicyBlock.Title -> Text(block.text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 6.dp))
                    is PolicyBlock.Heading -> Text(
                        block.text,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
                    )
                    is PolicyBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 5.dp))
                    is PolicyBlock.Bullet -> Row(Modifier.padding(vertical = 4.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(block.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
