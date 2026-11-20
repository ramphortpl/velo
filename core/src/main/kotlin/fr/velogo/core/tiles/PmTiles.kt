package fr.velogo.core.tiles

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

/** Coordonnées d'une tuile (schéma XYZ habituel : y = 0 au nord). */
data class TileId(val z: Int, val x: Int, val y: Int)

/**
 * Lecteur de fichiers PMTiles (version 3) : un seul fichier qui contient toutes les tuiles d'une région.
 * Spécification : https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 *
 * Les lectures se font par position dans le fichier : plusieurs fils peuvent lire en même temps sans se gêner.
 * Les répertoires (la table des matières) sont gardés en mémoire après leur première lecture.
 */
class PmTilesReader private constructor(private val channel: FileChannel, val header: Header) : Closeable {

    /** En-tête du fichier : emprise, zooms et format des tuiles. */
    data class Header(
        val rootDirectoryOffset: Long,
        val rootDirectoryLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafDirectoriesOffset: Long,
        val tileDataOffset: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double,
    ) {
        val isGzippedTiles: Boolean get() = tileCompression == COMPRESSION_GZIP
    }

    private class Directory(val tileIds: LongArray, val runLengths: IntArray, val lengths: IntArray, val offsets: LongArray)

    private val root: Directory = readDirectory(header.rootDirectoryOffset, header.rootDirectoryLength.toInt())
    private val leaves = object : LinkedHashMap<Long, Directory>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Directory>?): Boolean = size > MAX_CACHED_LEAVES
    }

    /** Vrai si la tuile peut se trouver dans ce fichier (bon zoom et emprise qui se recoupent). */
    fun mayContain(z: Int, x: Int, y: Int): Boolean {
        if (z < header.minZoom || z > header.maxZoom) return false
        val n = (1 shl z).toDouble()
        val west = x / n * 360.0 - 180.0
        val east = (x + 1) / n * 360.0 - 180.0
        val north = tileLat(y, n)
        val south = tileLat(y + 1, n)
        return east >= header.minLon && west <= header.maxLon && north >= header.minLat && south <= header.maxLat
    }

    /** Octets de la tuile tels qu'ils sont stockés (éventuellement compressés en gzip), ou null si elle n'existe pas. */
    fun rawTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val target = zxyToTileId(z, x, y)
        var directory = root
        repeat(MAX_DEPTH) {
            val index = find(directory.tileIds, target)
            if (index < 0) return null
            val run = directory.runLengths[index]
            if (run == 0) {
                // Entrée de renvoi vers un sous-répertoire.
                directory = leaf(header.leafDirectoriesOffset + directory.offsets[index], directory.lengths[index])
            } else {
                if (target >= directory.tileIds[index] + run) return null
                return read(header.tileDataOffset + directory.offsets[index], directory.lengths[index])
            }
        }
        return null
    }

    /** Octets de la tuile, décompressés si le fichier les stocke en gzip. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val raw = rawTile(z, x, y) ?: return null
        return if (header.isGzippedTiles && raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) gunzip(raw) else raw
    }

    /** Métadonnées JSON du fichier (attribution, liste des couches…). */
    fun metadata(): String {
        if (header.metadataLength <= 0) return "{}"
        val raw = read(header.metadataOffset, header.metadataLength.toInt())
        return String(if (header.internalCompression == COMPRESSION_GZIP) gunzip(raw) else raw, Charsets.UTF_8)
    }

    override fun close() = channel.close()

    private fun leaf(offset: Long, length: Int): Directory {
        synchronized(leaves) { leaves[offset]?.let { return it } }
        val directory = readDirectory(offset, length)
        synchronized(leaves) { leaves[offset] = directory }
        return directory
    }

    private fun readDirectory(offset: Long, length: Int): Directory {
        val raw = read(offset, length)
        val bytes = when (header.internalCompression) {
            COMPRESSION_GZIP -> gunzip(raw)
            COMPRESSION_NONE, COMPRESSION_UNKNOWN -> raw
            else -> throw IOException("Compression de répertoire non gérée : ${header.internalCompression}")
        }
        val reader = VarintReader(bytes)
        val count = reader.next().toInt()
        val tileIds = LongArray(count)
        val runLengths = IntArray(count)
        val lengths = IntArray(count)
        val offsets = LongArray(count)
        var last = 0L
        for (i in 0 until count) { last += reader.next(); tileIds[i] = last }
        for (i in 0 until count) runLengths[i] = reader.next().toInt()
        for (i in 0 until count) lengths[i] = reader.next().toInt()
        for (i in 0 until count) {
            val value = reader.next()
            // 0 signifie « juste après la tuile précédente » : économise des octets dans les fichiers ordonnés.
            offsets[i] = if (value == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else value - 1
        }
        return Directory(tileIds, runLengths, lengths, offsets)
    }

    private fun read(offset: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position)
            if (n < 0) throw IOException("Fichier PMTiles tronqué")
            position += n
        }
        return buffer.array()
    }

    private class VarintReader(private val bytes: ByteArray) {
        private var position = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[position++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }

    companion object {
        const val COMPRESSION_UNKNOWN = 0
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        const val TYPE_MVT = 1
        const val TYPE_PNG = 2
        const val TYPE_JPEG = 3
        const val TYPE_WEBP = 4
        private const val HEADER_LENGTH = 127
        private const val MAX_DEPTH = 4
        private const val MAX_CACHED_LEAVES = 64

        fun open(file: File): PmTilesReader {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            try {
                val buffer = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.hasRemaining()) if (channel.read(buffer, buffer.position().toLong()) < 0) break
                if (buffer.hasRemaining()) throw IOException("Fichier trop court pour être un PMTiles")
                val magic = ByteArray(7)
                buffer.position(0)
                buffer.get(magic)
                if (String(magic, Charsets.US_ASCII) != "PMTiles") throw IOException("Ce n'est pas un fichier PMTiles")
                val version = buffer.get(7).toInt()
                if (version != 3) throw IOException("Version PMTiles non gérée : $version")
                val header = Header(
                    rootDirectoryOffset = buffer.getLong(8),
                    rootDirectoryLength = buffer.getLong(16),
                    metadataOffset = buffer.getLong(24),
                    metadataLength = buffer.getLong(32),
                    leafDirectoriesOffset = buffer.getLong(40),
                    tileDataOffset = buffer.getLong(56),
                    internalCompression = buffer.get(97).toInt(),
                    tileCompression = buffer.get(98).toInt(),
                    tileType = buffer.get(99).toInt(),
                    minZoom = buffer.get(100).toInt(),
                    maxZoom = buffer.get(101).toInt(),
                    minLon = buffer.getInt(102) / 1e7,
                    minLat = buffer.getInt(106) / 1e7,
                    maxLon = buffer.getInt(110) / 1e7,
                    maxLat = buffer.getInt(114) / 1e7,
                )
                return PmTilesReader(channel, header)
            } catch (e: Exception) {
                channel.close()
                throw e
            }
        }

        /** Numéro d'une tuile dans l'ordre du fichier : les zooms à la suite, chacun parcouru selon une courbe de Hilbert. */
        fun zxyToTileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..26) { "zoom hors limites" }
            var id = ((1L shl (2 * z)) - 1) / 3
            var tx = x.toLong()
            var ty = y.toLong()
            var s = (1L shl z) / 2
            while (s > 0) {
                val rx = if (tx and s > 0) 1L else 0L
                val ry = if (ty and s > 0) 1L else 0L
                id += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = s - 1 - tx
                        ty = s - 1 - ty
                    }
                    val t = tx
                    tx = ty
                    ty = t
                }
                s /= 2
            }
            return id
        }

        /** Indice de la dernière entrée dont le numéro est inférieur ou égal à [target], ou -1. */
        private fun find(tileIds: LongArray, target: Long): Int {
            var lo = 0
            var hi = tileIds.size - 1
            var result = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (tileIds[mid] <= target) { result = mid; lo = mid + 1 } else hi = mid - 1
            }
            return result
        }

        private fun tileLat(y: Int, n: Double): Double {
            val t = Math.PI * (1 - 2 * y / n)
            return Math.toDegrees(Math.atan(Math.sinh(t)))
        }

        fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }
}
