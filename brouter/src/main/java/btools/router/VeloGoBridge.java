package btools.router;

import java.util.ArrayList;
import java.util.List;

/**
 * Ajout de VeloGo (ce fichier ne vient pas de BRouter).
 *
 * BRouter garde internes à son paquet les consignes de changement de direction et les attributs des voies
 * empruntées. Cette petite passerelle les expose à notre code, sans toucher aux classes d'origine.
 */
public final class VeloGoBridge {

  private VeloGoBridge() {
  }

  /** Une consigne de changement de direction, telle que BRouter l'a calculée. */
  public static final class Hint {
    /** Rang, dans la trace, du point où la consigne s'applique. */
    public int indexInTrack;
    /** Code de la manœuvre (constantes ci-dessous). */
    public int command;
    /** Angle du virage en degrés (négatif à gauche). */
    public float angle;
    /** Numéro de la sortie pour un giratoire, 0 sinon. */
    public int roundaboutExit;
  }

  public static final int CONTINUE = VoiceHint.C;
  public static final int LEFT = VoiceHint.TL;
  public static final int SLIGHT_LEFT = VoiceHint.TSLL;
  public static final int SHARP_LEFT = VoiceHint.TSHL;
  public static final int RIGHT = VoiceHint.TR;
  public static final int SLIGHT_RIGHT = VoiceHint.TSLR;
  public static final int SHARP_RIGHT = VoiceHint.TSHR;
  public static final int KEEP_LEFT = VoiceHint.KL;
  public static final int KEEP_RIGHT = VoiceHint.KR;
  public static final int U_TURN_LEFT = VoiceHint.TLU;
  public static final int U_TURN_RIGHT = VoiceHint.TRU;
  public static final int ROUNDABOUT = VoiceHint.RNDB;
  public static final int ROUNDABOUT_LEFT = VoiceHint.RNLB;
  public static final int U_TURN = VoiceHint.TU;
  public static final int BEELINE = VoiceHint.BL;
  public static final int EXIT_LEFT = VoiceHint.EL;
  public static final int EXIT_RIGHT = VoiceHint.ER;

  /** Consignes d'une trace, dans l'ordre du parcours (liste vide si BRouter n'en a pas calculé). */
  public static List<Hint> hints(OsmTrack track) {
    List<Hint> out = new ArrayList<>();
    if (track == null || track.voiceHints == null) {
      return out;
    }
    for (VoiceHint source : track.voiceHints.list) {
      Hint hint = new Hint();
      hint.indexInTrack = source.indexInTrack;
      hint.command = source.cmd;
      hint.angle = source.angle;
      hint.roundaboutExit = source.roundaboutExit;
      out.add(hint);
    }
    return out;
  }

  /** Attributs OpenStreetMap de la voie qui mène à ce point (« highway=cycleway surface=asphalt … »), ou null. */
  public static String wayTags(OsmPathElement element) {
    return element == null || element.message == null ? null : element.message.wayKeyValues;
  }
}
