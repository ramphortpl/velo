package fr.velogo.app.elevation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import fr.velogo.app.offline.OfflineTiles
import fr.velogo.core.tiles.ElevationTile
import fr.velogo.core.tiles.TileMath

/**
 * Altitude du sol d'après les packs de relief installés (données LiDAR de l'IGN, diffusées par Mapterhorn).
 *
 * Le téléphone n'ayant pas de baromètre, c'est ce relief qui donne en direct une altitude, une pente et un
 * dénivelé fiables, y compris sans réseau. Au zoom 12, un pixel couvre environ 13 m en France.
 *
 * Les tuiles décodées sont gardées en mémoire (1 Mo chacune) : en roulant, on ne change de tuile que tous les
 * 6 à 7 km, le coût est donc négligeable.
 */
class ReliefSource(private val tiles: () -> OfflineTiles) {

    private var epoch = -1
    private val cache = object : LinkedHashMap<Long, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean = size > MAX_TILES
    }

    /** Tuile décodée, ou absence de tuile (hors des régions téléchargées) : on retient aussi les absences. */
    private class Entry(val tile: ElevationTile?)

    /** Vrai si au moins un pack de relief est installé. */
    val isAvailable: Boolean get() = !tiles().relief.isEmpty

    /** Altitude du sol en mètres au-dessus du niveau de la mer, ou null hors des régions téléchargées. */
    @Synchronized
    fun altitude(lat: Double, lon: Double): Double? {
        val current = tiles()
        if (current.relief.isEmpty) return null
        if (current.epoch != epoch) {
            cache.clear()
            epoch = current.epoch
        }
        val position = TileMath.position(lat, lon, ZOOM)
        val key = position.x.toLong() shl 32 or position.y.toLong()
        val entry = cache.getOrPut(key) {
            Entry(current.relief.tile(ZOOM, position.x, position.y)?.let { decode(it) })
        }
        val meters = entry.tile?.sample(position.fx, position.fy) ?: return null
        // Garde-fou : une tuile illisible ou vide ne doit pas donner une altitude fantaisiste.
        return meters.takeIf { it > MIN_PLAUSIBLE_M && it < MAX_PLAUSIBLE_M }
    }

    private fun decode(bytes: ByteArray): ElevationTile? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Les couleurs codent des altitudes : il ne faut surtout pas que le décodeur les retouche.
            inPremultiplied = false
            inScaled = false
        }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull() ?: return null
        return try {
            if (bitmap.width != bitmap.height || bitmap.width < 2) return null
            val size = bitmap.width
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            ElevationTile.fromArgb(size, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        /** Zoom le plus détaillé des packs de relief. */
        const val ZOOM = 12
        const val MAX_TILES = 8
        const val MIN_PLAUSIBLE_M = -50.0
        const val MAX_PLAUSIBLE_M = 5_000.0
    }
}
