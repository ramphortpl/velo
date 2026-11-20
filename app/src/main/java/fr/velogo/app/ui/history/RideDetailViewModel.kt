package fr.velogo.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.ui.components.ChartSeries
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Simplify
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.ProfileSample
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    init {
        viewModelScope.launch {
            // On recharge la trace si l'altitude vient d'être recalée sur le relief.
            container.rides.observeRide(rideId).filterNotNull().distinctUntilChangedBy { it.altitudeSource to it.pointCount }.collect { ride ->
                val loaded = container.rides.points(rideId)
                points = loaded
                _track.value = withContext(Dispatchers.Default) { build(ride, loaded) }
            }
        }
    }

    private fun build(ride: RideEntity, points: List<TrackPoint>): RideTrackData {
        val source = if (ride.altitudeSource == RideEntity.ALTITUDE_GNSS) AltitudeSource.GNSS else AltitudeSource.TERRAIN
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

    fun rename(name: String) = viewModelScope.launch { container.rides.rename(rideId, name) }

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        container.rides.delete(rideId)
        onDeleted()
    }

    private companion object {
        const val MAX_SEGMENT_POINTS = 3_000
    }
}
