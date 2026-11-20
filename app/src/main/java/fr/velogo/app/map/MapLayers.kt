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
    /** Style vectoriel prêt à l'emploi (thème clair / thème sombre), ou null pour un fond en images. */
    val lightStyleUri: String? = null,
    val darkStyleUri: String? = null,
    /** Adresses des tuiles image, pour un fond « raster ». */
    val rasterTiles: List<String> = emptyList(),
    val rasterMaxZoom: Int = 19,
    /** Vrai si le fond utilise le schéma OpenMapTiles : on peut y surligner les pistes cyclables. */
    val highlightCycleways: Boolean = false,
)

/** Ce que la carte doit charger : un style distant, ou un style JSON fabriqué ici. */
data class MapStyleSpec(val layer: MapLayer, val dark: Boolean) {
    val uri: String? get() = if (dark) layer.darkStyleUri ?: layer.lightStyleUri else layer.lightStyleUri
    val json: String? get() = if (uri == null) MapLayers.rasterStyle(layer, dark) else null
}

/**
 * Fonds de carte en ligne. Tous sont gratuits et sans clé ; aucun n'est téléchargé en masse :
 * seules les tuiles affichées sont demandées, puis gardées en cache.
 */
object MapLayers {
    private const val IGN_WMTS = "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
        "&STYLE=normal&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}"

    val OPENFREEMAP = MapLayer(
        id = "openfreemap",
        title = "Plan",
        description = "Carte vectorielle nette et rapide, claire ou sombre, pistes cyclables surlignées.",
        attribution = "© OpenStreetMap · OpenFreeMap",
        lightStyleUri = "https://tiles.openfreemap.org/styles/liberty",
        darkStyleUri = "https://tiles.openfreemap.org/styles/dark",
        highlightCycleways = true,
    )

    val CYCLOSM = MapLayer(
        id = "cyclosm",
        title = "CyclOSM",
        description = "La carte des cyclistes : pistes, bandes, véloroutes, relief, points d'eau.",
        attribution = "© OpenStreetMap · CyclOSM · OSM France",
        rasterTiles = listOf("a", "b", "c").map { "https://$it.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png" },
        rasterMaxZoom = 20,
    )

    val PLAN_IGN = MapLayer(
        id = "plan_ign",
        title = "Plan IGN",
        description = "La carte de l'IGN : chemins, courbes de niveau, toponymes.",
        attribution = "© IGN – Géoplateforme",
        rasterTiles = listOf("$IGN_WMTS&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&FORMAT=image/png"),
        rasterMaxZoom = 19,
    )

    val ORTHO_IGN = MapLayer(
        id = "ortho_ign",
        title = "Photos aériennes",
        description = "Vues aériennes de l'IGN : pour vérifier un chemin ou un revêtement.",
        attribution = "© IGN – Géoplateforme",
        rasterTiles = listOf("$IGN_WMTS&LAYER=ORTHOIMAGERY.ORTHOPHOTOS&FORMAT=image/jpeg"),
        rasterMaxZoom = 19,
    )

    val ALL = listOf(OPENFREEMAP, CYCLOSM, PLAN_IGN, ORTHO_IGN)

    fun byId(id: String?): MapLayer = ALL.firstOrNull { it.id == id } ?: OPENFREEMAP

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
