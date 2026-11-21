package fr.velogo.core.nav

import kotlin.math.max
import kotlin.math.roundToInt

/** Les mots du guidage, en français. Tout est ici pour pouvoir le relire et le tester d'un seul coup d'œil. */
object GuidancePhrases {

    /** Libellé court pour le bandeau : « À gauche », « Rond-point, 2e sortie »… */
    fun label(instruction: Instruction): String = when (instruction.maneuver) {
        Maneuver.CONTINUE -> "Tout droit"
        Maneuver.SLIGHT_LEFT -> "Légèrement à gauche"
        Maneuver.LEFT -> "À gauche"
        Maneuver.SHARP_LEFT -> "Franchement à gauche"
        Maneuver.SLIGHT_RIGHT -> "Légèrement à droite"
        Maneuver.RIGHT -> "À droite"
        Maneuver.SHARP_RIGHT -> "Franchement à droite"
        Maneuver.KEEP_LEFT -> "Serrer à gauche"
        Maneuver.KEEP_RIGHT -> "Serrer à droite"
        Maneuver.U_TURN -> "Demi-tour"
        Maneuver.ROUNDABOUT -> if (instruction.exit > 0) "Rond-point, ${ordinal(instruction.exit)} sortie" else "Rond-point"
        Maneuver.ARRIVE -> "Arrivée"
    }

    /** La manœuvre telle qu'on la dit : « tourne à gauche », « au rond-point, prends la deuxième sortie »… */
    fun spoken(instruction: Instruction): String = when (instruction.maneuver) {
        Maneuver.CONTINUE -> "continue tout droit"
        Maneuver.SLIGHT_LEFT -> "tourne légèrement à gauche"
        Maneuver.LEFT -> "tourne à gauche"
        Maneuver.SHARP_LEFT -> "tourne franchement à gauche"
        Maneuver.SLIGHT_RIGHT -> "tourne légèrement à droite"
        Maneuver.RIGHT -> "tourne à droite"
        Maneuver.SHARP_RIGHT -> "tourne franchement à droite"
        Maneuver.KEEP_LEFT -> "serre à gauche"
        Maneuver.KEEP_RIGHT -> "serre à droite"
        Maneuver.U_TURN -> "fais demi-tour"
        Maneuver.ROUNDABOUT ->
            if (instruction.exit > 0) "au rond-point, prends la ${spokenOrdinal(instruction.exit)} sortie" else "prends le rond-point"
        Maneuver.ARRIVE -> "tu es à destination"
    }

    /** Annonce faite à l'approche : « Dans 150 mètres, tourne à gauche. » */
    fun prepare(instruction: Instruction, distanceM: Double): String =
        "Dans ${spokenDistance(distanceM)}, ${spoken(instruction)}."

    /** Annonce faite au moment d'agir, avec la consigne suivante si elle est toute proche. */
    fun now(instruction: Instruction, then: Instruction?): String {
        val first = spoken(instruction).replaceFirstChar { it.uppercase() }
        return if (then != null && then.maneuver != Maneuver.ARRIVE) "$first, puis ${spoken(then)}." else "$first."
    }

    const val OFF_ROUTE = "Tu t'écartes de l'itinéraire."
    const val REROUTED = "Nouvel itinéraire."
    const val ARRIVED = "Tu es à destination."

    /** Distance arrondie comme on la dirait : « 40 mètres », « 150 mètres », « 1,2 kilomètre ». */
    fun spokenDistance(meters: Double): String = when {
        meters < 95 -> "${(meters / 10).roundToInt().coerceAtLeast(1) * 10} mètres"
        meters < 950 -> "${(meters / 50).roundToInt() * 50} mètres"
        else -> {
            val tenths = (meters / 100).roundToInt()
            val whole = tenths / 10
            val fraction = tenths % 10
            val number = if (fraction == 0) "$whole" else "$whole,$fraction"
            if (tenths < 20) "$number kilomètre" else "$number kilomètres"
        }
    }

    private fun ordinal(n: Int): String = if (n == 1) "1re" else "${n}e"

    private fun spokenOrdinal(n: Int): String = when (n) {
        1 -> "première"
        2 -> "deuxième"
        3 -> "troisième"
        4 -> "quatrième"
        5 -> "cinquième"
        6 -> "sixième"
        else -> "${n}e"
    }
}

/** Une annonce à faire entendre (ou à signaler par une vibration si la voix est coupée). */
data class Announcement(val kind: Kind, val text: String) {
    enum class Kind { PREPARE, NOW, OFF_ROUTE, ARRIVED }
}

/**
 * Décide quand parler : chaque consigne est annoncée une fois à l'approche et une fois au moment d'agir ;
 * la sortie d'itinéraire et l'arrivée, une fois chacune.
 *
 * Les distances d'annonce suivent la vitesse : à 30 km/h on prévient plus tôt qu'à 12 km/h.
 */
class GuidanceAnnouncer {
    private var preparedFor: Instruction? = null
    private var announcedFor: Instruction? = null
    private var offRouteSaid = false
    private var arrivedSaid = false

    fun update(state: NavState, speedMps: Double?): List<Announcement> {
        val out = ArrayList<Announcement>(1)
        if (state.arrived) {
            if (!arrivedSaid) {
                arrivedSaid = true
                out.add(Announcement(Announcement.Kind.ARRIVED, GuidancePhrases.ARRIVED))
            }
            return out
        }
        if (state.isOffRoute) {
            if (!offRouteSaid) {
                offRouteSaid = true
                out.add(Announcement(Announcement.Kind.OFF_ROUTE, GuidancePhrases.OFF_ROUTE))
            }
            return out
        }
        offRouteSaid = false

        val next = state.next ?: return out
        if (next.maneuver == Maneuver.ARRIVE) return out
        val speed = (speedMps ?: 5.0).coerceIn(2.0, 15.0)
        val nowAt = max(NOW_MIN_M, speed * NOW_SECONDS)
        val prepareAt = max(PREPARE_MIN_M, speed * PREPARE_SECONDS)
        val distance = state.distanceToNextM
        if (distance <= nowAt) {
            if (announcedFor !== next) {
                announcedFor = next
                preparedFor = next
                out.add(Announcement(Announcement.Kind.NOW, GuidancePhrases.now(next, state.then)))
            }
        } else if (distance <= prepareAt && preparedFor !== next) {
            preparedFor = next
            // « Continue tout droit » ne s'annonce qu'une fois, au carrefour : inutile d'y préparer.
            if (next.maneuver != Maneuver.CONTINUE) {
                out.add(Announcement(Announcement.Kind.PREPARE, GuidancePhrases.prepare(next, distance)))
            }
        }
        return out
    }

    private companion object {
        const val NOW_MIN_M = 25.0
        const val NOW_SECONDS = 4.0
        const val PREPARE_MIN_M = 120.0
        const val PREPARE_SECONDS = 22.0
    }
}
