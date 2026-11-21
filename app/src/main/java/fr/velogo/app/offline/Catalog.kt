package fr.velogo.app.offline

import fr.velogo.core.geo.BoundingBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Un fichier de carte téléchargeable.
 * [name] est son nom sur le téléphone (stable) ; [remoteName] son nom dans la Release GitHub, qui change à chaque
 * nouvelle version du contenu.
 */
data class PackFile(
    val type: String,
    val name: String,
    val remoteName: String,
    val size: Long,
    val sha256: String,
) {
    companion object {
        /** Fond de carte (routes, bâtiments, noms…). */
        const val BASE = "fond"
        /** Surcouche vélo : pistes, revêtements, véloroutes, cols, points d'eau. */
        const val BIKE = "velo"
        /** Relief : ombrage de la carte, altitude et pente hors ligne. */
        const val RELIEF = "relief"
        /** Données d'itinéraire BRouter. */
        const val ROUTING = "dalle"
    }
}

/** Une région de France et ses fichiers (fond, vélo, relief, données d'itinéraire). */
data class RegionPack(
    val id: String,
    val name: String,
    val bounds: BoundingBox,
    /** Dalles BRouter nécessaires pour calculer des itinéraires dans cette région. */
    val routingTiles: List<String>,
    val files: List<PackFile>,
) {
    val size: Long get() = files.sumOf { it.size }
}

/** La liste de tout ce qui peut être téléchargé, publiée avec les cartes (catalogue.json). */
data class Catalog(
    /** Date de génération, au format AAAA-MM-JJ. */
    val date: String,
    /** Vue d'ensemble de la France : téléchargée avec la première région. */
    val france: List<PackFile>,
    val routing: List<PackFile>,
    val regions: List<RegionPack>,
) {
    fun region(id: String): RegionPack? = regions.firstOrNull { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Nom du fichier de données d'itinéraire d'un carré d'un degré (« E8_N42 » -> « dalle-E8_N42.rd5 »). */
        fun routingFileName(square: String): String = "dalle-$square.rd5"

        /** Lit le catalogue ; lève une exception si le texte n'en est pas un. */
        fun parse(text: String): Catalog {
            val dto = json.decodeFromString(CatalogDto.serializer(), text)
            val routing = dto.dalles.mapNotNull { it.toPackFile() }
            val routingByName = routing.associateBy { it.name }
            return Catalog(
                date = dto.date,
                france = dto.france.mapNotNull { it.toPackFile() },
                routing = routing,
                regions = dto.regions.mapNotNull { region ->
                    val maps = region.fichiers.mapNotNull { it.toPackFile() }
                    if (maps.isEmpty() || region.emprise.size != 4) return@mapNotNull null
                    RegionPack(
                        id = region.id,
                        name = region.nom,
                        // Dans le catalogue : ouest, sud, est, nord.
                        bounds = BoundingBox(region.emprise[1], region.emprise[0], region.emprise[3], region.emprise[2]),
                        routingTiles = region.dalles,
                        // Les données d'itinéraire de la région (un fichier par carré d'un degré qu'elle touche ;
                        // un carré vide, en pleine mer, n'a pas de fichier) se téléchargent avec ses cartes.
                        files = maps + region.dalles.mapNotNull { routingByName[routingFileName(it)] },
                    )
                },
            )
        }
    }
}

@Serializable
private data class CatalogDto(
    val date: String = "",
    val france: List<FileDto> = emptyList(),
    val dalles: List<FileDto> = emptyList(),
    val regions: List<RegionDto> = emptyList(),
)

@Serializable
private data class RegionDto(
    val id: String,
    val nom: String,
    val emprise: List<Double> = emptyList(),
    val dalles: List<String> = emptyList(),
    val fichiers: List<FileDto> = emptyList(),
)

@Serializable
private data class FileDto(
    val type: String = "",
    val nom: String = "",
    val fichier: String = "",
    val taille: Long = 0,
    val sha256: String = "",
) {
    /** Les noms deviennent des noms de fichiers sur le téléphone : on refuse tout ce qui sortirait du dossier des cartes. */
    fun toPackFile(): PackFile? {
        val safe = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        if (!safe.matches(nom) || !safe.matches(fichier) || taille <= 0 || sha256.length != 64) return null
        return PackFile(type, nom, fichier, taille, sha256.lowercase())
    }
}
