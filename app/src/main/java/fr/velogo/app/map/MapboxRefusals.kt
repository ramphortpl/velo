package fr.velogo.app.map

/**
 * Reconnaît un fond que Mapbox refuse d'envoyer.
 *
 * Quand Mapbox refuse une tuile (limite du compte de l'appli atteinte, service auquel ce compte n'a pas droit, jeton
 * retiré), son kit le note dans le journal et la carte reste vide, sans rien dire à l'utilisateur. On compte donc,
 * pour chaque source du fond, les refus reçus d'affilée ; la moindre tuile arrivée remet le compte à zéro. Une
 * source qui atteint [threshold] refus est « à vérifier » : si rien n'est arrivé d'elle [graceMs] plus tard
 * ([isHopeless]), la carte ne s'affichera pas, et l'écran peut revenir à un fond qui marche.
 *
 * Ce délai protège d'une salve de refus passagère : Mapbox limite aussi le débit, les réponses arrivent dans le
 * désordre, et quelques refus peuvent précéder des tuiles qui, elles, arrivent.
 *
 * Aucun appel au kit ici : la classe se teste sans lui, et l'heure lui est donnée.
 */
internal class RefusalWatch(private val threshold: Int = THRESHOLD, private val graceMs: Long = GRACE_MS) {
    private class Streak(var count: Int = 0, var sinceMs: Long = 0L)

    private val streaks = HashMap<String, Streak>()

    /** Tout oublier : un autre fond se charge. */
    fun reset() = streaks.clear()

    /**
     * [source] vient d'essuyer un refus. [whole] : c'est sa description même qui est refusée, aucune de ses tuiles
     * ne sera donc demandée, et un seul refus suffit.
     *
     * Vrai si elle atteint le seuil à l'instant : c'est le moment de programmer la vérification, dans [graceMs].
     */
    fun refused(source: String, nowMs: Long, whole: Boolean = false): Boolean {
        val streak = streaks.getOrPut(source) { Streak() }
        val before = streak.count
        streak.count = if (whole) maxOf(before, threshold) else before + 1
        if (before >= threshold || streak.count < threshold) return false
        streak.sinceMs = nowMs
        return true
    }

    /** Une tuile de [source] est arrivée : Mapbox la sert, ses refus précédents étaient passagers. */
    fun served(source: String) {
        streaks.remove(source)
    }

    /** Vrai si [source] a atteint le seuil depuis au moins [graceMs] sans qu'aucune de ses tuiles soit arrivée. */
    fun isHopeless(source: String, nowMs: Long): Boolean {
        val streak = streaks[source] ?: return false
        return streak.count >= threshold && nowMs - streak.sinceMs >= graceMs
    }

    companion object {
        /** Refus d'affilée d'une même source avant de s'inquiéter : un écran de téléphone demande au moins quatre tuiles. */
        const val THRESHOLD = 4

        /** Le temps laissé aux autres tuiles de la source pour arriver. */
        const val GRACE_MS = 2_500L

        /**
         * Vrai si ce message d'erreur du kit est un refus du service de Mapbox : trop de demandes pour ce compte ou
         * service fermé à ce compte (429), jeton non reconnu (401) ou sans droit (403). Une panne de réseau, une
         * tuile qui n'existe pas (404) ou un serveur en panne (5xx) n'en sont pas.
         *
         * La forme du message a été relevée sur l'émulateur (fabrication n° 39) :
         * « Failed to load tile: HTTP status code 429. Too Many Requests ».
         */
        fun isRefusal(message: String?): Boolean = message != null && REFUSAL.containsMatchIn(message)

        private val REFUSAL = Regex("""status code (401|403|429)\b""", RegexOption.IGNORE_CASE)
    }
}
