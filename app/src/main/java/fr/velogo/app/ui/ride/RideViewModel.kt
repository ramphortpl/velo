package fr.velogo.app.ui.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.data.Settings
import fr.velogo.app.data.db.RideEntity
import fr.velogo.core.ride.RideAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RideViewModel(private val container: AppContainer) : ViewModel() {
    val snapshot = container.recorder.snapshot
    val track = container.recorder.track
    val events = container.recorder.events

    val settings: StateFlow<Settings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Sortie restée « en cours » parce que l'appli a été arrêtée pendant un enregistrement. */
    private val _interrupted = MutableStateFlow<RideEntity?>(null)
    val interrupted: StateFlow<RideEntity?> = _interrupted

    init {
        viewModelScope.launch {
            if (!container.recorder.isActive) _interrupted.value = container.rides.unfinishedRide()
        }
    }

    fun onResumeInterrupted() {
        _interrupted.value = null
    }

    /** Clôt la sortie interrompue avec ce qui a été enregistré avant l'arrêt. */
    fun finishInterrupted(onSaved: (Long) -> Unit) {
        val ride = _interrupted.value ?: return
        _interrupted.value = null
        viewModelScope.launch {
            val points = container.rides.points(ride.id)
            if (points.size < 5) {
                container.rides.delete(ride.id)
            } else {
                val totals = RideAnalyzer.summarize(points)
                container.rides.finishRide(ride.id, totals, points.last().timeMs)
                onSaved(ride.id)
            }
        }
    }

    fun setMapLayer(id: String) = viewModelScope.launch { container.settings.setMapLayer(id) }
    fun setHeadingUp(value: Boolean) = viewModelScope.launch { container.settings.setHeadingUp(value) }
    fun setBatteryHintShown() = viewModelScope.launch { container.settings.setBatteryHintShown(true) }
}
