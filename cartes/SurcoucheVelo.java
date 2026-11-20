import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmRelationInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Surcouche vélo de VeloGo : tout ce qui manque au fond de carte Protomaps pour faire une vraie carte cycliste.
 *
 * Profil Planetiler (https://github.com/onthegomap/planetiler) exécuté tel quel, sans compilation préalable :
 *   java -cp planetiler.jar cartes/SurcoucheVelo.java --osm=france.osm.pbf --sortie=velo.pmtiles
 *
 * Couches produites (données © les contributeurs d'OpenStreetMap, licence ODbL) :
 *   - itineraires : itinéraires cyclables balisés (EuroVelo, véloroutes nationales, régionales, locales, VTT) ;
 *   - voies       : aménagements cyclables, revêtements, difficulté VTT, voies interdites aux vélos ;
 *   - points      : points d'eau, ateliers de réparation, magasins, abris, toilettes, cols…
 */
public class SurcoucheVelo implements Profile {

  private record Itineraire(long id, String nom, String ref, String reseau) implements OsmRelationInfo {}

  private static final Set<String> REVETU = Set.of("paved", "asphalt", "concrete", "concrete:plates", "concrete:lanes",
    "paving_stones", "chipseal", "metal", "wood", "rubber", "tartan");
  private static final Set<String> PAVE = Set.of("sett", "cobblestone", "unhewn_cobblestone", "bricks", "brick", "grass_paver");
  private static final Set<String> STABILISE = Set.of("compacted", "fine_gravel");
  private static final Set<String> ROUTES = Set.of("living_street", "residential", "unclassified", "service", "tertiary",
    "tertiary_link", "secondary", "secondary_link", "primary", "primary_link", "trunk", "trunk_link", "road", "busway");
  private static final Set<String> CHEMINS = Set.of("cycleway", "path", "track", "footway", "pedestrian", "bridleway");
  private static final Set<String> BANDE = Set.of("lane", "opposite_lane");
  private static final Set<String> PARTAGE = Set.of("shared_lane", "share_busway", "opposite_share_busway");
  private static final Set<String> PISTE = Set.of("track", "opposite_track");

  @Override
  public List<OsmRelationInfo> preprocessOsmRelation(OsmElement.Relation relation) {
    if (!relation.hasTag("type", "route") || !relation.hasTag("route", "bicycle", "mtb")) return null;
    if (relation.hasTag("state", "proposed") || relation.hasTag("proposed")) return null;
    String reseau;
    if (relation.hasTag("route", "mtb")) reseau = "vtt";
    else if (relation.hasTag("network", "icn")) reseau = "europeen";
    else if (relation.hasTag("network", "ncn")) reseau = "national";
    else if (relation.hasTag("network", "rcn")) reseau = "regional";
    else reseau = "local";
    return List.of(new Itineraire(relation.id(), relation.getString("name"), relation.getString("ref"), reseau));
  }

  /** Classe de revêtement : revetu, pave, stabilise (roulant en gravel) ou meuble (VTT). Null si inconnu. */
  private static String revetement(SourceFeature f) {
    String s = f.getString("surface");
    if (s != null) {
      if (REVETU.contains(s)) return "revetu";
      if (PAVE.contains(s)) return "pave";
      if (STABILISE.contains(s)) return "stabilise";
      return "meuble";
    }
    String t = f.getString("tracktype");
    if (t == null) return null;
    return switch (t) {
      case "grade1" -> "revetu";
      case "grade2" -> "stabilise";
      default -> "meuble";
    };
  }

  private static boolean auMoinsUn(SourceFeature f, Set<String> valeurs, String... cles) {
    for (String cle : cles) {
      String v = f.getString(cle);
      if (v != null && valeurs.contains(v)) return true;
    }
    return false;
  }

  @Override
  public void processFeature(SourceFeature f, FeatureCollector features) {
    if (f.canBeLine()) {
      for (var info : f.relationInfo(Itineraire.class)) {
        Itineraire r = info.relation();
        int zoomMin = switch (r.reseau()) {
          case "europeen", "national" -> 6;
          case "regional" -> 8;
          case "local" -> 10;
          default -> 11;
        };
        features.line("itineraires")
          .setAttr("reseau", r.reseau())
          .setAttr("ref", r.ref())
          .setAttr("nom", r.nom())
          .setMinZoom(zoomMin)
          .setMinPixelSize(0)
          .setSortKey(-zoomMin);
      }

      String hw = f.getString("highway");
      if (hw != null && (ROUTES.contains(hw) || CHEMINS.contains(hw) || hw.equals("motorway") || hw.equals("motorway_link"))) {
        String velo = f.getString("bicycle");
        boolean chemin = CHEMINS.contains(hw);
        String[] cles = {"cycleway", "cycleway:right", "cycleway:left", "cycleway:both"};

        String type = null;
        if (hw.equals("cycleway") || f.hasTag("bicycle_road", "yes") || f.hasTag("cyclestreet", "yes")
          || (chemin && "designated".equals(velo))) {
          type = "piste";
        } else if (auMoinsUn(f, PISTE, cles)) {
          type = "piste_chaussee";
        } else if (auMoinsUn(f, BANDE, cles)) {
          type = "bande";
        } else if (auMoinsUn(f, PARTAGE, cles)) {
          type = "partage";
        } else if (f.hasTag("oneway", "yes") && (f.hasTag("oneway:bicycle", "no") || f.hasTag("cycleway", "opposite"))) {
          type = "contresens";
        }

        boolean interdit = "no".equals(velo) || f.hasTag("motorroad", "yes")
          || ((hw.startsWith("motorway") || hw.startsWith("trunk")) && !"yes".equals(velo) && !"designated".equals(velo) && hw.startsWith("motorway"));
        String rev = revetement(f);
        String vtt = f.getString("mtb:scale");
        // On ne garde que ce qui apporte une information au cycliste : le reste est déjà sur le fond de carte.
        boolean utile = type != null || interdit || vtt != null
          || (rev != null && (chemin || !rev.equals("revetu")));
        if (utile && !(hw.startsWith("motorway") && !interdit)) {
          int zoomMin = type != null && type.startsWith("piste") ? 9 : (type != null || interdit) ? 11 : chemin ? 12 : 11;
          features.line("voies")
            .setAttr("type", type)
            .setAttr("revetement", rev)
            .setAttr("chemin", chemin ? 1 : null)
            .setAttr("vtt", vtt == null ? null : vtt.replaceAll("[^0-6]", ""))
            .setAttr("interdit", interdit ? 1 : null)
            .setMinZoom(zoomMin)
            .setMinPixelSize(0);
        }
      }
    }

    if (f.isPoint()) {
      String type = null;
      int zoomMin = 14;
      if (f.hasTag("mountain_pass", "yes")) { type = "col"; zoomMin = 9; }
      else if (f.hasTag("amenity", "drinking_water") || (f.hasTag("drinking_water", "yes") && f.hasTag("amenity", "fountain", "water_point"))) { type = "eau"; zoomMin = 12; }
      else if (f.hasTag("amenity", "bicycle_repair_station")) { type = "reparation"; zoomMin = 12; }
      else if (f.hasTag("shop", "bicycle")) { type = "magasin"; zoomMin = 12; }
      else if (f.hasTag("amenity", "shelter")) { type = "abri"; zoomMin = 13; }
      else if (f.hasTag("tourism", "camp_site")) { type = "camping"; zoomMin = 12; }
      else if (f.hasTag("tourism", "viewpoint")) { type = "point_de_vue"; zoomMin = 13; }
      else if (f.hasTag("amenity", "toilets")) { type = "toilettes"; zoomMin = 14; }
      else if (f.hasTag("amenity", "bicycle_rental")) { type = "location"; zoomMin = 14; }
      else if (f.hasTag("amenity", "bicycle_parking")) { type = "stationnement"; zoomMin = 15; }
      if (type != null) {
        features.point("points")
          .setAttr("type", type)
          .setAttr("nom", f.getString("name"))
          .setAttr("ele", f.getString("ele"))
          .setMinZoom(Math.min(zoomMin, 14));
      }
    }
  }

  @Override
  public List<VectorTile.Feature> postProcessLayerFeatures(String layer, int zoom, List<VectorTile.Feature> items) throws GeometryException {
    if (layer.equals("points")) return items;
    // Les tronçons contigus de mêmes attributs sont soudés : tuiles plus légères, tracés continus.
    return FeatureMerge.mergeLineStrings(items, 0.33, zoom >= 14 ? 0.0625 : 0.125, 4);
  }

  @Override public String name() { return "VeloGo - surcouche vélo"; }
  @Override public String description() { return "Itinéraires cyclables, aménagements, revêtements et points utiles aux cyclistes"; }
  @Override public boolean isOverlay() { return true; }
  @Override public String attribution() {
    return "<a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\">&copy; les contributeurs d'OpenStreetMap</a>";
  }

  public static void main(String[] args) throws Exception {
    Arguments a = Arguments.fromArgsOrConfigFile(args);
    Planetiler.create(a)
      .setProfile(new SurcoucheVelo())
      .addOsmSource("osm", Path.of(a.getString("osm", "extrait OpenStreetMap (.osm.pbf)", "france-latest.osm.pbf")))
      .overwriteOutput(Path.of(a.getString("sortie", "fichier produit (.pmtiles)", "velo.pmtiles")))
      .run();
  }
}
