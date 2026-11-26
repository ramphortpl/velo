package fr.velogo.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Arrondis des composants Material (fenêtres, feuilles, menus, champs) : plus généreux que ceux d'origine. */
val VeloGoShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/** Rayons des éléments de l'appli. */
object Corners {
    /** Cartes des listes et des écrans de détail. */
    val Card = 26.dp
    /** Petites cartes à l'intérieur d'un panneau ou d'une feuille. */
    val Inner = 20.dp
    /** Vignettes (tracé d'une sortie, d'un trajet). */
    val Thumbnail = 18.dp
    /** Panneaux posés sur la carte. */
    val Panel = 32.dp
}

/**
 * Coins « continus », comme sur iOS : la courbure n'apparaît pas d'un coup au bout d'un côté droit, elle s'installe
 * peu à peu. À rayon égal le coin a la même rondeur d'ensemble qu'un quart de cercle, mais il se raccorde aux côtés
 * sans à-coup : l'œil ne voit plus où la courbe commence.
 *
 * La courbe est celle des rectangles arrondis d'iOS, relevée point par point : trois courbes de Bézier par coin,
 * qui commencent à 1,53 fois le rayon du sommet. Le rayon est donc borné au tiers environ du petit côté ; au-delà
 * on veut une pilule, qui est un vrai demi-cercle (`CircleShape`).
 *
 * Cette forme sert à **dessiner** des fonds, pas à rogner : rogner selon un tracé quelconque coûte bien plus cher
 * au téléphone que selon un rectangle arrondi, et cela se sentirait en faisant défiler une liste.
 */
class ContinuousShape(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(continuousRoundRect(size, with(density) { radius.toPx() }))

    override fun equals(other: Any?): Boolean = other is ContinuousShape && other.radius == radius
    override fun hashCode(): Int = radius.hashCode()
    override fun toString(): String = "ContinuousShape($radius)"
}

/** Rapport entre le rayon et la longueur de côté que prend un coin continu. */
const val CONTINUOUS_REACH = 1.52866483f

/** Le plus grand rayon qu'un coin continu peut avoir dans un rectangle de cette taille. */
fun continuousMaxRadius(size: Size): Float = min(size.width, size.height) / 2f / CONTINUOUS_REACH

/** Tracé d'un rectangle à coins continus. Le rayon est ramené à ce que la taille permet. */
fun continuousRoundRect(size: Size, radius: Float, into: Path = Path()): Path {
    val w = size.width
    val h = size.height
    val r = min(radius, continuousMaxRadius(size)).coerceAtLeast(0f)
    into.rewind()
    if (r <= 0f || w <= 0f || h <= 0f) {
        into.moveTo(0f, 0f)
        into.lineTo(w, 0f)
        into.lineTo(w, h)
        into.lineTo(0f, h)
        into.close()
        return into
    }
    /**
     * Un coin, parcouru dans le sens des aiguilles d'une montre : on y arrive par un côté, on en repart par
     * l'autre. ([vx], [vy]) est son sommet, ([sx], [sy]) le sens qui en éloigne vers l'intérieur. Dans (a, b),
     * a est la distance au sommet le long du côté d'arrivée et b le long du côté de départ, en rayons.
     */
    fun corner(vx: Float, vy: Float, sx: Float, sy: Float, arrivesHorizontally: Boolean) {
        fun x(a: Float, b: Float) = vx + sx * r * (if (arrivesHorizontally) a else b)
        fun y(a: Float, b: Float) = vy + sy * r * (if (arrivesHorizontally) b else a)
        fun line(a: Float, b: Float) = into.lineTo(x(a, b), y(a, b))
        fun curve(a1: Float, b1: Float, a2: Float, b2: Float, a3: Float, b3: Float) =
            into.cubicTo(x(a1, b1), y(a1, b1), x(a2, b2), y(a2, b2), x(a3, b3), y(a3, b3))

        line(CONTINUOUS_REACH, 0f)
        curve(1.08849299f, 0f, 0.86840701f, 0f, 0.66993397f, 0.06549569f)
        line(0.63149399f, 0.07491111f)
        curve(0.37282401f, 0.16905883f, 0.16905883f, 0.37282401f, 0.07491111f, 0.63149399f)
        curve(0f, 0.86840701f, 0f, 1.08849299f, 0f, CONTINUOUS_REACH)
    }

    into.moveTo(CONTINUOUS_REACH * r, 0f)
    corner(w, 0f, -1f, 1f, arrivesHorizontally = true)
    corner(w, h, -1f, -1f, arrivesHorizontally = false)
    corner(0f, h, 1f, -1f, arrivesHorizontally = true)
    corner(0f, 0f, 1f, 1f, arrivesHorizontally = false)
    into.close()
    return into
}
