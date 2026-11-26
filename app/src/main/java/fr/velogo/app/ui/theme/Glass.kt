package fr.velogo.app.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlin.math.max
import kotlin.math.min

/**
 * Les surfaces « verre » de VeloGo : ce qui flotte au-dessus de la carte (barre d'onglets, boutons, pastilles,
 * panneaux).
 *
 * Ce n'est pas un vrai verre dépoli. La carte est dessinée par MapLibre dans une surface à part (`SurfaceView`),
 * que l'interface ne peut ni lire ni flouter ; et flouter soixante à quatre-vingt-dix images par seconde
 * demanderait au téléphone plus qu'il ne peut donner sans saccades. Le verre est donc **dessiné** : un fond
 * translucide en léger dégradé, un liseré clair plus vif en haut (le reflet), un filet sombre autour et une ombre
 * douce au-dehors. Tout cela, ce sont quelques rectangles arrondis, que le téléphone trace sans effort et ne
 * retrace que lorsque l'élément change : faire glisser la carte ne redessine rien de l'interface.
 */
@Immutable
data class GlassStyle(
    /** Le fond, du haut vers le bas. */
    val fillTop: Color,
    val fillBottom: Color,
    /** Le reflet : un liseré à l'intérieur du bord, plus vif en haut qu'en bas. */
    val rimTop: Color,
    val rimBottom: Color,
    /** Le filet qui détache la surface de la carte. */
    val edge: Color,
    /** L'ombre, à sa plus forte opacité (contre le bord). */
    val shadow: Color,
    /** La couleur du texte et des icônes posés dessus. */
    val content: Color,
)

/** Les quatre verres de l'appli. */
@Immutable
data class GlassPalette(
    /** Boutons, pastilles et barres : on devine la carte au travers. */
    val regular: GlassStyle,
    /** Panneaux qui portent beaucoup de texte : presque opaque, pour rester lisible sans flou. */
    val panel: GlassStyle,
    /** L'action principale : le vert vif du logo. */
    val accent: GlassStyle,
    /** Le vert foncé du logo : bandeau de guidage, ce qui doit se lire d'un coup d'œil. */
    val deep: GlassStyle,
)

private val AccentGlass = GlassStyle(
    fillTop = Color(0xFF2FE3B1),
    fillBottom = Color(0xFF01C18C),
    rimTop = Color.White.copy(alpha = 0.70f),
    rimBottom = Color.White.copy(alpha = 0.12f),
    edge = Brand.Deep.copy(alpha = 0.22f),
    shadow = Color(0xFF017A5B).copy(alpha = 0.30f),
    content = Color(0xFF013527),
)

private val DeepGlass = GlassStyle(
    fillTop = Color(0xFF02664D),
    fillBottom = Brand.Deep,
    rimTop = Color.White.copy(alpha = 0.38f),
    rimBottom = Color.White.copy(alpha = 0.06f),
    edge = Color(0xFF00241B).copy(alpha = 0.35f),
    shadow = Color(0xFF00241B).copy(alpha = 0.30f),
    content = Color.White,
)

val LightGlass = GlassPalette(
    regular = GlassStyle(
        fillTop = Color.White.copy(alpha = 0.90f),
        fillBottom = Color(0xFFF1FAF6).copy(alpha = 0.80f),
        rimTop = Color.White.copy(alpha = 0.95f),
        rimBottom = Color.White.copy(alpha = 0.40f),
        edge = Brand.Deep.copy(alpha = 0.10f),
        shadow = Color(0xFF012A1F).copy(alpha = 0.17f),
        content = Color(0xFF0E1A16),
    ),
    panel = GlassStyle(
        fillTop = Color.White.copy(alpha = 0.97f),
        fillBottom = Color(0xFFFBFDFC).copy(alpha = 0.96f),
        rimTop = Color.White,
        rimBottom = Color.White.copy(alpha = 0.50f),
        edge = Brand.Deep.copy(alpha = 0.10f),
        shadow = Color(0xFF012A1F).copy(alpha = 0.20f),
        content = Color(0xFF0E1A16),
    ),
    accent = AccentGlass,
    deep = DeepGlass,
)

val DarkGlass = GlassPalette(
    regular = GlassStyle(
        fillTop = Color(0xFF1B2D26).copy(alpha = 0.88f),
        fillBottom = Color(0xFF0D1915).copy(alpha = 0.82f),
        rimTop = Color.White.copy(alpha = 0.30f),
        rimBottom = Color.White.copy(alpha = 0.05f),
        edge = Color.Black.copy(alpha = 0.45f),
        shadow = Color.Black.copy(alpha = 0.40f),
        content = Color(0xFFE1EBE6),
    ),
    panel = GlassStyle(
        fillTop = Color(0xFF16241F).copy(alpha = 0.97f),
        fillBottom = Color(0xFF0E1915).copy(alpha = 0.96f),
        rimTop = Color.White.copy(alpha = 0.26f),
        rimBottom = Color.White.copy(alpha = 0.04f),
        edge = Color.Black.copy(alpha = 0.45f),
        shadow = Color.Black.copy(alpha = 0.45f),
        content = Color(0xFFE1EBE6),
    ),
    accent = AccentGlass.copy(edge = Color.Black.copy(alpha = 0.35f), shadow = Color.Black.copy(alpha = 0.40f)),
    deep = DeepGlass.copy(fillTop = Color(0xFF027256), fillBottom = Color(0xFF01523E), shadow = Color.Black.copy(alpha = 0.45f)),
)

/**
 * Plein soleil : ni transparence ni ombre, du noir sur du blanc cerné de noir. Sur un écran peu lumineux en plein
 * été, tout le reste se perd.
 */
val SunlightGlass: GlassPalette = run {
    val plain = GlassStyle(
        fillTop = Color.White, fillBottom = Color.White,
        rimTop = Color.Transparent, rimBottom = Color.Transparent,
        edge = Color.Black, shadow = Color.Transparent, content = Color.Black,
    )
    val solid = GlassStyle(
        fillTop = Color(0xFF004D3C), fillBottom = Color(0xFF004D3C),
        rimTop = Color.Transparent, rimBottom = Color.Transparent,
        edge = Color.Black, shadow = Color.Transparent, content = Color.White,
    )
    GlassPalette(regular = plain, panel = plain, accent = solid, deep = solid)
}

val LocalGlass = staticCompositionLocalOf { LightGlass }

object GlassDefaults {
    /** Jusqu'où porte l'ombre d'un élément posé sur la carte. */
    val Reach = 14.dp
    /** L'ombre d'un petit élément (pastille, bouton rond). */
    val SmallReach = 10.dp
    /** Nombre d'anneaux qui composent une ombre. */
    const val RINGS = 10
}

/**
 * Part de l'opacité de l'ombre portée par chacun de ses anneaux, du plus proche du bord au plus lointain.
 *
 * L'ombre est faite d'anneaux qui partent tous du bord et vont de plus en plus loin : là où ils se recouvrent,
 * leurs opacités s'ajoutent. Ces parts sont choisies pour que l'opacité cumulée décroisse comme le carré de la
 * distance restante : forte contre le bord, nulle au bout, sans marche visible. Leur somme vaut 1.
 */
fun glassShadowShares(rings: Int = GlassDefaults.RINGS): FloatArray {
    require(rings > 0)
    // Opacité cumulée voulue au départ de l'anneau k : entière contre le bord (k = 0), nulle au bout (k = rings).
    fun cumulated(k: Int): Float {
        val left = 1f - k / rings.toFloat()
        return left * left
    }
    return FloatArray(rings) { index -> cumulated(index) - cumulated(index + 1) }
}

/**
 * Dessine une surface « verre » derrière le contenu. [corner] non précisé : une pilule (coins au maximum).
 * [reach] est la portée de l'ombre ; zéro, pas d'ombre (élément posé sur un fond uni, ou dans une liste).
 *
 * L'ombre déborde de l'élément : son parent ne doit pas le rogner.
 */
fun Modifier.glass(style: GlassStyle, corner: Dp = Dp.Unspecified, reach: Dp = GlassDefaults.Reach): Modifier = drawWithCache {
    val radius = min(if (corner.isSpecified) corner.toPx() else Float.MAX_VALUE, size.minDimension / 2f)
    val reachPx = reach.toPx()
    val shadowed = reachPx > 0f && style.shadow.alpha > 0f
    // L'ombre tombe un peu vers le bas, comme sous une lumière venue d'en haut.
    val lift = reachPx * 0.22f
    val inset = lift + 1f
    val shares = if (shadowed) glassShadowShares() else FloatArray(0)
    val outline = if (shadowed) Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) } else null
    onDrawBehind {
        // Élément encore sans taille (début d'une animation d'apparition) : rien à dessiner.
        if (size.minDimension <= 0f) return@onDrawBehind
        if (outline != null) {
            // Rien de l'ombre ne passe sous le verre : il est translucide, elle s'y verrait.
            clipPath(outline, ClipOp.Difference) {
                for (index in shares.indices) {
                    val far = reachPx * (index + 1) / shares.size
                    val middle = (far - inset) / 2f
                    drawRoundRect(
                        color = style.shadow.copy(alpha = style.shadow.alpha * shares[index]),
                        topLeft = Offset(-middle, -middle + lift),
                        size = Size(size.width + 2 * middle, size.height + 2 * middle),
                        cornerRadius = CornerRadius(max(0f, radius + middle)),
                        style = Stroke(far + inset),
                    )
                }
            }
        }
        drawGlassBody(style, Offset.Zero, size, radius)
    }
}

/**
 * Le verre lui-même, sans son ombre : le fond en dégradé, le reflet à l'intérieur du bord, le filet autour.
 * Sert aussi à dessiner une pastille de verre à l'intérieur d'un autre élément (option choisie, onglet actif).
 */
fun DrawScope.drawGlassBody(style: GlassStyle, topLeft: Offset, size: Size, radius: Float) {
    if (size.width <= 0f || size.height <= 0f) return
    val bottom = topLeft.y + size.height
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(style.fillTop, style.fillBottom), startY = topLeft.y, endY = bottom),
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(radius),
    )
    if (style.rimTop.alpha > 0f || style.rimBottom.alpha > 0f) {
        val width = min(max(1.dp.toPx(), 1.5f), min(size.width, size.height) / 2f)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(style.rimTop, style.rimBottom), startY = topLeft.y, endY = bottom),
            topLeft = Offset(topLeft.x + width / 2f, topLeft.y + width / 2f),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(max(0f, radius - width / 2f)),
            style = Stroke(width),
        )
    }
    if (style.edge.alpha > 0f) {
        val width = max(0.75.dp.toPx(), 1f)
        drawRoundRect(
            color = style.edge,
            topLeft = Offset(topLeft.x - width / 2f, topLeft.y - width / 2f),
            size = Size(size.width + width, size.height + width),
            cornerRadius = CornerRadius(radius + width / 2f),
            style = Stroke(width),
        )
    }
}

/**
 * Laisse le contenu déborder de [room] de chaque côté sans changer la place qu'il occupe : il est mesuré plus
 * grand, puis décalé d'autant. À poser sur un conteneur animé en fondu, dont le contenu a une marge de [room] :
 * pendant un fondu, Android dessine le conteneur dans une image à sa taille, et les ombres du verre, qui en
 * dépassent, seraient rognées puis réapparaîtraient d'un coup.
 */
fun Modifier.bleed(room: Dp): Modifier = layout { measurable, constraints ->
    val extra = room.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * extra, vertical = 2 * extra))
    layout((placeable.width - 2 * extra).coerceAtLeast(0), (placeable.height - 2 * extra).coerceAtLeast(0)) {
        placeable.place(-extra, -extra)
    }
}

/**
 * Petit rebond sous le doigt, comme une goutte qu'on presse : l'élément rétrécit à peine pendant l'appui et
 * revient en souplesse. Seule l'échelle de la couche change : rien n'est recomposé ni redessiné.
 */
fun Modifier.bounce(interaction: InteractionSource, pressedScale: Float = 0.955f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "rebond",
    )
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Une surface « verre » et son contenu. Avec [onClick], elle réagit comme un bouton (rebond, onde bornée à sa
 * forme).
 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    style: GlassStyle = LocalGlass.current.regular,
    corner: Dp = Dp.Unspecified,
    reach: Dp = GlassDefaults.Reach,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    role: Role? = null,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = if (corner.isSpecified) RoundedCornerShape(corner) else CircleShape
    val interaction = if (onClick != null) remember { MutableInteractionSource() } else null
    var outer = modifier
    if (interaction != null) outer = outer.bounce(interaction)
    outer = outer.glass(style, corner, reach)
    if (onClick != null && interaction != null) {
        outer = outer.clip(shape).clickable(
            interactionSource = interaction,
            indication = ripple(color = style.content),
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
    }
    CompositionLocalProvider(LocalContentColor provides style.content) {
        Box(outer, contentAlignment = contentAlignment, content = content)
    }
}
