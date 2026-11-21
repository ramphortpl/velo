package fr.velogo.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.ui.components.ChartSeries
import fr.velogo.app.ui.segments.RideSegmentRow
import fr.velogo.app.ui.segments.rideBestRows
import fr.velogo.app.ui.segments.rideSegmentRows
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Simplify
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.ProfileSample
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TrackPoint
import fr.velogo.core.segments.SegmentCutter
import fr.velogo.core.segments.SegmentMeasures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tout ce qu'il faut pour afficher une sortie : trace pour la carte et séries pour les graphiques. */
class RideTrackData(
    val segments: List<List<LatLon>>,
    val bounds: BoundingBox?,
    val profile: List<ProfileSample>,
    val altitude: ChartSeries,
    val speed: ChartSeries,
)

class RideDetailViewModel(private val container: AppContainer, private val rideId: Long) : ViewModel() {

    val ride: StateFlow<RideEntity?> =
        container.rides.observeRide(rideId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _track = MutableStateFlow<RideTrackData?>(null)
    val track: StateFlow<RideTrackData?> = _track

    private var points: List<TrackPoint> = emptyList()

    /** Découpe les morceaux de cette sortie pour en faire des segments (prêt en même temps que la trace). */
    private var cutter: SegmentCutter? = null

    /** Les passages de cette sortie sur les segments personnels, classés parmi tous les passages. */
    val segments: StateFlow<List<RideSegmentRow>> =
        combine(container.segments.observeSegments(), container.segments.observeEfforts(), container.rides.observeRides()) { segments, efforts, rides ->
            rideSegmentRows(rideId, segments, efforts, rides.mapTo(HashSet()) { it.id })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Les distances de référence sur lesquelles cette sortie détient le record : (« 10 km », « 21:34 »). */
    val bests: StateFlow<List<Pair<String, String>>> =
        combine(container.database.bestEfforts().observeAll(), container.rides.observeRides()) { efforts, rides ->
            rideBestRows(rideId, efforts, rides.mapTo(HashSet()) { it.id })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // On recharge la trace si l'altitude vient d'être recalée sur le relief.
            container.rides.observeRide(rideId).filterNotNull().distinctUntilChangedBy { it.altitudeSource to it.pointCount }.collect { ride ->
                val loaded = container.rides.points(rideId)
                val (data, prepared) = withContext(Dispatchers.Default) { build(ride, loaded) to SegmentCutter(loaded, altitudeSource(ride)) }
                points = loaded
                cutter = prepared
                _track.value = data
            }
        }
    }

    private fun altitudeSource(ride: RideEntity) =
        if (ride.altitudeSource == RideEntity.ALTITUDE_GNSS) AltitudeSource.GNSS else AltitudeSource.TERRAIN

    private fun build(ride: RideEntity, points: List<TrackPoint>): RideTrackData {
        val source = altitudeSource(ride)
        val segments = points.groupBy { it.segment }.toSortedMap().values.map { seg ->
            val line = seg.map { LatLon(it.lat, it.lon) }
            if (line.size > MAX_SEGMENT_POINTS) Simplify.toMaxPoints(line, MAX_SEGMENT_POINTS) else line
        }
        val profile = RideAnalyzer.profile(points, maxSamples = 360, source = source)
        val xs = FloatArray(profile.size) { (profile[it].distanceM / 1000.0).toFloat() }
        return RideTrackData(
            segments = segments,
            bounds = BoundingBox.of(points.map { LatLon(it.lat, it.lon) }),
            profile = profile,
            altitude = ChartSeries(xs, FloatArray(profile.size) { profile[it].altitudeM?.toFloat() ?: Float.NaN }),
            speed = ChartSeries(xs, FloatArray(profile.size) { profile[it].speedMps?.let { v -> (v * 3.6).toFloat() } ?: Float.NaN }),
        )
    }

    fun points(): List<TrackPoint> = points

    /** Le morceau de la trace compris entre deux distances depuis le départ (pour le montrer sur la carte). */
    fun portion(fromM: Double, toM: Double): List<LatLon> {
        val line = cutter?.line(fromM, toM).orEmpty()
        return if (line.size > MAX_SEGMENT_POINTS) Simplify.toMaxPoints(line, MAX_SEGMENT_POINTS) else line
    }

    /** Les mesures de ce morceau : exactement celles qu'aura le segment si on le crée. */
    fun measure(fromM: Double, toM: Double): SegmentMeasures? = cutter?.measure(fromM, toM)

    /**
     * Crée un segment à partir d'un morceau de cette sortie, puis le cherche dans toutes les autres. [onDone]
     * reçoit son identifiant, ou null si le morceau est trop court.
     */
    fun createSegment(name: String, fromM: Double, toM: Double, onDone: (Long?) -> Unit) {
        // Dans le périmètre de l'appli : la recherche dans les anciennes sorties continue si l'on quitte l'écran.
        container.appScope.launch {
            val id = runCatching { container.segments.create(name, rideId, fromM, toM) }.getOrNull()
            if (id != null) launch { runCatching { container.analysis.matchSegment(id) } }
            withContext(Dispatchers.Main) { onDone(id) }
        }
    }

    fun rename(name: String) = viewModelScope.launch { container.rides.rename(rideId, name) }

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        container.rides.delete(rideId)
        onDeleted()
    }

    private companion object {
        const val MAX_SEGMENT_POINTS = 3_000
    }
}
