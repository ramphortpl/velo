package fr.velogo.app.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.velogo.app.location.GpsStatus
import fr.velogo.app.location.LocationAccess
import fr.velogo.app.ui.components.GlassPill
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.core.format.Formats
import fr.velogo.core.ride.RideConfig
import kotlin.math.abs
import kotlin.math.round

/** Vert : tout va bien. Orange : ça marche à moitié. Rouge : pas de position utilisable pour enregistrer. */
enum class GpsTone { GOOD, WEAK, BAD }

/** La pastille du GPS, en haut de l'écran : une couleur et quelques mots. */
data class GpsChipModel(val tone: GpsTone, val label: String)

/** Ce que l'appli peut proposer pour arranger la situation. */
enum class GpsRemedy {
    /** Ouvrir les réglages de localisation du téléphone. */
    LOCATION_SETTINGS,
    /** Demander la position exacte. */
    ALLOW_PRECISE,
    /** Relancer la recherche des satellites. */
    RESTART,
}

/** Une ligne de la feuille « Signal GPS » : « Satellites — 12 captés, 7 utilisés ». */
data class GpsLine(val label: String, val value: String)

/** La feuille « Signal GPS » : ce qui se passe, dit simplement, et ce qu'on peut y faire. */
data class GpsSheetModel(
    val tone: GpsTone,
    val title: String,
    val text: String,
    val lines: List<GpsLine> = emptyList(),
    /** Conseil en petit, sous les lignes. */
    val tip: String? = null,
    val remedy: GpsRemedy? = null,
    val remedyLabel: String? = null,
    /** Proposer aussi de faire tout oublier au GPS : seulement quand il cherche depuis longtemps. */
    val canReset: Boolean = false,
)

/** En dessous, le signal est bon ; au-dessus, il reste utilisable mais on le signale en orange. */
private const val GOOD_ACCURACY_M = 15.0
/** Au-delà, le moteur de sortie n'enregistre plus les positions. */
private val RECORD_ACCURACY_M = RideConfig().maxAccuracyM
/** Après ce temps de recherche sans résultat, on propose la réinitialisation du GPS. */
private const val RESET_AFTER_S = 90

fun gpsChip(access: LocationAccess, status: GpsStatus): GpsChipModel = when {
    !status.locationEnabled -> GpsChipModel(GpsTone.BAD, "GPS coupé")
    access != LocationAccess.PRECISE -> GpsChipModel(GpsTone.WEAK, "Approximatif")
    !status.gpsPresent -> GpsChipModel(GpsTone.BAD, "Pas de GPS")
    !status.gpsEnabled -> GpsChipModel(GpsTone.BAD, "GPS coupé")
    status.hasFix -> {
        val accuracy = status.accuracyM
        if (accuracy == null) GpsChipModel(GpsTone.GOOD, "GPS") else GpsChipModel(toneOf(accuracy), "GPS ±${Formats.meters(accuracy)} m")
    }
    // Le GPS cherche : orange si la carte montre au moins une position approchée, rouge si elle ne montre rien.
    else -> GpsChipModel(if (status.hasApproximate) GpsTone.WEAK else GpsTone.BAD, "Recherche GPS")
}

private fun toneOf(accuracyM: Double): GpsTone = when {
    accuracyM <= GOOD_ACCURACY_M -> GpsTone.GOOD
    accuracyM <= RECORD_ACCURACY_M -> GpsTone.WEAK
    else -> GpsTone.BAD
}

fun gpsSheet(access: LocationAccess, status: GpsStatus): GpsSheetModel = when {
    !status.locationEnabled -> GpsSheetModel(
        tone = GpsTone.BAD,
        title = "La localisation du téléphone est coupée",
        text = "Tant qu'elle est coupée, VeloGo ne peut ni te situer sur la carte ni enregistrer ta sortie.",
        remedy = GpsRemedy.LOCATION_SETTINGS,
        remedyLabel = "Activer la localisation",
    )

    access != LocationAccess.PRECISE -> GpsSheetModel(
        tone = GpsTone.WEAK,
        title = "Position approximative seulement",
        text = "VeloGo n'a le droit de connaître ta position qu'à quelques kilomètres près : assez pour la météo, " +
            "pas pour te situer sur la carte ni pour enregistrer une sortie.",
        lines = approximateLines(status),
        remedy = GpsRemedy.ALLOW_PRECISE,
        remedyLabel = "Autoriser la position exacte",
    )

    !status.gpsPresent -> GpsSheetModel(
        tone = GpsTone.BAD,
        title = "Cet appareil n'a pas de GPS",
        text = "Sans GPS, VeloGo peut montrer une position approchée, déduite du Wi-Fi et du réseau, mais pas enregistrer de sortie.",
        lines = approximateLines(status),
    )

    !status.gpsEnabled -> GpsSheetModel(
        tone = GpsTone.BAD,
        title = "Le GPS du téléphone est coupé",
        text = "La localisation est active, mais sans les satellites : le téléphone est réglé sur un mode économe " +
            "(« Économie de batterie » ou « Réseau uniquement », par exemple). Dans les réglages de localisation, " +
            "choisis le mode « Haute précision ».",
        lines = approximateLines(status),
        remedy = GpsRemedy.LOCATION_SETTINGS,
        remedyLabel = "Ouvrir les réglages de localisation",
    )

    status.hasFix -> {
        val accuracy = status.accuracyM
        val lines = listOfNotNull(satellitesLine(status))
        when {
            accuracy == null -> GpsSheetModel(GpsTone.GOOD, "Le GPS fonctionne", "Le téléphone ne dit pas avec quelle précision.", lines)
            accuracy <= GOOD_ACCURACY_M -> GpsSheetModel(GpsTone.GOOD, "Le GPS fonctionne bien", "Ta position est connue à ${within(accuracy)}.", lines)
            accuracy <= RECORD_ACCURACY_M -> GpsSheetModel(
                GpsTone.WEAK, "Signal GPS moyen",
                "Ta position est connue à ${within(accuracy)}. C'est assez pour enregistrer ; le signal s'améliore à ciel ouvert.",
                lines,
            )
            else -> GpsSheetModel(
                GpsTone.BAD, "Signal GPS trop faible",
                "Ta position n'est connue qu'à ${within(accuracy)} : c'est trop flou pour enregistrer " +
                    "(il faut ${Formats.meters(RECORD_ACCURACY_M)}${NBSP}m ou mieux). Éloigne-toi des murs et des arbres, téléphone à l'air libre.",
                lines,
            )
        }
    }

    else -> {
        val searching = status.searchingS
        val shown = if (status.hasApproximate) {
            val accuracy = status.approximateAccuracyM
            "En attendant, la carte montre une position approchée, déduite du Wi-Fi et du réseau" +
                (if (accuracy != null) " (à ${within(accuracy)})" else "") + ". "
        } else {
            "VeloGo n'a pas encore de position. "
        }
        val canReset = searching != null && searching >= RESET_AFTER_S
        GpsSheetModel(
            tone = if (status.hasApproximate) GpsTone.WEAK else GpsTone.BAD,
            title = "Le GPS cherche ses satellites",
            text = shown + "Pour enregistrer une sortie ou être guidé, il faut le GPS : tout démarre dès qu'il a trouvé ses satellites.",
            lines = listOfNotNull(
                satellitesLine(status),
                searching?.let { GpsLine("Recherche", "depuis ${elapsed(it)}") },
            ),
            tip = "Dehors, à ciel ouvert, il lui faut d'habitude moins d'une minute. En intérieur, il peut ne jamais y arriver." +
                if (canReset) " S'il ne trouve toujours rien dehors, « Réinitialiser le GPS » lui fait oublier ce qu'il croit " +
                    "savoir des satellites : il repart de zéro, ce qui débloque parfois un téléphone, et demande une à deux minutes."
                else "",
            remedy = GpsRemedy.RESTART,
            remedyLabel = "Relancer le GPS",
            canReset = canReset,
        )
    }
}

private fun approximateLines(status: GpsStatus): List<GpsLine> =
    if (status.hasApproximate) listOf(GpsLine("Position approchée", status.approximateAccuracyM?.let { "à ${within(it)}" } ?: "connue")) else emptyList()

/** « 12 captés, 7 utilisés » : ce que le GPS reçoit, et ce dont il se sert pour calculer la position. */
private fun satellitesLine(status: GpsStatus): GpsLine? {
    val seen = status.satellitesSeen ?: return null
    val used = status.satellitesUsed ?: 0
    val value = when {
        seen == 0 -> "aucun capté pour l'instant"
        else -> "$seen ${if (seen > 1) "captés" else "capté"}, $used ${if (used > 1) "utilisés" else "utilisé"}"
    }
    return GpsLine("Satellites", value)
}

/** « 4 m près », « 1,5 km près », « 2 km près » : la précision d'une position, dans une phrase. */
private fun within(accuracyM: Double): String {
    val value = if (accuracyM < 995) {
        "${Formats.decimal(accuracyM.coerceAtLeast(1.0), 0)}${NBSP}m"
    } else {
        val km = accuracyM / 1000.0
        "${Formats.decimal(km, if (abs(km - round(km)) < 0.05) 0 else 1)}${NBSP}km"
    }
    return "$value près"
}

/** Espace insécable : un nombre n'est jamais séparé de son unité par un retour à la ligne. */
private const val NBSP = "\u00A0"

/** « 45 s », « 2 min 10 s ». */
private fun elapsed(seconds: Int): String =
    if (seconds < 60) "$seconds s" else "${seconds / 60} min ${(seconds % 60).toString().padStart(2, '0')} s"

@Composable
private fun toneColor(tone: GpsTone): Color = when (tone) {
    GpsTone.GOOD -> LocalRideColors.current.active
    GpsTone.WEAK -> LocalRideColors.current.paused
    GpsTone.BAD -> MaterialTheme.colorScheme.error
}

/** État du GPS en un coup d'œil ; un appui ouvre la feuille qui l'explique. */
@Composable
fun GpsChip(model: GpsChipModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassPill(modifier, onClick = onClick, horizontalPadding = 13.dp, verticalPadding = 10.dp) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(toneColor(model.tone)))
        Spacer(Modifier.width(8.dp))
        Text(
            model.label,
            style = LabelCapsStyle.copy(fontSize = 14.sp),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Contenu de la feuille « Signal GPS ». */
@Composable
fun GpsSheetContent(
    model: GpsSheetModel,
    onRemedy: (GpsRemedy) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Signal GPS", style = LabelCapsStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(toneColor(model.tone)))
            Spacer(Modifier.width(12.dp))
            Text(model.title, style = MaterialTheme.typography.titleLarge)
        }
        Text(model.text, style = MaterialTheme.typography.bodyLarge)
        if (model.lines.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            for (line in model.lines) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(line.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text(line.value, style = MaterialTheme.typography.titleMedium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (model.tip != null) {
            Text(model.tip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val remedy = model.remedy
        if (remedy != null && model.remedyLabel != null) {
            Spacer(Modifier.height(2.dp))
            Button(onClick = { onRemedy(remedy) }, modifier = Modifier.fillMaxWidth()) { Text(model.remedyLabel) }
        }
        if (model.canReset) {
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Réinitialiser le GPS") }
        }
    }
}
