package fr.velogo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.velogo.app.ui.theme.GlassBox
import fr.velogo.app.ui.theme.GlassDefaults
import fr.velogo.app.ui.theme.GlassStyle
import fr.velogo.app.ui.theme.LocalGlass

/** Bouton rond en verre, pour une icône seule. */
@Composable
fun GlassIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlassStyle = LocalGlass.current.regular,
    tint: Color = Color.Unspecified,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    GlassBox(modifier.size(size), style = style, reach = GlassDefaults.SmallReach, onClick = onClick, role = Role.Button) {
        Icon(painterResource(icon), contentDescription = description, tint = tint.takeOrElse { LocalContentColor.current }, modifier = Modifier.size(iconSize))
    }
}

/** Un bouton d'une pile de commandes ([GlassControlStack]). */
class StackControl(
    val icon: Int,
    val description: String,
    /** La fonction est en marche (carte qui suit la position, par exemple) : l'icône prend la couleur active. */
    val active: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Plusieurs boutons réunis dans une seule pilule de verre verticale, séparés par un filet : c'est plus calme, sur
 * une carte, qu'une colonne de boutons détachés.
 */
@Composable
fun GlassControlStack(controls: List<StackControl>, activeColor: Color, modifier: Modifier = Modifier) {
    if (controls.isEmpty()) return
    val style = LocalGlass.current.regular
    GlassBox(modifier.width(STACK_CELL), style = style, corner = STACK_CELL / 2, reach = GlassDefaults.SmallReach) {
        Column(Modifier.clip(RoundedCornerShape(STACK_CELL / 2))) {
            controls.forEachIndexed { index, control ->
                if (index > 0) {
                    Box(Modifier.padding(horizontal = 11.dp).height(1.dp).width(STACK_CELL - 22.dp).background(style.content.copy(alpha = 0.12f)))
                }
                Box(
                    Modifier.size(STACK_CELL).clickable(role = Role.Button, onClick = control.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(control.icon),
                        contentDescription = control.description,
                        tint = if (control.active) activeColor else style.content,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** Côté d'un bouton de la pile : la taille minimale confortable sous le doigt. */
val STACK_CELL = 48.dp

/** Une pilule de verre qui porte une ligne de contenu (pastille d'état, bouton avec icône et texte). */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    style: GlassStyle = LocalGlass.current.regular,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    reach: Dp = GlassDefaults.SmallReach,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 9.dp,
    content: @Composable RowScope.() -> Unit,
) {
    // Une pilule qui se touche garde autour d'elle la place d'un doigt (48 dp), même si elle est plus fine à l'œil.
    val outer = if (onClick != null) modifier.minimumInteractiveComponentSize() else modifier
    GlassBox(outer, style = style, reach = reach, onClick = onClick, onClickLabel = onClickLabel, role = if (onClick != null) Role.Button else null) {
        Row(Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/**
 * Le bouton de l'action principale d'un panneau (« Calculer l'itinéraire », « Démarrer le guidage ») : une pilule
 * du vert vif du logo. Désactivé, il devient gris et perd son ombre.
 */
@Composable
fun AccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val style = if (enabled) LocalGlass.current.accent else {
        val ink = MaterialTheme.colorScheme.onSurface
        GlassStyle(
            fillTop = ink.copy(alpha = 0.10f), fillBottom = ink.copy(alpha = 0.10f),
            rimTop = Color.Transparent, rimBottom = Color.Transparent,
            edge = Color.Transparent, shadow = Color.Transparent, content = ink.copy(alpha = 0.42f),
        )
    }
    GlassBox(
        modifier.heightIn(min = 52.dp),
        style = style,
        reach = if (enabled) GlassDefaults.SmallReach else 0.dp,
        onClick = onClick,
        enabled = enabled,
        role = Role.Button,
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
