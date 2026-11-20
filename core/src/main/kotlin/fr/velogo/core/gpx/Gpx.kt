package fr.velogo.core.gpx

import fr.velogo.core.ride.TrackPoint
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/** Point de passage nommé d'un fichier GPX. */
data class GpxWaypoint(val lat: Double, val lon: Double, val name: String?, val altitudeM: Double?)

/** Une trace ou un itinéraire lu dans un fichier GPX. */
data class GpxTrack(val name: String?, val points: List<TrackPoint>, val isRoute: Boolean)

data class GpxFile(val name: String?, val tracks: List<GpxTrack>, val waypoints: List<GpxWaypoint>) {
    /** Tous les points, traces d'abord, dans l'ordre du fichier. */
    val allPoints: List<TrackPoint> get() = tracks.flatMap { it.points }
}

/** Lecture et écriture du format GPX 1.1, le format d'échange standard des traces GPS. */
object Gpx {

    /** Écrit une trace GPX. Les tronçons (pauses) deviennent des <trkseg> séparés. */
    fun write(out: Appendable, name: String, points: List<TrackPoint>, creator: String = "VeloGo", type: String = "cycling") {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"").append(escape(creator)).append("\" ")
        out.append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        out.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        out.append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        out.append("  <metadata>\n    <name>").append(escape(name)).append("</name>\n")
        points.firstOrNull()?.let { out.append("    <time>").append(isoTime(it.timeMs)).append("</time>\n") }
        out.append("  </metadata>\n")
        out.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        out.append("    <type>").append(escape(type)).append("</type>\n")
        var currentSegment: Int? = null
        for (p in points) {
            if (p.segment != currentSegment) {
                if (currentSegment != null) out.append("    </trkseg>\n")
                out.append("    <trkseg>\n")
                currentSegment = p.segment
            }
            out.append("      <trkpt lat=\"").append(coordinate(p.lat)).append("\" lon=\"").append(coordinate(p.lon)).append("\">")
            p.altitudeM?.let { out.append("<ele>").append(String.format(Locale.ROOT, "%.1f", it)).append("</ele>") }
            if (p.timeMs > 0) out.append("<time>").append(isoTime(p.timeMs)).append("</time>")
            out.append("</trkpt>\n")
        }
        if (currentSegment != null) out.append("    </trkseg>\n")
        out.append("  </trk>\n</gpx>\n")
    }

    /** Écrit un itinéraire (sans horodatage) sous forme de trace, le format le mieux reconnu par les autres applis. */
    fun writeRoute(out: Appendable, name: String, points: List<TrackPoint>, waypoints: List<GpxWaypoint> = emptyList()) {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"VeloGo\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.append("  <metadata>\n    <name>").append(escape(name)).append("</name>\n  </metadata>\n")
        for (w in waypoints) {
            out.append("  <wpt lat=\"").append(coordinate(w.lat)).append("\" lon=\"").append(coordinate(w.lon)).append("\">")
            w.altitudeM?.let { out.append("<ele>").append(String.format(Locale.ROOT, "%.1f", it)).append("</ele>") }
            w.name?.let { out.append("<name>").append(escape(it)).append("</name>") }
            out.append("</wpt>\n")
        }
        out.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            out.append("      <trkpt lat=\"").append(coordinate(p.lat)).append("\" lon=\"").append(coordinate(p.lon)).append("\">")
            p.altitudeM?.let { out.append("<ele>").append(String.format(Locale.ROOT, "%.1f", it)).append("</ele>") }
            out.append("</trkpt>\n")
        }
        out.append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    /** Lit un fichier GPX (traces <trk>, itinéraires <rte> et points <wpt>). */
    fun read(input: InputStream): GpxFile {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // Pas de DTD ni d'entités externes : un GPX n'en a pas besoin et c'est une porte d'entrée connue.
        for (feature in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) {
            try { factory.setFeature(feature.first, feature.second) } catch (_: Exception) { }
        }
        val handler = Handler()
        factory.newSAXParser().parse(input, handler)
        return GpxFile(handler.fileName, handler.tracks, handler.waypoints)
    }

    private class Handler : DefaultHandler() {
        var fileName: String? = null
        val tracks = ArrayList<GpxTrack>()
        val waypoints = ArrayList<GpxWaypoint>()

        private val path = ArrayList<String>()
        private val text = StringBuilder()
        private var points = ArrayList<TrackPoint>()
        private var trackName: String? = null
        private var segment = 0
        private var lat = 0.0
        private var lon = 0.0
        private var ele: Double? = null
        private var time = 0L
        private var pointName: String? = null
        private var inPoint = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val tag = tagName(localName, qName)
            path.add(tag)
            text.setLength(0)
            when (tag) {
                "trk", "rte" -> { points = ArrayList(); trackName = null; segment = 0 }
                "trkpt", "rtept", "wpt" -> {
                    lat = attributes.getValue("lat")?.toDoubleOrNull() ?: Double.NaN
                    lon = attributes.getValue("lon")?.toDoubleOrNull() ?: Double.NaN
                    ele = null; time = 0L; pointName = null; inPoint = true
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val tag = tagName(localName, qName)
            val value = text.toString().trim()
            val parent = if (path.size >= 2) path[path.size - 2] else ""
            when (tag) {
                "ele" -> if (inPoint) ele = value.toDoubleOrNull()
                "time" -> if (inPoint) time = parseTime(value)
                "name" -> when {
                    inPoint -> pointName = value.ifEmpty { null }
                    parent == "trk" || parent == "rte" -> trackName = value.ifEmpty { null }
                    parent == "metadata" || parent == "gpx" -> fileName = value.ifEmpty { null }
                }
                "trkpt", "rtept" -> {
                    if (!lat.isNaN() && !lon.isNaN()) points.add(TrackPoint(time, lat, lon, ele, null, null, segment))
                    inPoint = false
                }
                "wpt" -> {
                    if (!lat.isNaN() && !lon.isNaN()) waypoints.add(GpxWaypoint(lat, lon, pointName, ele))
                    inPoint = false
                }
                "trkseg" -> segment++
                "trk" -> if (points.isNotEmpty()) tracks.add(GpxTrack(trackName, points, isRoute = false))
                "rte" -> if (points.isNotEmpty()) tracks.add(GpxTrack(trackName, points, isRoute = true))
            }
            if (path.isNotEmpty()) path.removeAt(path.size - 1)
            text.setLength(0)
        }

        private fun tagName(localName: String?, qName: String?): String {
            val n = if (!localName.isNullOrEmpty()) localName else qName.orEmpty()
            return n.substringAfter(':').lowercase(Locale.ROOT)
        }
    }

    private fun parseTime(value: String): Long {
        if (value.isEmpty()) return 0L
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try { OffsetDateTime.parse(value).toInstant().toEpochMilli() } catch (_: Exception) { 0L }
        }
    }

    private fun isoTime(timeMs: Long): String = Instant.ofEpochMilli(timeMs).toString()

    private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.7f", value)

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (c.code >= 0x20 || c == '\n' || c == '\t') sb.append(c)
            }
        }
        return sb.toString()
    }
}
