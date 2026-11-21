package fr.velogo.routing

import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.RoutePreference
import java.io.File

/**
 * Les profils de BRouter et la façon dont VeloGo s'en sert.
 *
 * Un profil est un fichier texte qui donne un « coût » à chaque voie selon ses attributs OpenStreetMap ; BRouter
 * cherche le chemin le moins coûteux. On part des trois profils vélo livrés avec BRouter, et on règle leurs
 * paramètres selon ce que l'utilisateur privilégie.
 */
object RoutingProfiles {
    private val FILES = listOf("lookups.dat", "fastbike.brf", "trekking.brf", "mtb.brf")

    /** Change quand les fichiers embarqués changent : les copies sur le téléphone sont alors refaites. */
    private const val VERSION = "brouter-1.7.10-a"

    /** Paramètres qui pénalisent franchement les montées (et les descentes raides, qu'il faudra bien remonter). */
    private val FLAT = mapOf("uphillcost" to "110", "uphillcutoff" to "0.5", "downhillcost" to "80", "downhillcutoff" to "0.5")

    /**
     * Profil BRouter et réglages pour un type de vélo et une préférence.
     * Les valeurs sont toujours des nombres : BRouter attend 1 et 0 pour « vrai » et « faux ».
     */
    fun choose(bike: BikeType, preference: RoutePreference): Pair<String, Map<String, String>> = when (bike) {
        // Vélo de route : profil « fastbike », qui ne quitte pas le bitume.
        BikeType.ROAD -> "fastbike" to when (preference) {
            RoutePreference.FAST -> mapOf("consider_traffic" to "0.1")
            RoutePreference.SAFE -> mapOf("consider_traffic" to "1")
            RoutePreference.FLAT -> FLAT + ("consider_traffic" to "0.3")
        }
        // VTC et gravel : profil « trekking », qui aime les pistes, les voies vertes et les petites routes.
        BikeType.HYBRID -> "trekking" to when (preference) {
            RoutePreference.FAST -> mapOf("ignore_cycleroutes" to "1")
            RoutePreference.SAFE -> mapOf("avoid_unsafe" to "1", "consider_traffic" to "1")
            RoutePreference.FLAT -> FLAT + ("ignore_cycleroutes" to "1")
        }
        // VTT : profil « mtb », qui cherche chemins et sentiers.
        BikeType.MTB -> "mtb" to when (preference) {
            RoutePreference.FAST -> emptyMap()
            RoutePreference.SAFE -> mapOf("avoid_unsafe" to "1")
            RoutePreference.FLAT -> mapOf("hills" to "2")
        }
    }

    /** Copie les profils embarqués dans [target] (BRouter les lit comme des fichiers). Sans effet s'ils y sont déjà. */
    fun install(target: File) {
        val marker = File(target, "version.txt")
        if (marker.isFile && marker.readText() == VERSION && FILES.all { File(target, it).isFile }) return
        target.mkdirs()
        for (name in FILES) {
            val resource = RoutingProfiles::class.java.getResourceAsStream("/brouter/$name")
                ?: error("profil BRouter introuvable dans l'appli : $name")
            resource.use { input -> File(target, name).outputStream().use { input.copyTo(it) } }
        }
        marker.writeText(VERSION)
    }
}
