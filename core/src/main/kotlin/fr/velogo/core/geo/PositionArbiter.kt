package fr.velogo.core.geo

/**
 * Dit quand se servir de la position « approchée » du téléphone (Wi-Fi, réseau mobile) à la place du GPS.
 *
 * Le GPS reste la seule source des sorties enregistrées et du guidage. Mais tant qu'il n'a pas trouvé ses
 * satellites (en intérieur, ou pendant les longues minutes d'un démarrage à froid sur certains téléphones), la
 * carte montre la position que le téléphone déduit du Wi-Fi et du réseau : on voit où l'on est tout de suite,
 * et la météo comme le calcul d'un itinéraire fonctionnent.
 *
 * Trois règles, pour que cette position approchée ne gêne jamais le GPS :
 * - au début d'une écoute, on laisse d'abord quelques secondes au GPS ([graceMs]) : quand il est prêt, il répond
 *   tout de suite et la position approchée ne s'affiche même pas ;
 * - quand le GPS a déjà répondu, il faut qu'il se taise un bon moment ([silenceMs]) pour qu'une position
 *   approchée prenne le relais, et seulement si elle est assez fine ([fineAccuracyM]) : en pleine nature, une
 *   position déduite d'une antenne à un kilomètre ferait sauter la carte à chaque passage sous les arbres ;
 * - une position grossière n'est acceptée que si l'on ne sait rien de mieux : GPS jamais reçu, ou muet depuis
 *   si longtemps ([staleMs]) que sa dernière position ne veut plus rien dire.
 *
 * Tous les instants sont ceux d'une horloge qui ne recule jamais, en millisecondes.
 */
class PositionArbiter(
    private val graceMs: Long = 4_000,
    private val silenceMs: Long = 30_000,
    private val staleMs: Long = 5 * 60_000,
    private val fineAccuracyM: Double = 200.0,
    private val maxAccuracyM: Double = 10_000.0,
    private val helpAfterMs: Long = 20_000,
    private val helpEveryMs: Long = 10 * 60_000,
) {
    private var startedMs: Long? = null
    private var lastPreciseMs: Long? = null
    private var lastHelpMs: Long? = null

    /** Une écoute commence (l'appli vient d'allumer le GPS). */
    fun start(nowMs: Long) {
        startedMs = nowMs
    }

    /** Le GPS vient de donner une position. */
    fun onPrecise(nowMs: Long) {
        lastPreciseMs = nowMs
    }

    /** Vrai tant que le GPS ne suffit pas : il faut alors écouter aussi la position approchée du téléphone. */
    fun needsAssist(nowMs: Long): Boolean = silentFor(nowMs)?.let { it >= silenceMs } ?: true

    /**
     * Faut-il montrer cette position approchée, de précision [accuracyM] (null si le téléphone ne la donne pas) ?
     * Faux pendant le délai laissé au GPS : la position est alors à représenter un peu plus tard.
     */
    fun acceptApproximate(nowMs: Long, accuracyM: Double?): Boolean {
        val started = startedMs ?: return false
        if (nowMs - started < graceMs) return false
        val accuracy = accuracyM ?: return false
        if (accuracy.isNaN() || accuracy < 0.0 || accuracy > maxAccuracyM) return false
        val silent = silentFor(nowMs) ?: return true
        return when {
            silent < silenceMs -> false
            silent >= staleMs -> true
            else -> accuracy <= fineAccuracyM
        }
    }

    /**
     * Vrai une fois de temps en temps quand le GPS cherche depuis un moment sans rien trouver : c'est l'instant
     * de redemander au téléphone l'heure exacte et la position des satellites, qui accélèrent sa recherche.
     */
    fun shouldHelp(nowMs: Long): Boolean {
        val since = searchingSinceMs() ?: return false
        if (nowMs - since < helpAfterMs) return false
        val last = lastHelpMs
        if (last != null && nowMs - last < helpEveryMs) return false
        lastHelpMs = nowMs
        return true
    }

    /** L'aide vient d'être demandée à la main : inutile de la redemander tout de suite. */
    fun onHelped(nowMs: Long) {
        lastHelpMs = nowMs
    }

    /** Depuis quand le GPS cherche : sa dernière position, ou le début de l'écoute s'il n'a rien donné depuis. */
    fun searchingSinceMs(): Long? {
        val started = startedMs ?: return null
        val precise = lastPreciseMs
        return if (precise != null && precise > started) precise else started
    }

    private fun silentFor(nowMs: Long): Long? = lastPreciseMs?.let { (nowMs - it).coerceAtLeast(0) }
}
