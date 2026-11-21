package fr.velogo.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import fr.velogo.app.data.db.SavedRouteEntity
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.app.nav.RouteCodec
import fr.velogo.core.gpx.Gpx
import fr.velogo.core.gpx.GpxWaypoint
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.Route
import fr.velogo.core.ride.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Les itinéraires que l'utilisateur a enregistrés, et leur export au format GPX. */
class SavedRouteRepository(db: VeloGoDatabase) {
    private val dao = db.savedRoutes()

    /** La liste (sans le détail des tracés), la plus récente d'abord. */
    fun observe(): Flow<List<SavedRouteEntity>> = dao.observeSummaries()

    suspend fun save(name: String, route: Route, bike: BikeType): Long = dao.insert(
        SavedRouteEntity(
            name = name.trim().ifEmpty { "Itinéraire" },
            createdAtMs = System.currentTimeMillis(),
            bike = bike.name,
            distanceM = route.distanceM,
            ascentM = route.ascentM,
            durationS = route.durationS,
            data = RouteCodec.encode(route),
        )
    )

    /** L'itinéraire complet, ou null s'il n'existe plus ou n'est plus lisible. */
    suspend fun load(id: Long): Pair<SavedRouteEntity, Route>? {
        val entity = dao.get(id) ?: return null
        val route = RouteCodec.decode(entity.data) ?: return null
        return entity to route
    }

    suspend fun delete(id: Long) = dao.delete(id)

    companion object {
        /** Écrit l'itinéraire en GPX dans le dossier de partage et renvoie l'intention « Partager ». */
        suspend fun shareIntent(context: Context, name: String, route: Route): Intent = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val safeName = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "itineraire" }.take(60)
            val file = File(dir, "VeloGo_$safeName.gpx")
            val points = route.points.mapIndexed { i, p -> TrackPoint(timeMs = 0, lat = p.lat, lon = p.lon, altitudeM = route.elevations?.get(i)) }
            val waypoints = route.waypoints.mapIndexed { i, w ->
                val label = when (i) {
                    0 -> "Départ"
                    route.waypoints.size - 1 -> "Arrivée"
                    else -> "Étape $i"
                }
                GpxWaypoint(w.lat, w.lon, label, null)
            }
            file.bufferedWriter(Charsets.UTF_8).use { Gpx.writeRoute(it, name, points, waypoints) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(GpxExport.MIME)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Partager l'itinéraire (GPX)",
            )
        }
    }
}
