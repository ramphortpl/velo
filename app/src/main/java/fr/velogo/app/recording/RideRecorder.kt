package fr.velogo.app.recording

import android.os.SystemClock
import fr.velogo.app.data.RideRepository
import fr.velogo.app.data.totals
import fr.velogo.core.geo.LatLon
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.Fix
import fr.velogo.core.ride.RideConfig
import fr.velogo.core.ride.RideEngine
import fr.velogo.core.ride.RideSnapshot
import fr.velogo.core.ride.RideState
import fr.velogo.core.ride.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Un morceau de trace déjà figé (il ne changera plus) : la carte ne le redessine pas à chaque seconde. */
class TrackChunk(val points: List<LatLon>, val segment: Int)

/** La trace en cours, découpée pour que la carte reste fluide même après des heures d'enregistrement. */
class LiveTrack(val chunks: List<TrackChunk>, val tail: List<LatLon>, val tailSegment: Int) {
    val isEmpty: Boolean get() = chunks.isEmpty() && tail.isEmpty()

    companion object {
        val EMPTY = LiveTrack(emptyList(), emptyList(), 0)
    }
}

sealed interface RecorderEvent {
    /** La sortie est enregistrée dans l'historique. */
    data class Saved(val rideId: Long) : RecorderEvent
    /** La sortie était trop courte pour être gardée. */
    data object TooShort : RecorderEvent
}

/**
 * Chef d'orchestre d'un enregistrement : fait tourner le moteur de calcul, publie l'état pour l'écran,
 * et sauvegarde la trace au fil de l'eau (si l'appli est tuée, on ne perd que quelques secondes).
 *
 * Tous les accès au moteur passent par un seul fil d'exécution ([serial]) : pas de course possible entre
 * les positions GPS, le chrono et les boutons.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideRecorder(private val repository: RideRepository) {

    private val serial = Dispatchers.Default.limitedParallelism(1)

    private var engine = RideEngine()
    private var rideId: Long? = null
    private var nextSeq = 0
    private val pending = ArrayList<TrackPoint>()
    private var lastFlushMonoMs = 0L

    private var chunks: List<TrackChunk> = emptyList()
    private var tail = ArrayList<LatLon>()
    private var tailSegment = 0

    private val _snapshot = MutableStateFlow(RideSnapshot())
    val snapshot: StateFlow<RideSnapshot> = _snapshot

    private val _track = MutableStateFlow(LiveTrack.EMPTY)
    val track: StateFlow<LiveTrack> = _track

    private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RecorderEvent> = _events

    val isActive: Boolean get() = _snapshot.value.isActive

    /**
     * Démarre une sortie, ou reprend celle qui était en cours si l'appli avait été arrêtée.
     * Avec [allowNew] à faux, on se contente de reprendre : s'il n'y a rien à reprendre, rien ne démarre.
     * Renvoie vrai si un enregistrement est actif à la sortie de la fonction.
     */
    suspend fun startOrRestore(autoPause: Boolean, allowNew: Boolean): Boolean = withContext(serial) {
        if (engine.state != RideState.IDLE) return@withContext true
        val nowMono = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val config = RideConfig(autoPause = autoPause, altitudeSource = AltitudeSource.GNSS)
        val unfinished = repository.unfinishedRide()
        if (unfinished == null && !allowNew) return@withContext false
        engine = RideEngine(config)
        pending.clear()
        if (unfinished != null) {
            val saved = repository.points(unfinished.id)
            rideId = unfinished.id
            nextSeq = saved.size
            val elapsed = unfinished.elapsedMs + (nowWall - unfinished.updatedAtMs).coerceAtLeast(0)
            engine.restore(unfinished.startTimeMs, unfinished.totals().copy(elapsedMs = elapsed), unfinished.lastSegment + 1, nowMono)
            rebuildTrack(saved)
        } else {
            rideId = repository.createRide(nowWall, defaultRideName(nowWall))
            nextSeq = 0
            engine.start(nowMono, nowWall)
            chunks = emptyList(); tail = ArrayList(); tailSegment = 0
            _track.value = LiveTrack.EMPTY
        }
        lastFlushMonoMs = nowMono
        publish()
        true
    }

    suspend fun onFix(fix: Fix) = withContext(serial) {
        if (engine.state == RideState.IDLE) return@withContext
        val point = engine.onFix(fix)
        if (point != null) {
            pending.add(point)
            appendToTrack(point)
        }
        if (pending.size >= FLUSH_POINTS || fix.monoMs - lastFlushMonoMs >= FLUSH_INTERVAL_MS) flush()
        publish()
    }

    /** Fait avancer le chrono (appelé chaque seconde par le service). */
    suspend fun tick() = withContext(serial) {
        if (engine.state == RideState.IDLE) return@withContext
        val now = SystemClock.elapsedRealtime()
        engine.onTick(now)
        if (now - lastFlushMonoMs >= FLUSH_INTERVAL_MS) flush()
        publish()
    }

    suspend fun pause() = withContext(serial) {
        engine.pause(SystemClock.elapsedRealtime())
        flush()
        publish()
    }

    suspend fun resume() = withContext(serial) {
        engine.resume(SystemClock.elapsedRealtime())
        publish()
    }

    suspend fun setAutoPause(enabled: Boolean) = withContext(serial) {
        engine.setAutoPause(enabled)
        publish()
    }

    /** Termine la sortie. Renvoie son identifiant si elle a été gardée, null si elle était trop courte. */
    suspend fun stop(): Long? = withContext(serial) {
        val id = rideId
        if (engine.state == RideState.IDLE || id == null) return@withContext null
        val totals = engine.stop(SystemClock.elapsedRealtime())
        flush()
        val kept = nextSeq >= MIN_POINTS && totals.distanceM >= MIN_DISTANCE_M
        if (kept) repository.finishRide(id, totals, System.currentTimeMillis()) else repository.delete(id)
        rideId = null
        nextSeq = 0
        chunks = emptyList(); tail = ArrayList(); tailSegment = 0
        _track.value = LiveTrack.EMPTY
        _snapshot.value = RideSnapshot()
        _events.tryEmit(if (kept) RecorderEvent.Saved(id) else RecorderEvent.TooShort)
        if (kept) id else null
    }

    private suspend fun flush() {
        val id = rideId ?: return
        val batch = ArrayList(pending)
        pending.clear()
        repository.appendPoints(id, nextSeq, batch, engine.totals(), engine.currentSegment, System.currentTimeMillis())
        nextSeq += batch.size
        lastFlushMonoMs = SystemClock.elapsedRealtime()
    }

    private fun publish() {
        _snapshot.value = engine.snapshot()
    }

    private fun appendToTrack(point: TrackPoint) {
        val position = LatLon(point.lat, point.lon)
        if (tail.isNotEmpty() && point.segment != tailSegment) {
            // Reprise après une pause : on fige le tronçon précédent, sans trait de liaison.
            if (tail.size >= 2) chunks = chunks + TrackChunk(tail, tailSegment)
            tail = ArrayList()
        } else if (tail.size >= CHUNK_SIZE) {
            chunks = chunks + TrackChunk(tail, tailSegment)
            tail = arrayListOf(tail.last())
        }
        tailSegment = point.segment
        tail.add(position)
        _track.value = LiveTrack(chunks, ArrayList(tail), tailSegment)
    }

    private fun rebuildTrack(points: List<TrackPoint>) {
        chunks = emptyList(); tail = ArrayList(); tailSegment = 0
        val built = ArrayList<TrackChunk>()
        var current = ArrayList<LatLon>()
        var segment = points.firstOrNull()?.segment ?: 0
        for (p in points) {
            if (p.segment != segment || current.size >= CHUNK_SIZE) {
                if (current.size >= 2) built.add(TrackChunk(current, segment))
                current = if (p.segment == segment && current.isNotEmpty()) arrayListOf(current.last()) else ArrayList()
                segment = p.segment
            }
            current.add(LatLon(p.lat, p.lon))
        }
        if (current.size >= 2) built.add(TrackChunk(current, segment))
        chunks = built
        // Le tronçon suivant commencera après la reprise : la queue repart vide.
        _track.value = LiveTrack(chunks, emptyList(), tailSegment)
    }

    companion object {
        private const val FLUSH_POINTS = 10
        private const val FLUSH_INTERVAL_MS = 8_000L
        private const val CHUNK_SIZE = 300
        private const val MIN_POINTS = 5
        private const val MIN_DISTANCE_M = 50.0

        /** Nom par défaut d'une sortie, selon l'heure de départ. */
        fun defaultRideName(startTimeMs: Long): String {
            val hour = java.time.Instant.ofEpochMilli(startTimeMs).atZone(java.time.ZoneId.systemDefault()).hour
            return when (hour) {
                in 5..10 -> "Sortie du matin"
                in 11..13 -> "Sortie de midi"
                in 14..17 -> "Sortie de l'après-midi"
                in 18..21 -> "Sortie du soir"
                else -> "Sortie de nuit"
            }
        }
    }
}
