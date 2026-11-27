package fr.velogo.app.map

import org.json.JSONArray
import org.json.JSONObject

/** Un fond de carte proposé à l'utilisateur. */
data class MapLayer(
    val id: String,
    val title: String,
    val description: String,
    /** Mention à afficher en permanence dans un coin de la carte. */
    val attribution: String,
    /** Page qu'ouvre un appui sur la mention : les droits et la licence de ceux qui fournissent la carte. */
    val attributionUrl: String,
    /** Style vectoriel prêt à l'emploi (thème clair / thème sombre), ou null pour un fond en images. */
    val lightStyleUri: String? = null,
    val darkStyleUri: String? = null,
    /** Adresses des tuiles image, pour un fond « raster ». */
    val rasterTiles: List<String> = emptyList(),
    val rasterMaxZoom: Int = 19,
    /** Vrai si le fond utilise le schéma OpenMapTiles : on peut y surligner les pistes cyclables. */
    val highlightCycleways: Boolean = false,
    /** Vrai pour la carte servie depuis les packs du téléphone : elle n'a besoin d'aucun réseau. */
    val offline: Boolean = false,
)

/**
 * Ce que la carte doit charger : un style distant, ou un style JSON fabriqué ici.
 * [revision] change quand les cartes hors ligne installées changent, pour forcer un rechargement.
 */
data class MapStyleSpec(val layer: MapLayer, val dark: Boolean, val revision: Int = 0) {
    val uri: String? get() = if (dark) layer.darkStyleUri ?: layer.lightStyleUri else layer.lightStyleUri
    val json: String? get() = if (uri == null) MapLayers.rasterStyle(layer, dark) else null
}

/**
 * Fonds de carte. Ceux en ligne sont tous gratuits et sans clé ; aucun n'est téléchargé en masse :
 * seules les tuiles affichées sont demandées, puis gardées en cache. Le dernier fonctionne sans réseau.
 */
object MapLayers {
    /**
     * Les mentions sont des liens, comme le demandent ceux qui fournissent les cartes : OpenStreetMap France veut
     * une mention « avec un lien pointant sur https://osm.org/copyright », la Licence Ouverte de l'IGN se satisfait
     * d'une adresse qui mène à la donnée et à sa date.
     */
    const val OSM_COPYRIGHT = "https://www.openstreetmap.org/copyright"
    const val IGN_SOURCE = "https://cartes.gouv.fr"

    private const val IGN_WMTS = "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
        "&STYLE=normal&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}"

    val OPENFREEMAP = MapLayer(
        id = "openfreemap",
        title = "Plan",
        description = "Carte vectorielle nette et rapide, claire ou sombre, pistes cyclables surlignées.",
        // « © OpenMapTiles » est la partie qu'OpenFreeMap rend obligatoire ; son propre nom ne l'est pas.
        attribution = "© OpenStreetMap · © OpenMapTiles · OpenFreeMap",
        attributionUrl = OSM_COPYRIGHT,
        lightStyleUri = "https://tiles.openfreemap.org/styles/liberty",
        darkStyleUri = "https://tiles.openfreemap.org/styles/dark",
        highlightCycleways = true,
    )

    val CYCLOSM = MapLayer(
        id = "cyclosm",
        title = "CyclOSM",
        description = "La carte des cyclistes : pistes, bandes, véloroutes, relief, points d'eau.",
        attribution = "© OpenStreetMap · CyclOSM · OSM France",
        attributionUrl = OSM_COPYRIGHT,
        rasterTiles = listOf("a", "b", "c").map { "https://$it.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png" },
        rasterMaxZoom = 20,
    )

    val PLAN_IGN = MapLayer(
        id = "plan_ign",
        title = "Plan IGN",
        description = "La carte de l'IGN : chemins, courbes de niveau, toponymes.",
        attribution = "© IGN – Géoplateforme",
        attributionUrl = IGN_SOURCE,
        rasterTiles = listOf("$IGN_WMTS&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&FORMAT=image/png"),
        rasterMaxZoom = 19,
    )

    val ORTHO_IGN = MapLayer(
        id = "ortho_ign",
        title = "Photos aériennes",
        description = "Vues aériennes de l'IGN : pour vérifier un chemin ou un revêtement.",
        attribution = "© IGN – Géoplateforme",
        attributionUrl = IGN_SOURCE,
        rasterTiles = listOf("$IGN_WMTS&LAYER=ORTHOIMAGERY.ORTHOPHOTOS&FORMAT=image/jpeg"),
        rasterMaxZoom = 19,
    )

    /**
     * Carte vélo de VeloGo, dessinée sur le téléphone à partir des régions téléchargées. Son style est embarqué
     * dans l'appli (assets/hors-ligne) ; tuiles, polices et icônes sont servies sur place par OfflineRouter.
     */
    val OFFLINE = MapLayer(
        id = "hors_ligne",
        title = "Vélo hors ligne",
        description = "Carte vélo de VeloGo : pistes, revêtements, véloroutes, cols et relief, sans réseau.",
        attribution = "© OpenStreetMap · Protomaps · Mapterhorn (IGN)",
        attributionUrl = OSM_COPYRIGHT,
        lightStyleUri = "asset://hors-ligne/style-clair.json",
        darkStyleUri = "asset://hors-ligne/style-sombre.json",
        offline = true,
    )

    val ALL = listOf(OPENFREEMAP, CYCLOSM, PLAN_IGN, ORTHO_IGN, OFFLINE)

    fun byId(id: String?): MapLayer = ALL.firstOrNull { it.id == id } ?: OPENFREEMAP

    /**
     * Fond réellement affiché. Si la carte hors ligne est choisie alors qu'aucune région n'est installée
     * (tout a été supprimé), on revient au plan en ligne plutôt que de montrer une carte vide.
     * [offlineRevision] vaut 0 tant que les cartes du téléphone n'ont pas encore été relues au démarrage.
     */
    fun effective(id: String?, offlineRevision: Int, offlineInstalled: Boolean): MapLayer {
        val chosen = byId(id)
        return if (chosen.offline && offlineRevision > 0 && !offlineInstalled) OPENFREEMAP else chosen
    }

    /** Style MapLibre minimal pour un fond en images. En thème sombre, l'image est assombrie pour ne pas éblouir. */
    fun rasterStyle(layer: MapLayer, dark: Boolean): String {
        val source = JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray(layer.rasterTiles))
            .put("tileSize", 256)
            .put("maxzoom", layer.rasterMaxZoom)
            .put("attribution", layer.attribution)
        val paint = JSONObject()
        if (dark) paint.put("raster-brightness-max", 0.72).put("raster-saturation", -0.15)
        val layers = JSONArray()
            .put(JSONObject().put("id", "fond").put("type", "background")
                .put("paint", JSONObject().put("background-color", if (dark) "#10181f" else "#e9eeea")))
            .put(JSONObject().put("id", "tuiles").put("type", "raster").put("source", "tuiles").put("paint", paint))
        return JSONObject()
            .put("version", 8)
            .put("name", layer.title)
            .put("glyphs", "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf")
            .put("sources", JSONObject().put("tuiles", source))
            .put("layers", layers)
            .toString()
    }
}
