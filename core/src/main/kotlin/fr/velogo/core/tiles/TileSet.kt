package fr.velogo.core.tiles

import java.io.Closeable
import java.io.IOException

/**
 * Plusieurs fichiers PMTiles vus comme une seule carte : une région téléchargée par fichier, plus la vue
 * d'ensemble de la France. La première archive qui possède la tuile répond ; l'ordre de [readers] donne donc
 * la priorité (les régions détaillées avant la vue d'ensemble).
 */
class TileSet(private val readers: List<PmTilesReader>) : Closeable {

    val isEmpty: Boolean get() = readers.isEmpty()

    /** Zoom le plus détaillé disponible, ou -1 s'il n'y a aucune archive. */
    val maxZoom: Int get() = readers.maxOfOrNull { it.header.maxZoom } ?: -1

    /** Tuile prête à l'emploi (décompressée), ou null si aucune archive ne la contient. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        for (reader in readers) {
            if (!reader.mayContain(z, x, y)) continue
            try {
                reader.tile(z, x, y)?.let { return it }
            } catch (_: IOException) {
                // Archive fermée entre-temps (pack supprimé ou remplacé) ou abîmée : on essaie la suivante.
            }
        }
        return null
    }

    override fun close() {
        for (reader in readers) runCatching { reader.close() }
    }

    companion object {
        val EMPTY = TileSet(emptyList())
    }
}
