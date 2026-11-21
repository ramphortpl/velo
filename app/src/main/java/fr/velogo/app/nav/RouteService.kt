package fr.velogo.app.nav

import android.app.ActivityManager
import android.content.Context
import fr.velogo.app.elevation.ReliefSource
import fr.velogo.app.offline.OfflineStore
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.RoutePreference
import fr.velogo.core.ride.AscentAccumulator
import fr.velogo.routing.BRouterPlanner
import fr.velogo.routing.PlanFailure
import fr.velogo.routing.PlanOutcome
import fr.velogo.routing.PlanRequest
import fr.velogo.routing.RoutingProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Un itinéraire proposé, avec ce pour quoi il est le meilleur. */
data class RouteOption(
    val route: Route,
    /** Préférences que cet itinéraire satisfait le mieux (plusieurs si les calculs ont abouti au même tracé). */
    val preferences: List<RoutePreference>,
    /** Vrai pour une variante proposée en plus (aucune préférence ne la désigne comme la meilleure). */
    val isVariant: Boolean = false,
)

sealed interface RouteOptions {
    class Found(val options: List<RouteOption>) : RouteOptions
    class Failed(val reason: PlanFailure, val message: String) : RouteOptions
}

/**
 * Calcule les itinéraires proposés à l'utilisateur, entièrement sur le téléphone (moteur BRouter embarqué et
 * données d'itinéraire des régions téléchargées).
 */
class RouteService(
    private val context: Context,
    private val store: OfflineStore,
    private val relief: ReliefSource,
) {
    private val planner: BRouterPlanner by lazy {
        val profiles = File(context.filesDir, "brouter")
        RoutingProfiles.install(profiles)
        val memoryClass = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 64
        BRouterPlanner(profiles, memoryClass)
    }

    /** Vrai si des données d'itinéraire sont installées (sinon, inutile de proposer un calcul). */
    val hasData: Boolean get() = store.hasRoutingData

    /**
     * Les trois itinéraires de base (le plus rapide, le plus sûr, le moins de dénivelé). Quand deux d'entre eux
     * suivent le même tracé, ils sont réunis en une seule proposition, et l'on complète avec des variantes pour
     * offrir un vrai choix quand le réseau le permet.
     */
    suspend fun options(waypoints: List<LatLon>, bike: BikeType, powerW: Int): RouteOptions = withContext(Dispatchers.Default) {
        val segments = store.routingDirectory()
            ?: return@withContext RouteOptions.Failed(PlanFailure.MISSING_DATA, message(PlanFailure.MISSING_DATA))
        val found = ArrayList<RouteOption>()
        var failure: PlanOutcome.Failed? = null
        for (preference in RoutePreference.entries) {
            coroutineContext.ensureActive()
            when (val outcome = planner.plan(segments, PlanRequest(waypoints, bike, preference, powerW = powerW))) {
                is PlanOutcome.Failed -> {
                    failure = outcome
                    // Données manquantes ou point hors réseau : les autres préférences échoueraient de la même façon.
                    if (outcome.reason != PlanFailure.TIMEOUT && outcome.reason != PlanFailure.ERROR) break
                }
                is PlanOutcome.Found -> {
                    val route = refine(outcome.route)
                    val same = found.indexOfFirst { it.route.isSameAs(route) }
                    if (same >= 0) found[same] = found[same].copy(preferences = found[same].preferences + preference)
                    else found.add(RouteOption(route, listOf(preference)))
                }
            }
        }
        if (found.isEmpty()) {
            val reason = failure?.reason ?: PlanFailure.ERROR
            return@withContext RouteOptions.Failed(reason, message(reason))
        }
        var alternative = 1
        while (found.size < WANTED_OPTIONS && alternative <= MAX_ALTERNATIVES) {
            coroutineContext.ensureActive()
            val outcome = planner.plan(segments, PlanRequest(waypoints, bike, RoutePreference.FAST, alternative = alternative, powerW = powerW))
            if (outcome is PlanOutcome.Found) {
                val route = refine(outcome.route)
                // Une variante n'a d'intérêt que si elle diffère vraiment, sans rallonger déraisonnablement.
                val shortest = found.minOf { it.route.distanceM }
                if (found.none { it.route.isSameAs(route) } && route.distanceM <= shortest * MAX_DETOUR) {
                    found.add(RouteOption(route, emptyList(), isVariant = true))
                }
            }
            alternative++
        }
        RouteOptions.Found(found)
    }

    /** Un seul itinéraire (recalcul en cours de guidage). */
    suspend fun single(waypoints: List<LatLon>, bike: BikeType, preference: RoutePreference, powerW: Int): Route? =
        withContext(Dispatchers.Default) {
            val segments = store.routingDirectory() ?: return@withContext null
            val outcome = planner.plan(segments, PlanRequest(waypoints, bike, preference, powerW = powerW, timeoutS = REROUTE_TIMEOUT_S))
            (outcome as? PlanOutcome.Found)?.route?.let { refine(it) }
        }

    /**
     * Remplace le profil d'altitude de BRouter (maille de 30 m) par celui du relief hors ligne quand il couvre
     * tout l'itinéraire : le dénivelé annoncé est alors celui qu'on mesurera en roulant.
     */
    private fun refine(route: Route): Route {
        if (!relief.isAvailable) return route
        val elevations = DoubleArray(route.points.size)
        for ((i, p) in route.points.withIndex()) {
            elevations[i] = relief.altitude(p.lat, p.lon) ?: return route
        }
        val accumulator = AscentAccumulator(RELIEF_ASCENT_THRESHOLD_M)
        for (e in elevations) accumulator.add(e)
        return route.withElevations(elevations, accumulator.ascentM, accumulator.descentM)
    }

    companion object {
        private const val WANTED_OPTIONS = 3
        private const val MAX_ALTERNATIVES = 2
        private const val MAX_DETOUR = 1.6
        private const val REROUTE_TIMEOUT_S = 25
        private const val RELIEF_ASCENT_THRESHOLD_M = 3.0

        /** Explication à montrer quand le calcul échoue. */
        fun message(reason: PlanFailure): String = when (reason) {
            PlanFailure.MISSING_DATA -> "Les données d'itinéraire de cette zone ne sont pas sur le téléphone. Télécharge (ou mets à jour) la région dans « Cartes hors ligne »."
            PlanFailure.POINT_OFF_NETWORK -> "Un des points est trop loin d'une route ou d'un chemin : déplace-le."
            PlanFailure.NO_ROUTE -> "Aucun itinéraire à vélo ne relie ces points."
            PlanFailure.TIMEOUT -> "Calcul trop long : rapproche les points ou ajoute une étape."
            PlanFailure.ERROR -> "Le calcul de l'itinéraire a échoué."
        }
    }
}
