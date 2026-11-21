package fr.velogo.app.data

import androidx.room3.withWriteTransaction
import fr.velogo.app.data.db.BestEffortEntity
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.data.db.SegmentEffortEntity
import fr.velogo.app.data.db.SegmentEntity
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.core.geo.Polyline
import fr.velogo.core.segments.SegmentMatcher
import fr.velogo.core.stats.BestEfforts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ce que l'on calcule une fois pour toutes sur chaque sortie terminée, et que l'on garde en base : ses meilleurs
 * temps sur les distances de référence et ses passages sur les segments personnels.
 *
 * Tout se fait en tâche de fond, une sortie à la fois. Chaque sortie retient la version de l'analyse qu'elle a
 * reçue : si le calcul change un jour ([VERSION] augmente), les anciennes sorties sont reprises au lancement.
 */
class RideAnalysis(private val db: VeloGoDatabase, private val rides: RideRepository) {
    private val mutex = Mutex()

    private val _matching = MutableStateFlow<Set<Long>>(emptySet())
    /** Segments en cours de recherche dans les sorties déjà enregistrées. */
    val matching: StateFlow<Set<Long>> = _matching

    /** Analyse une sortie terminée (sans effet sur une sortie en cours ou disparue). */
    suspend fun analyze(rideId: Long) = mutex.withLock { analyzeLocked(rideId, db.segments().all()) }

    /**
     * Analyse les sorties qui ne l'ont pas encore été (anciennes sorties, sorties importées), une par une : entre
     * deux sorties, une autre demande (un segment qu'on vient de créer, par exemple) peut passer.
     */
    suspend fun analyzePending() {
        for (ride in db.rides().needingAnalysis(VERSION)) {
            mutex.withLock { analyzeLocked(ride.id, db.segments().all()) }
        }
    }

    /** Cherche un segment (nouveau) dans toutes les sorties déjà enregistrées. */
    suspend fun matchSegment(segmentId: Long) {
        _matching.update { it + segmentId }
        try {
            mutex.withLock { matchLocked(segmentId) }
        } finally {
            _matching.update { it - segmentId }
        }
    }

    private suspend fun matchLocked(segmentId: Long) {
        val segment = db.segments().get(segmentId) ?: return
        val shape = Polyline.decode(segment.points)
        val found = ArrayList<SegmentEffortEntity>()
        for (ride in rides.finishedRides()) {
            if (!ride.covers(segment)) continue
            val points = rides.points(ride.id)
            val passes = withContext(Dispatchers.Default) { SegmentMatcher.passes(points, shape) }
            passes.mapTo(found) { SegmentEffortEntity(segmentId = segmentId, rideId = ride.id, startTimeMs = it.startTimeMs, elapsedMs = it.elapsedMs) }
        }
        db.withWriteTransaction {
            db.segments().deleteEffortsOfSegment(segmentId)
            if (found.isNotEmpty()) db.segments().insertEfforts(found)
        }
    }

    private suspend fun analyzeLocked(rideId: Long, segments: List<SegmentEntity>) {
        val ride = rides.ride(rideId) ?: return
        if (ride.status != RideEntity.STATUS_FINISHED) return
        val points = rides.points(rideId)
        val (bests, efforts) = withContext(Dispatchers.Default) {
            val bests = BestEfforts.compute(points).map { BestEffortEntity(rideId, it.distanceM, it.durationMs, it.startTimeMs) }
            val efforts = ArrayList<SegmentEffortEntity>()
            for (segment in segments) {
                if (!ride.covers(segment)) continue
                SegmentMatcher.passes(points, Polyline.decode(segment.points)).mapTo(efforts) {
                    SegmentEffortEntity(segmentId = segment.id, rideId = rideId, startTimeMs = it.startTimeMs, elapsedMs = it.elapsedMs)
                }
            }
            bests to efforts
        }
        db.withWriteTransaction {
            db.bestEfforts().deleteForRide(rideId)
            if (bests.isNotEmpty()) db.bestEfforts().insertAll(bests)
            db.segments().deleteEffortsOfRide(rideId)
            if (efforts.isNotEmpty()) db.segments().insertEfforts(efforts)
            db.rides().setAnalysisVersion(rideId, VERSION)
        }
    }

    /**
     * Vrai si la sortie a pu passer par tout le segment : son emprise doit contenir celle du segment (à la
     * tolérance près). Cela évite de relire les milliers de points d'une sortie faite ailleurs.
     */
    private fun RideEntity.covers(segment: SegmentEntity): Boolean {
        val south = minLat ?: return false
        val west = minLon ?: return false
        val north = maxLat ?: return false
        val east = maxLon ?: return false
        return segment.minLat >= south - MARGIN_DEG && segment.maxLat <= north + MARGIN_DEG &&
            segment.minLon >= west - MARGIN_DEG && segment.maxLon <= east + MARGIN_DEG
    }

    companion object {
        /** À augmenter quand le calcul change : les sorties déjà analysées seront reprises. */
        const val VERSION = 1
        /** Environ 100 m en latitude, 70 m en longitude : large devant la tolérance du rapprochement. */
        private const val MARGIN_DEG = 0.001
    }
}
