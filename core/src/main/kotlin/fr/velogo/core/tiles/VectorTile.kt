package fr.velogo.core.tiles

/**
 * Un objet linéaire d'une tuile vectorielle : ses attributs texte et ses lignes.
 *
 * Chaque ligne est une suite `x0, y0, x1, y1, …` en unités de tuile : (0, 0) est le coin nord-ouest de la tuile,
 * ([VectorLayer.extent], [VectorLayer.extent]) son coin sud-est ; un objet peut déborder un peu de la tuile.
 */
class TileLine(val attributes: Map<String, String>, val lines: List<IntArray>)

/** Les objets linéaires d'une couche de tuile vectorielle. */
class VectorLayer(val extent: Int, val lines: List<TileLine>)

/**
 * Lecteur minimal de tuiles vectorielles (format « Mapbox Vector Tile », celui des cartes hors ligne) : il sait
 * seulement en sortir les lignes d'une couche avec leurs attributs texte. C'est ce qu'il faut pour retrouver le
 * nom des rues qu'emprunte un itinéraire ; la carte elle-même est dessinée par MapLibre, qui a son propre lecteur.
 */
object VectorTile {
    private const val LINESTRING = 2
    private const val MOVE_TO = 1
    private const val LINE_TO = 2

    /**
     * Les lignes de la couche [layerName] de la tuile [tile] (décompressée), avec ceux de leurs attributs texte
     * dont le nom figure dans [wanted]. Renvoie null si la couche n'existe pas ou si la tuile est illisible.
     */
    fun lines(tile: ByteArray, layerName: String, wanted: Set<String>): VectorLayer? = runCatching {
        val reader = Reader(tile, 0, tile.size)
        while (reader.hasMore()) {
            val key = reader.varint().toInt()
            if (key ushr 3 == 3 && key and 7 == 2) {
                val length = reader.varint().toInt()
                val layer = layer(Reader(tile, reader.position, reader.position + length), layerName, wanted)
                if (layer != null) return@runCatching layer
                reader.position += length
            } else {
                reader.skip(key and 7)
            }
        }
        null
    }.getOrNull()

    private fun layer(reader: Reader, layerName: String, wanted: Set<String>): VectorLayer? {
        val bytes = reader.bytes
        var name: String? = null
        var extent = 4096
        val keys = ArrayList<String>()
        // Valeurs de la couche : seules les chaînes nous intéressent, les autres restent à null.
        val values = ArrayList<String?>()
        val features = ArrayList<IntArray>()
        while (reader.hasMore()) {
            val key = reader.varint().toInt()
            val field = key ushr 3
            val wire = key and 7
            when {
                // Le nom vient en tête : une autre couche est écartée sans lire son contenu.
                field == 1 && wire == 2 -> name = reader.string().also { if (it != layerName) return null }
                field == 2 && wire == 2 -> {
                    val length = reader.varint().toInt()
                    features.add(intArrayOf(reader.position, reader.position + length))
                    reader.position += length
                }
                field == 3 && wire == 2 -> keys.add(reader.string())
                field == 4 && wire == 2 -> {
                    val length = reader.varint().toInt()
                    values.add(stringValue(Reader(bytes, reader.position, reader.position + length)))
                    reader.position += length
                }
                field == 5 && wire == 0 -> extent = reader.varint().toInt()
                else -> reader.skip(wire)
            }
        }
        if (name != layerName) return null
        val lines = ArrayList<TileLine>()
        for ((start, end) in features) feature(Reader(bytes, start, end), keys, values, wanted)?.let(lines::add)
        return VectorLayer(extent, lines)
    }

    private fun stringValue(reader: Reader): String? {
        var text: String? = null
        while (reader.hasMore()) {
            val key = reader.varint().toInt()
            if (key ushr 3 == 1 && key and 7 == 2) text = reader.string() else reader.skip(key and 7)
        }
        return text
    }

    private fun feature(reader: Reader, keys: List<String>, values: List<String?>, wanted: Set<String>): TileLine? {
        var type = 0
        var tags: IntArray? = null
        var geometry: IntArray? = null
        while (reader.hasMore()) {
            val key = reader.varint().toInt()
            val field = key ushr 3
            val wire = key and 7
            when {
                field == 2 && wire == 2 -> tags = reader.packed()
                field == 3 && wire == 0 -> type = reader.varint().toInt()
                field == 4 && wire == 2 -> geometry = reader.packed()
                else -> reader.skip(wire)
            }
        }
        if (type != LINESTRING || geometry == null) return null
        val attributes = HashMap<String, String>()
        tags?.let { t ->
            var i = 0
            while (i + 1 < t.size) {
                val name = keys.getOrNull(t[i])
                if (name != null && name in wanted) values.getOrNull(t[i + 1])?.let { attributes[name] = it }
                i += 2
            }
        }
        return TileLine(attributes, decodeLines(geometry))
    }

    /** Les commandes de tracé d'un objet (aller à, tracer jusqu'à, en déplacements relatifs) en suites de points. */
    private fun decodeLines(geometry: IntArray): List<IntArray> {
        val lines = ArrayList<IntArray>()
        var current = IntArray(16)
        var size = 0
        var x = 0
        var y = 0
        var i = 0
        fun flush() {
            if (size >= 4) lines.add(current.copyOf(size))
            size = 0
        }
        while (i < geometry.size) {
            val command = geometry[i] and 7
            val count = geometry[i] ushr 3
            i++
            if (command != MOVE_TO && command != LINE_TO) continue
            repeat(count) {
                if (i + 1 >= geometry.size) return@repeat
                x += zigzag(geometry[i])
                y += zigzag(geometry[i + 1])
                i += 2
                if (command == MOVE_TO) flush()
                if (size + 2 > current.size) current = current.copyOf(current.size * 2)
                current[size++] = x
                current[size++] = y
            }
        }
        flush()
        return lines
    }

    private fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)

    /** Lecture pas à pas d'un message « protobuf » entre deux positions d'un tableau d'octets. */
    private class Reader(val bytes: ByteArray, var position: Int, private val end: Int) {
        fun hasMore(): Boolean = position < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[position++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "entier trop long" }
            }
        }

        fun string(): String {
            val length = varint().toInt()
            require(length >= 0 && position + length <= end) { "chaîne tronquée" }
            return String(bytes, position, length, Charsets.UTF_8).also { position += length }
        }

        /** Une suite d'entiers écrits à la file (« packed »). */
        fun packed(): IntArray {
            val length = varint().toInt()
            val stop = position + length
            require(length >= 0 && stop <= end) { "suite tronquée" }
            var out = IntArray(length.coerceAtLeast(1))
            var n = 0
            while (position < stop) {
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = varint().toInt()
            }
            return out.copyOf(n)
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> position += 8
                2 -> position += varint().toInt()
                5 -> position += 4
                else -> throw IllegalArgumentException("champ illisible")
            }
            require(position <= end) { "message tronqué" }
        }
    }
}
