package fr.velogo.core.tiles

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/** Position d'un point dans la pyramide de tuiles : numéro de tuile et position (0..1) à l'intérieur. */
data class TilePosition(val z: Int, val x: Int, val y: Int, val fx: Double, val fy: Double)

/** Géométrie des tuiles « Web Mercator » (le découpage de toutes les cartes en ligne). */
object TileMath {
    fun position(lat: Double, lon: Double, z: Int): TilePosition {
        val n = (1 shl z).toDouble()
        val xf = ((lon + 180.0) / 360.0 * n).coerceIn(0.0, n - 1e-9)
        val yf = ((1.0 - asinh(tan(Math.toRadians(lat.coerceIn(-85.05, 85.05)))) / PI) / 2.0 * n).coerceIn(0.0, n - 1e-9)
        val x = floor(xf).toInt()
        val y = floor(yf).toInt()
        return TilePosition(z, x, y, xf - x, yf - y)
    }
}

/**
 * Une tuile de relief décodée : altitude en mètres de chaque pixel.
 *
 * Encodage « Terrarium » (celui de Mapterhorn) : altitude = rouge × 256 + vert + bleu / 256 − 32768.
 */
class ElevationTile(val size: Int, private val meters: FloatArray) {

    /** Altitude au point (fx, fy) de la tuile, coordonnées entre 0 et 1, par interpolation bilinéaire. */
    fun sample(fx: Double, fy: Double): Double {
        // Le centre du pixel (0, 0) est à 0,5 pixel du bord de la tuile.
        val px = (fx * size - 0.5).coerceIn(0.0, size - 1.0)
        val py = (fy * size - 0.5).coerceIn(0.0, size - 1.0)
        val x0 = floor(px).toInt().coerceAtMost(size - 2).coerceAtLeast(0)
        val y0 = floor(py).toInt().coerceAtMost(size - 2).coerceAtLeast(0)
        val tx = px - x0
        val ty = py - y0
        val a = meters[y0 * size + x0]
        val b = meters[y0 * size + x0 + 1]
        val c = meters[(y0 + 1) * size + x0]
        val d = meters[(y0 + 1) * size + x0 + 1]
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }

    companion object {
        /** Décode une image dont chaque pixel est un entier ARGB (le format des images Android). */
        fun fromArgb(size: Int, pixels: IntArray): ElevationTile {
            require(pixels.size == size * size) { "image de relief de taille inattendue" }
            val meters = FloatArray(pixels.size)
            for (i in pixels.indices) meters[i] = terrarium(pixels[i]).toFloat()
            return ElevationTile(size, meters)
        }

        fun terrarium(argb: Int): Double {
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            return r * 256.0 + g + b / 256.0 - 32768.0
        }

        /** Encodage inverse (sert aux tests). */
        fun encode(meters: Double): Int {
            val v = meters + 32768.0
            val r = floor(v / 256.0).toInt().coerceIn(0, 255)
            val g = floor(v - r * 256.0).toInt().coerceIn(0, 255)
            val b = floor((v - r * 256.0 - g) * 256.0).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
