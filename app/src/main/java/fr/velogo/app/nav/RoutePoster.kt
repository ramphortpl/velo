package fr.velogo.app.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import androidx.core.graphics.drawable.DrawableCompat
import fr.velogo.app.R
import fr.velogo.core.format.Formats
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.Route
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Ce que l'affiche d'un itinéraire annonce, en plus du tracé. */
data class PosterText(
    val title: String,
    /** Deuxième ligne, libre : la destination, un rendez-vous… Peut être vide. */
    val subtitle: String = "",
    /** Mention des sources du fond de carte, obligatoire quand il y en a un. */
    val attribution: String = "",
)

/** Fond de carte d'une affiche : l'image, et où y tombe un point géographique (en pixels de l'image). */
class PosterMap(val bitmap: Bitmap, val project: (LatLon) -> PointF)

/**
 * L'affiche d'un itinéraire, à envoyer aux copains : la carte avec le tracé, puis le nom de la sortie, ses
 * chiffres (distance, dénivelé, durée estimée) et son profil. Elle est dessinée ici, point par point, sans passer
 * par l'écran : même résultat sur tous les téléphones, et vérifiable en image sans téléphone.
 */
object RoutePoster {
    /** Format portrait 4:5, celui que les messageries affichent en entier. */
    const val WIDTH = 1080
    const val HEIGHT = 1350

    /** Taille du fond de carte à fournir : la carte occupe le haut de l'affiche. */
    const val MAP_WIDTH = WIDTH
    const val MAP_HEIGHT = 800

    /** Marges à garder autour du tracé dans le fond de carte, pour que rien ne touche les bords ni le cartouche. */
    const val MAP_PADDING = 84
    const val MAP_PADDING_BOTTOM = 124

    private const val CARD_OVERLAP = 44
    private const val SIDE = 56f

    private const val INK = 0xFF10201A.toInt()
    private const val INK_SOFT = 0xFF4A5A53.toInt()
    private const val INK_FAINT = 0xFF66756E.toInt()
    private const val PAPER = 0xFFFFFFFF.toInt()
    private const val BRAND = 0xFF006B54.toInt()
    private const val ROUTE = 0xFF1565C0.toInt()
    private const val START = 0xFF12A150.toInt()
    private const val VIA = 0xFFEF6C00.toInt()
    private const val END = 0xFFD32F2F.toInt()

    fun render(context: Context, route: Route, text: PosterText, map: PosterMap?): Bitmap {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)
        val condensedBold = font(context, R.font.barlow_condensed_bold, Typeface.DEFAULT_BOLD)
        val condensed = font(context, R.font.barlow_condensed_semibold, Typeface.DEFAULT_BOLD)

        drawMap(canvas, route, map)
        drawBrand(context, canvas, condensedBold)
        if (map != null && text.attribution.isNotBlank()) drawAttribution(canvas, text.attribution)
        drawCard(canvas, route, text, condensedBold, condensed)
        return bitmap
    }

    // --- La carte et le tracé ---------------------------------------------------------------------------------

    private fun drawMap(canvas: Canvas, route: Route, map: PosterMap?) {
        val zone = Rect(0, 0, MAP_WIDTH, MAP_HEIGHT)
        val project: (LatLon) -> PointF
        if (map != null) {
            // L'image est demandée à la taille de la zone ; si elle diffère, elle est étirée pour la remplir.
            val scaleX = MAP_WIDTH / map.bitmap.width.toFloat()
            val scaleY = MAP_HEIGHT / map.bitmap.height.toFloat()
            canvas.drawBitmap(map.bitmap, null, zone, Paint(Paint.FILTER_BITMAP_FLAG))
            project = { p -> map.project(p).let { PointF(it.x * scaleX, it.y * scaleY) } }
        } else {
            // Pas de fond de carte (pas de réseau) : le tracé seul, sur un fond uni légèrement quadrillé.
            val background = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, MAP_HEIGHT.toFloat(), 0xFFEAF4EE.toInt(), 0xFFD9EAE1.toInt(), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(zone, background)
            val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14006B54; strokeWidth = 2f }
            var x = 60f
            while (x < MAP_WIDTH) { canvas.drawLine(x, 0f, x, MAP_HEIGHT.toFloat(), grid); x += 120f }
            var y = 40f
            while (y < MAP_HEIGHT) { canvas.drawLine(0f, y, MAP_WIDTH.toFloat(), y, grid); y += 120f }
            project = flatProjection(route.points)
        }

        val path = Path()
        // Quelques milliers de points suffisent largement à cette taille.
        val step = (route.points.size / 3000).coerceAtLeast(1)
        var index = 0
        var first = true
        while (index < route.points.size) {
            val p = project(route.points[index])
            if (first) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            first = false
            index = if (index == route.points.size - 1) route.points.size else min(index + step, route.points.size - 1)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, stroke.apply { color = 0x33000000; strokeWidth = 24f })
        canvas.drawPath(path, stroke.apply { color = Color.WHITE; strokeWidth = 19f })
        canvas.drawPath(path, stroke.apply { color = ROUTE; strokeWidth = 11f })
        drawDirection(canvas, route, project)

        // Étapes, puis départ et arrivée par-dessus.
        val waypoints = route.waypoints
        for (i in 1 until waypoints.size - 1) marker(canvas, project(waypoints[i]), VIA, 13f)
        marker(canvas, project(route.points.last()), END, 18f)
        marker(canvas, project(route.points.first()), START, 18f)
    }

    /** Petites pointes blanches le long du tracé : elles disent dans quel sens on le parcourt. */
    private fun drawDirection(canvas: Canvas, route: Route, project: (LatLon) -> PointF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val count = 6
        for (k in 1 until count) {
            val at = route.distanceM * k / count
            val here = project(route.positionAt(at))
            val ahead = project(route.positionAt(at + route.distanceM / 200.0))
            val dx = ahead.x - here.x
            val dy = ahead.y - here.y
            val length = kotlin.math.hypot(dx, dy)
            if (length < 0.5f) continue
            val ux = dx / length
            val uy = dy / length
            // Triangle pointé dans le sens de la marche, inscrit dans l'épaisseur du trait.
            val tip = 7.5f
            val back = 5.5f
            val half = 5.2f
            val arrow = Path().apply {
                moveTo(here.x + ux * tip, here.y + uy * tip)
                lineTo(here.x - ux * back - uy * half, here.y - uy * back + ux * half)
                lineTo(here.x - ux * back + uy * half, here.y - uy * back - ux * half)
                close()
            }
            canvas.drawPath(arrow, paint)
        }
    }

    private fun marker(canvas: Canvas, at: PointF, color: Int, radius: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(at.x, at.y + 2f, radius + 7f, paint.apply { this.color = 0x40000000 })
        canvas.drawCircle(at.x, at.y, radius + 6f, paint.apply { this.color = Color.WHITE })
        canvas.drawCircle(at.x, at.y, radius, paint.apply { this.color = color })
    }

    /** Place les points dans la zone carte en gardant les proportions du terrain (repli sans fond de carte). */
    private fun flatProjection(points: List<LatLon>): (LatLon) -> PointF {
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in points) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = max((maxLon - minLon) * k, 1e-9)
        val spanY = max(maxLat - minLat, 1e-9)
        val width = MAP_WIDTH - 2f * MAP_PADDING
        val height = MAP_HEIGHT - MAP_PADDING - MAP_PADDING_BOTTOM.toFloat()
        val scale = min(width / spanX, height / spanY)
        val left = MAP_PADDING + (width - spanX * scale).toFloat() / 2f
        val top = MAP_PADDING + (height - spanY * scale).toFloat() / 2f
        return { p -> PointF(left + ((p.lon - minLon) * k * scale).toFloat(), top + ((maxLat - p.lat) * scale).toFloat()) }
    }

    private fun drawBrand(context: Context, canvas: Canvas, typeface: Typeface) {
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = 46f; color = BRAND }
        val name = "VeloGo"
        val icon = 44
        val width = 26f + icon + 12f + label.measureText(name) + 30f
        val box = RectF(32f, 32f, 32f + width, 32f + 76f)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawRoundRect(RectF(box.left, box.top + 3f, box.right, box.bottom + 3f), 38f, 38f, plate.apply { color = 0x26000000 })
        canvas.drawRoundRect(box, 38f, 38f, plate.apply { color = Color.WHITE })
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_directions_bike_filled, context.theme)?.mutate()?.let { drawable ->
            DrawableCompat.setTint(drawable, BRAND)
            val left = (box.left + 26f).toInt()
            val top = (box.centerY() - icon / 2f).toInt()
            drawable.setBounds(left, top, left + icon, top + icon)
            drawable.draw(canvas)
        }
        canvas.drawText(name, box.left + 26f + icon + 12f, box.centerY() - (label.ascent() + label.descent()) / 2f, label)
    }

    private fun drawAttribution(canvas: Canvas, attribution: String) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = INK }
        val shown = TextUtils.ellipsize(attribution, paint, WIDTH - 2 * 28f - 24f, TextUtils.TruncateAt.END).toString()
        val width = paint.measureText(shown)
        val bottom = MAP_HEIGHT - CARD_OVERLAP - 12f
        val box = RectF(24f, bottom - 36f, 24f + width + 24f, bottom)
        canvas.drawRoundRect(box, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt() })
        canvas.drawText(shown, box.left + 12f, box.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    // --- Le cartouche : nom, chiffres, profil -----------------------------------------------------------------

    private fun drawCard(canvas: Canvas, route: Route, text: PosterText, bold: Typeface, semiBold: Typeface) {
        val top = (MAP_HEIGHT - CARD_OVERLAP).toFloat()
        val plate = Paint(Paint.ANTI_ALIAS_FLAG)
        // Coins arrondis en haut seulement : le bas du cartouche est celui de l'affiche.
        canvas.drawRoundRect(RectF(0f, top - 4f, WIDTH.toFloat(), HEIGHT + 60f), 44f, 44f, plate.apply { color = 0x1F000000 })
        canvas.drawRoundRect(RectF(0f, top, WIDTH.toFloat(), HEIGHT + 60f), 44f, 44f, plate.apply { color = PAPER })

        val contentWidth = (WIDTH - 2 * SIDE).toInt()
        var y = top + 40f

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 76f; color = INK }
        y += drawParagraph(canvas, text.title.ifBlank { "Sortie vélo" }, titlePaint, contentWidth, y, maxLines = 2, lineSpacing = 0.92f)

        if (text.subtitle.isNotBlank()) {
            val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; color = INK_SOFT }
            y += 6f
            y += drawParagraph(canvas, text.subtitle.trim(), subtitlePaint, contentWidth, y, maxLines = 2, lineSpacing = 1.05f)
        }
        y += 26f

        // Les trois chiffres, en colonnes.
        val (distanceValue, distanceUnit) = if (route.distanceM < 995) Formats.meters(route.distanceM) to "m"
        else Formats.decimal(route.distanceM / 1000.0, if (route.distanceM < 99_950) 1 else 0) to "km"
        val minutes = ((route.durationS + 30) / 60).toLong()
        val figures = listOf(
            Triple("DISTANCE", distanceValue, distanceUnit),
            Triple("DÉNIVELÉ +", Formats.meters(route.ascentM), "m"),
            if (minutes >= 60) Triple("DURÉE ESTIMÉE", "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}", "")
            else Triple("DURÉE ESTIMÉE", minutes.coerceAtLeast(1).toString(), "min"),
        )
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; color = INK_FAINT; letterSpacing = 0.09f; typeface = Typeface.DEFAULT_BOLD }
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semiBold; textSize = 92f; color = INK }
        val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semiBold; textSize = 44f; color = INK_SOFT }
        val column = contentWidth / 3f
        val labelBaseline = y - labelPaint.ascent()
        val valueBaseline = labelBaseline + 12f - valuePaint.ascent() * 0.86f
        figures.forEachIndexed { i, (label, value, unit) ->
            val x = SIDE + i * column
            canvas.drawText(label, x, labelBaseline, labelPaint)
            canvas.drawText(value, x, valueBaseline, valuePaint)
            if (unit.isNotEmpty()) canvas.drawText(unit, x + valuePaint.measureText(value) + 10f, valueBaseline, unitPaint)
        }
        y = valueBaseline + 26f

        // Signature, tout en bas ; le profil prend ce qui reste entre les deux.
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; color = INK_FAINT }
        val footer = "Itinéraire préparé avec VeloGo"
        val footerBaseline = HEIGHT - 30f
        canvas.drawText(footer, WIDTH - SIDE - footerPaint.measureText(footer), footerBaseline, footerPaint)

        val profileBottom = footerBaseline - 44f
        if (profileBottom - y >= 84f) drawProfile(canvas, route, RectF(SIDE, y, WIDTH - SIDE, profileBottom))
    }

    /** Écrit un texte sur [maxLines] lignes au plus (points de suspension au-delà) et renvoie la hauteur prise. */
    private fun drawParagraph(canvas: Canvas, text: String, paint: TextPaint, width: Int, top: Float, maxLines: Int, lineSpacing: Float): Float {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, lineSpacing)
            .setIncludePad(false)
            .build()
        canvas.withTranslation(SIDE, top) { layout.draw(this) }
        return layout.height.toFloat()
    }

    /** Profil d'altitude : la silhouette du parcours, avec ses altitudes extrêmes. */
    private fun drawProfile(canvas: Canvas, route: Route, box: RectF) {
        val elevations = route.elevations ?: return
        if (route.distanceM <= 0.0) return
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        for (e in elevations) { low = min(low, e); high = max(high, e) }
        if (high - low < 1.0) { high += 0.5; low -= 0.5 }
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 23f; color = INK_FAINT }
        val highLabel = "${Formats.meters(high)} m"
        val lowLabel = "${Formats.meters(low)} m"
        val left = box.left + max(labelPaint.measureText(highLabel), labelPaint.measureText(lowLabel)) + 14f
        val top = box.top + 8f
        val bottom = box.bottom
        val height = bottom - top
        fun x(distance: Double) = left + (distance / route.distanceM * (box.right - left)).toFloat()
        fun y(elevation: Double) = bottom - ((elevation - low) / (high - low) * height * 0.9).toFloat() - height * 0.04f

        val line = Path()
        val area = Path()
        val step = (elevations.size / 600).coerceAtLeast(1)
        var i = 0
        var first = true
        while (i < elevations.size) {
            val px = x(route.distances[i])
            val py = y(elevations[i])
            if (first) { line.moveTo(px, py); area.moveTo(px, bottom); area.lineTo(px, py) } else { line.lineTo(px, py); area.lineTo(px, py) }
            first = false
            i = if (i == elevations.size - 1) elevations.size else min(i + step, elevations.size - 1)
        }
        area.lineTo(box.right, bottom)
        area.close()
        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, bottom, 0x66006B54, 0x0A006B54, Shader.TileMode.CLAMP)
        })
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5f; color = BRAND; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        canvas.drawLine(left, bottom, box.right, bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000; strokeWidth = 2f })
        canvas.drawText(highLabel, box.left, y(high) - labelPaint.ascent() / 2f, labelPaint)
        canvas.drawText(lowLabel, box.left, bottom - 4f, labelPaint)
    }

    private fun font(context: Context, id: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: fallback
}
