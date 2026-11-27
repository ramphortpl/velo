package fr.velogo.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import fr.velogo.app.R
import kotlin.math.roundToInt

// Les petites images que l'appli dessine elle-même sur la carte, les mêmes quel que soit le moteur qui l'affiche.

/** Le chiffre d'une étape, en blanc sur fond transparent, à poser sur son repère. */
internal fun stepNumberImage(ctx: Context, number: Int): Bitmap {
    val metrics = ctx.resources.displayMetrics
    val size = (VeloMapState.STEP_IMAGE_DP * metrics.density).roundToInt().coerceAtLeast(8)
    val image = createBitmap(size, size).apply { density = metrics.densityDpi }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = size * 0.72f
    }
    Canvas(image).drawText(number.toString(), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
    return image
}

/** La pointe de la flèche de manœuvre : un triangle tourné vers le nord, que la carte oriente ensuite. */
internal fun arrowHeadImage(ctx: Context, c: MapColors): Bitmap {
    val metrics = ctx.resources.displayMetrics
    val size = (VeloMapState.ARROW_IMAGE_DP * metrics.density).roundToInt().coerceAtLeast(12)
    val image = createBitmap(size, size).apply { density = metrics.densityDpi }
    val s = size.toFloat()
    val shape = Path().apply {
        moveTo(s * 0.5f, s * 0.08f)
        lineTo(s * 0.9f, s * 0.82f)
        lineTo(s * 0.5f, s * 0.66f)
        lineTo(s * 0.1f, s * 0.82f)
        close()
    }
    val canvas = Canvas(image)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = s * 0.14f
    paint.color = c.arrowCasing
    canvas.drawPath(shape, paint)
    paint.style = Paint.Style.FILL
    paint.color = c.arrow
    canvas.drawPath(shape, paint)
    return image
}

/**
 * La position tant que le sens de la marche est inconnu : un point de la couleur du repère, cerné de blanc, avec
 * une ombre légère. Pour le moteur de Mapbox, à qui l'on donne des images toutes faites ; l'autre moteur compose
 * le même repère à partir de deux dessins.
 */
internal fun puckDotImage(ctx: Context, c: MapColors): Bitmap {
    val metrics = ctx.resources.displayMetrics
    val d = metrics.density
    val size = (PUCK_DOT_DP * d).roundToInt().coerceAtLeast(12)
    val image = createBitmap(size, size).apply { density = metrics.densityDpi }
    val canvas = Canvas(image)
    val center = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.WHITE
    paint.setShadowLayer(2f * d, 0f, 0.8f * d, 0x55000000)
    canvas.drawCircle(center, center, 9.5f * d, paint)
    paint.clearShadowLayer()
    paint.color = c.puck
    canvas.drawCircle(center, center, 7f * d, paint)
    return image
}

/** La position quand on roule : la flèche de l'appli (`map_puck_arrow`), pointe vers le nord, que la carte oriente. */
internal fun puckArrowImage(ctx: Context): Bitmap {
    val metrics = ctx.resources.displayMetrics
    val drawable = checkNotNull(ContextCompat.getDrawable(ctx, R.drawable.map_puck_arrow)) { "dessin de la flèche introuvable" }
    return drawable.toBitmap().apply { density = metrics.densityDpi }
}

/** Côté de l'image du point de position : le point, son cerne blanc et la place de l'ombre. */
private const val PUCK_DOT_DP = 26f
