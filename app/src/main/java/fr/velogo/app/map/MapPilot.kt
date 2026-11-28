package fr.velogo.app.map

import android.location.Location
import fr.velogo.app.recording.LiveTrack
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.LatLon

/** Le moteur qui dessine un fond de carte. */
enum class MapEngine {
    /** MapLibre : le plan de VeloGo, CyclOSM, l'IGN et la carte hors ligne. */
    MAPLIBRE,

    /** Le kit de Mapbox : ses conditions n'admettent ses cartes que dans son propre moteur. */
    MAPBOX,
}

/**
 * Où regarde une carte : sert à passer d'un moteur à l'autre sans changer de vue. [center] est le point montré au
 * milieu de ce que laissent les marges [padding] : une carte cadrée au-dessus du panneau d'itinéraire a une marge
 * en bas, et sans elle la même vue se retrouverait décalée sous le panneau (vu sur l'émulateur, fabrication n° 39).
 */
data class MapCamera(val center: LatLon, val zoom: Double, val bearingDeg: Double = 0.0, val padding: MapPadding = MapPadding.NONE)

/** Marges d'une carte, en pixels : la place de ce qui la recouvre sur chaque bord. */
data class MapPadding(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    companion object {
        val NONE = MapPadding(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Ce que l'écran « Rouler » demande à sa carte, quel que soit le moteur qui la dessine : ce qu'il faut y tracer,
 * où est la position, comment la suivre.
 */
interface MapPilot {
    /** Vrai tant que la carte suit la position ; passe à faux dès qu'on la déplace au doigt. */
    val isFollowing: Boolean

    /** Appelé quand on appuie longuement sur la carte (pour y placer un point d'itinéraire). */
    var onLongPress: ((LatLon) -> Unit)?

    /** Appelé quand on commence à déplacer la carte au doigt. */
    var onUserMove: (() -> Unit)?

    /** Appelé quand on touche un des itinéraires proposés, avec son rang. */
    var onRouteTapped: ((Int) -> Unit)?

    /** Carte masquée par un autre panneau : son rendu s'arrête, sans la détruire. */
    fun setVisible(isVisible: Boolean)

    /** Trace de la sortie en cours. */
    fun setLiveTrack(track: LiveTrack)

    /** Itinéraires proposés : celui de rang [selected] est mis en avant, les autres restent en retrait. */
    fun setRoutes(options: List<List<LatLon>>, selected: Int)

    /** Guidage : l'itinéraire affiché est parcouru jusqu'à [position], sur son segment de rang [segment]. */
    fun setRouteProgress(segment: Int, position: LatLon)

    fun clearRouteProgress()

    /** Guidage : le tracé de la prochaine manœuvre, dessiné en flèche ; une liste vide l'efface. */
    fun setManeuverArrow(points: List<LatLon>)

    /** Départ, étapes et arrivée d'un itinéraire en préparation. */
    fun setPlanMarkers(markers: List<PlanMarker>)

    /** Centre de la carte affichée, ou null si elle n'est pas prête. */
    fun center(): LatLon?

    /** Nouvelle position : déplace le repère et, si le suivi est actif, la carte. */
    fun updateLocation(newLocation: Location)

    /** Recentre la carte sur la position et reprend le suivi. */
    fun follow()

    /** Carte orientée dans le sens de la marche (vrai) ou nord en haut (faux), hors guidage. */
    fun setHeadingUp(enabled: Boolean)

    /** Vue de guidage (dans le sens de la marche, inclinée, rapprochée) ou vue ordinaire. */
    fun setNavigation(enabled: Boolean)

    /** Ce qui recouvre la carte en haut et en bas pendant le guidage, en pixels. */
    fun setNavigationInsets(topPx: Int, bottomPx: Int)

    /** Cadre la carte sur une emprise, vue de dessus et nord en haut, dans la place que laissent les marges. */
    fun fit(box: BoundingBox, paddingPx: Int, topPx: Int = paddingPx, bottomPx: Int = paddingPx, endPx: Int = paddingPx, animate: Boolean = false)

    /** Hauteur de ce qui recouvre le haut de la carte (barre du haut), en pixels : la boussole se place dessous. */
    fun setTopInset(px: Int)

    /**
     * Où poser, en bas à gauche de la carte, les mentions que le moteur affiche lui-même (logo et bouton
     * d'information de Mapbox) : à [startPx] du bord et à [bottomPx] du bas, là où l'écran pose la mention des
     * autres fonds. Sans effet pour un moteur qui n'affiche rien de lui-même.
     */
    fun setOrnamentInsets(startPx: Int, bottomPx: Int)
}
