// Fabrique les styles de la carte de VeloGo, en clair et en sombre :
//   - « en ligne »   : tuiles vectorielles d'OpenFreeMap (schéma OpenMapTiles) + relief de Mapterhorn ;
//   - « hors ligne » : fond Protomaps + relief + surcouche vélo (cartes/SurcoucheVelo.java), servis par le téléphone.
//
//   cd cartes/style && npm install && npm run generer
//
// Les deux cartes partagent une même palette (THEMES) : même sol, mêmes routes, mêmes noms, pour qu'on ne voie pas
// la différence en passant de l'une à l'autre. Les fichiers produits sont versionnés dans app/src/main/assets/ :
// la compilation de l'appli n'a donc pas besoin de Node. À relancer quand on retouche les couleurs ou les couches.
import { layers, namedFlavor } from "@protomaps/basemaps";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Adresse fictive : dans l'appli, ces requêtes sont interceptées et servies depuis les packs du téléphone.
const HOTE = "https://hors-ligne.velogo.invalid";
const OPENFREEMAP = "https://tiles.openfreemap.org";
const MAPTERHORN = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp";
const ASSETS = join(dirname(fileURLToPath(import.meta.url)), "../../app/src/main/assets");

// ---------------------------------------------------------------------------------------------------------------
// Palette. Un sol clair et calme, des routes blanches, le relief en ombres douces : ce qui compte à vélo (pistes,
// grands axes à éviter, chemins) ressort sans que la carte crie. Les grands axes gardent une teinte chaude : on
// les repère d'un coup d'œil pour les éviter.
// ---------------------------------------------------------------------------------------------------------------
export const THEMES = {
  clair: {
    sprites: "light",
    // Sol
    terre: "#F5F3EE", bati: "#F0EDE7", culture: "#F3F2E8", herbe: "#E3EFD8", broussaille: "#DCEAD0", foret: "#CCE3C3",
    parc: "#D0E9C5", zoneHumide: "#DAEBE2", glace: "#FFFFFF", roche: "#EAE7E0", sable: "#F5EDD1",
    reserve: "#7DB77A",
    // Eau
    eau: "#A8D6F0", eauTexte: "#3A7CAB",
    // Bâti et emprises
    batiment: "#E5E0D7", batimentBord: "#D5CFC4",
    hopital: "#F4E7E4", ecole: "#F2EBDC", industrie: "#EBEAE6", cimetiere: "#D9E5D2", sport: "#D4EBCA", pieton: "#F2EFE7",
    aeroport: "#E9E9ED", pisteAvion: "#F7F7F9",
    // Routes
    route: "#FFFFFF", routeBord: "#D4CEC4", desserte: "#FBFAF7",
    axe: "#FFF3CB", axeBord: "#E3CB80",
    voieRapide: "#FDDCA6", voieRapideBord: "#E2AA5C",
    tunnel: "#F0EDE7", tunnelBord: "#DAD5CC",
    sentier: "#A39A8E", rail: "#B3B8BC", frontiere: "#B3A2C8",
    // Noms
    texte: "#26322E", texteDoux: "#5F6964", texteRoute: "#6C716C", halo: "#FFFFFF",
    // Relief
    ombre: "#43524D", lumiere: "#FFFFFF", relief: 0.3,
    // Vélo
    piste: "#00A47A", lisere: "#FFFFFF",
    national: "#7C4DDB", haloNational: "#D9CBF8", haloRegional: "#E5DCFA", haloLocal: "#D6E6FB", haloVtt: "#F7DFC6",
    nonRevetu: "#A8876B", pave: "#7F8C94", interdit: "#D0453F",
    pointEau: "#1E88E5", reparation: "#E07B1A", magasin: "#7C4DDB", abri: "#8D6E63", toilettes: "#6B7A85",
    camping: "#3E9B4F", pointDeVue: "#D2377A", stationnement: "#6B7A85", sommet: "#8A6F58",
  },
  sombre: {
    sprites: "dark",
    terre: "#14191B", bati: "#171D1F", culture: "#151B1B", herbe: "#16221B", broussaille: "#162119", foret: "#15271F",
    parc: "#172A20", zoneHumide: "#14242A", glace: "#2A3236", roche: "#1B2022", sable: "#22211B",
    reserve: "#3F7A4D",
    eau: "#0E2836", eauTexte: "#6AA7CC",
    batiment: "#1D2326", batimentBord: "#272F32",
    hopital: "#221B1C", ecole: "#211F19", industrie: "#1A1E20", cimetiere: "#161F18", sport: "#18261D", pieton: "#1C2123",
    aeroport: "#1A1D21", pisteAvion: "#25292D",
    route: "#434C50", routeBord: "#1A1F21", desserte: "#2F373A",
    axe: "#6A5F38", axeBord: "#2B2716",
    voieRapide: "#85602D", voieRapideBord: "#33260F",
    tunnel: "#262C2E", tunnelBord: "#1A1E20",
    sentier: "#6D756E", rail: "#454D51", frontiere: "#6E5F86",
    texte: "#E3E9E6", texteDoux: "#9AA5A0", texteRoute: "#919B96", halo: "#14191B",
    ombre: "#000000", lumiere: "#4A575D", relief: 0.42,
    piste: "#01CD95", lisere: "#0C1113",
    national: "#B49BF0", haloNational: "#3F3369", haloRegional: "#322A52", haloLocal: "#1E3450", haloVtt: "#4D3922",
    nonRevetu: "#B79A82", pave: "#9AA8B0", interdit: "#EE8E88",
    pointEau: "#5FB2F2", reparation: "#F0A152", magasin: "#B49BF0", abri: "#BCA69C", toilettes: "#9FADB6",
    camping: "#7CC78A", pointDeVue: "#F088B3", stationnement: "#9FADB6", sommet: "#BCA08A",
  },
};
const POLICE = { normal: ["Noto Sans Regular"], italique: ["Noto Sans Italic"] };
const LIGNE = { "line-cap": "round", "line-join": "round" };
const BOUT_DROIT = { "line-cap": "butt", "line-join": "round" };

/** Valeur qui varie avec le zoom, de façon exponentielle : `[[zoom, valeur], …]`. */
const selonZoom = (paliers, base = 1.5) => ["interpolate", ["exponential", base], ["zoom"], ...paliers.flat()];
const parmi = (champ, valeurs) => ["match", ["get", champ], valeurs, true, false];
const horsDe = (champ, valeurs) => ["match", ["get", champ], valeurs, false, true];

/** Ombrage du relief : le même sur les deux cartes. */
function ombrage(p) {
  return {
    id: "relief_ombrage", type: "hillshade", source: "relief",
    paint: {
      "hillshade-exaggeration": p.relief, "hillshade-shadow-color": p.ombre,
      "hillshade-highlight-color": p.lumiere, "hillshade-accent-color": p.ombre,
    },
  };
}

// ===============================================================================================================
// Carte en ligne : schéma OpenMapTiles (tuiles d'OpenFreeMap, zooms 0 à 14)
// ===============================================================================================================

/**
 * Les routes, de la plus petite à la plus grande. `l` : largeur du trait en pixels selon le zoom ; `bord` : zoom à
 * partir duquel on dessine le liseré (avant, la route n'est qu'un fil).
 */
function reseau(p) {
  return [
    { id: "desserte", classes: ["service"], minzoom: 13, bord: 15, couleur: p.desserte, liseré: p.routeBord,
      l: [[13, 0.5], [15, 1.6], [17, 5], [20, 16]] },
    { id: "rue", classes: ["minor", "busway"], minzoom: 11, bord: 13, couleur: p.route, liseré: p.routeBord,
      l: [[11, 0.4], [13, 1.2], [14, 2.2], [16, 6], [18, 15], [20, 36]] },
    { id: "tertiaire", classes: ["tertiary"], minzoom: 9, bord: 12, couleur: p.route, liseré: p.routeBord,
      l: [[9, 0.4], [11, 0.9], [13, 2.1], [14, 3.1], [16, 7.5], [18, 18], [20, 42]] },
    { id: "secondaire", classes: ["secondary"], minzoom: 8, bord: 11, couleur: p.route, liseré: p.routeBord,
      l: [[8, 0.4], [11, 1.3], [13, 2.7], [14, 3.9], [16, 9], [18, 20], [20, 46]] },
    { id: "axe", classes: ["primary"], minzoom: 6, bord: 9, couleur: p.axe, liseré: p.axeBord,
      l: [[6, 0.5], [9, 1.2], [12, 2.6], [14, 4.6], [16, 10], [18, 22], [20, 50]] },
    { id: "voie_rapide", classes: ["motorway", "trunk"], minzoom: 5, bord: 8, couleur: p.voieRapide, liseré: p.voieRapideBord,
      l: [[5, 0.6], [8, 1.4], [12, 3.2], [14, 5.4], [16, 11], [18, 26], [20, 58]] },
  ];
}

/** Largeur d'une route : les bretelles sont plus fines que la voie qu'elles desservent. */
const largeurRoute = (paliers, plus = 0) =>
  selonZoom(paliers.map(([z, l]) => [z, ["case", ["==", ["get", "ramp"], 1], +(l * 0.62 + plus).toFixed(2), +(l + plus).toFixed(2)]]));

function routesEnLigne(p) {
  const tiers = reseau(p);
  const ligne = (id, filtre, paint, extra = {}) => ({
    id, type: "line", source: "openmaptiles", "source-layer": "transportation", filter: filtre, layout: LIGNE, paint, ...extra,
  });
  const surSol = horsDe("brunnel", ["bridge", "tunnel"]);
  const enTunnel = ["==", ["get", "brunnel"], "tunnel"];
  const surPont = ["==", ["get", "brunnel"], "bridge"];
  const trait = ["match", ["geometry-type"], ["LineString", "MultiLineString"], true, false];
  const estPiste = ["all", ["==", ["get", "class"], "path"],
    ["any", ["==", ["get", "subclass"], "cycleway"], ["==", ["get", "bicycle"], "designated"]]];
  const estSentier = ["all", trait, parmi("class", ["path", "track"]), ["!", estPiste],
    horsDe("subclass", ["platform", "corridor"])];
  const couches = [];

  // --- Tunnels : la route en plus pâle, liseré en pointillé -------------------------------------------------
  for (const t of tiers) {
    couches.push(ligne(`tunnel_${t.id}_bord`, ["all", enTunnel, parmi("class", t.classes)],
      { "line-color": p.tunnelBord, "line-width": largeurRoute(t.l, 1.6), "line-dasharray": [0.6, 0.5] },
      { minzoom: Math.max(t.bord, 12), layout: BOUT_DROIT }));
  }
  for (const t of tiers) {
    couches.push(ligne(`tunnel_${t.id}`, ["all", enTunnel, parmi("class", t.classes)],
      { "line-color": p.tunnel, "line-width": largeurRoute(t.l) }, { minzoom: Math.max(t.minzoom, 11) }));
  }

  // --- Places et rues piétonnes ------------------------------------------------------------------------------
  couches.push({
    id: "place_pietonne", type: "fill", source: "openmaptiles", "source-layer": "transportation", minzoom: 13,
    filter: ["all", ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false], horsDe("class", ["pier", "bridge"])],
    paint: { "fill-color": p.pieton },
  });

  // --- Chemins et sentiers : un pointillé fin, sous les routes ----------------------------------------------
  // Deux couches, parce que le pointillé ne peut pas dépendre de l'objet dessiné dans MapLibre Native (Android) :
  // tirets longs pour les chemins carrossables, courts pour les sentiers.
  const largeurSentier = selonZoom([[12, 0.5], [14, 0.9], [17, 1.8], [20, 4]]);
  couches.push(ligne("sentier", ["all", estSentier, ["!=", ["get", "class"], "track"], horsDe("brunnel", ["tunnel"])],
    { "line-color": p.sentier, "line-width": largeurSentier, "line-dasharray": [2, 1.6] }, { minzoom: 12.5, layout: BOUT_DROIT }));
  couches.push(ligne("chemin", ["all", estSentier, ["==", ["get", "class"], "track"], horsDe("brunnel", ["tunnel"])],
    { "line-color": p.sentier, "line-width": largeurSentier, "line-dasharray": [4, 1.5] }, { minzoom: 12, layout: BOUT_DROIT }));

  // --- Routes : tous les liserés, puis tous les traits, pour que les carrefours se fondent -----------------
  for (const t of tiers) {
    couches.push(ligne(`route_${t.id}_bord`, ["all", surSol, parmi("class", t.classes)],
      { "line-color": t.liseré, "line-width": largeurRoute(t.l, 1.7) }, { minzoom: t.bord }));
  }
  for (const t of tiers) {
    couches.push(ligne(`route_${t.id}`, ["all", surSol, parmi("class", t.classes)],
      { "line-color": t.couleur, "line-width": largeurRoute(t.l) }, { minzoom: t.minzoom }));
  }

  // --- Voies ferrées -----------------------------------------------------------------------------------------
  couches.push(ligne("rail", ["all", parmi("class", ["rail", "transit"]), horsDe("brunnel", ["tunnel"])],
    { "line-color": p.rail, "line-width": selonZoom([[8, 0.4], [13, 0.9], [16, 1.8], [20, 4]]),
      "line-opacity": ["match", ["get", "class"], "transit", 0.6, 1] }, { minzoom: 8 }));

  // --- Ponts : par-dessus tout le reste ----------------------------------------------------------------------
  for (const t of tiers) {
    couches.push(ligne(`pont_${t.id}_bord`, ["all", surPont, parmi("class", t.classes)],
      { "line-color": t.liseré, "line-width": largeurRoute(t.l, 2.2) }, { minzoom: Math.max(t.bord, 12), layout: BOUT_DROIT }));
  }
  for (const t of tiers) {
    couches.push(ligne(`pont_${t.id}`, ["all", surPont, parmi("class", t.classes)],
      { "line-color": t.couleur, "line-width": largeurRoute(t.l) }, { minzoom: Math.max(t.minzoom, 11) }));
  }

  // --- Pistes cyclables et voies vertes : la couleur de VeloGo, sur un fin liseré ---------------------------
  const largeurPiste = (k) => selonZoom([[10, 0.6 * k], [13, 1.3 * k], [15, 2.1 * k], [17, 3.4 * k], [20, 7 * k]]);
  couches.push(ligne("piste_lisere", estPiste, { "line-color": p.lisere, "line-opacity": 0.85, "line-width": largeurPiste(1.9) }, { minzoom: 11 }));
  couches.push(ligne("piste_non_revetue", ["all", estPiste, ["==", ["get", "surface"], "unpaved"]],
    { "line-color": p.piste, "line-width": largeurPiste(1), "line-dasharray": [3.5, 1.4] }, { minzoom: 10, layout: BOUT_DROIT }));
  couches.push(ligne("piste", ["all", estPiste, ["!=", ["get", "surface"], "unpaved"]],
    { "line-color": p.piste, "line-width": largeurPiste(1) }, { minzoom: 10 }));
  return couches;
}

/** Nom à afficher : le français quand il existe, sinon le nom local. */
const NOM = ["coalesce", ["get", "name:fr"], ["get", "name:latin"], ["get", "name"]];

function nomsEnLigne(p) {
  const texte = (id, couche, filtre, layout, paint = {}, extra = {}) => ({
    id, type: "symbol", source: "openmaptiles", "source-layer": couche, ...(filtre ? { filter: filtre } : {}),
    layout: { "text-font": POLICE.normal, "text-field": NOM, ...layout },
    paint: { "text-color": p.texteDoux, "text-halo-color": p.halo, "text-halo-width": 1.4, ...paint }, ...extra,
  });
  const point = ["match", ["geometry-type"], ["Point", "MultiPoint"], true, false];
  const trait = ["match", ["geometry-type"], ["LineString", "MultiLineString"], true, false];
  const lieu = (id, classes, minzoom, taille, extra = {}, paint = {}) => texte(`lieu_${id}`, "place", parmi("class", classes),
    { "text-size": selonZoom(taille, 1.2), "text-max-width": 8, "symbol-sort-key": ["coalesce", ["get", "rank"], 20], ...extra },
    { "text-color": p.texte, "text-halo-width": 1.6, ...paint }, { minzoom });
  const cercle = (id, sousClasses, couleur, minzoom, rayon = 4) => ({
    id: `point_${id}`, type: "circle", source: "openmaptiles", "source-layer": "poi", minzoom,
    filter: parmi("subclass", sousClasses),
    paint: {
      "circle-color": couleur, "circle-radius": selonZoom([[minzoom, rayon * 0.6], [minzoom + 3, rayon]], 1.2),
      "circle-stroke-color": p.halo, "circle-stroke-width": 1.2,
    },
  });
  return [
    // --- Points utiles à vélo --------------------------------------------------------------------------------
    cercle("toilettes", ["toilets"], p.toilettes, 15.5, 3.5),
    cercle("abri", ["shelter", "alpine_hut", "wilderness_hut"], p.abri, 14.5, 3.5),
    cercle("point_de_vue", ["viewpoint"], p.pointDeVue, 14, 3.5),
    cercle("camping", ["camp_site"], p.camping, 13.5, 3.5),
    cercle("magasin", ["bicycle", "bicycle_rental"], p.magasin, 14),
    cercle("reparation", ["bicycle_repair_station"], p.reparation, 14),
    cercle("eau", ["drinking_water"], p.pointEau, 14, 4.2),

    // --- Eau --------------------------------------------------------------------------------------------------
    texte("eau_cours_nom", "waterway", trait,
      { "text-font": POLICE.italique, "symbol-placement": "line", "symbol-spacing": 350, "text-size": 11.5, "text-letter-spacing": 0.08 },
      { "text-color": p.eauTexte }, { minzoom: 13 }),
    texte("eau_plan_nom", "water_name", point,
      { "text-font": POLICE.italique, "text-size": selonZoom([[6, 11], [12, 13], [16, 15]], 1.2), "text-max-width": 7, "text-letter-spacing": 0.05 },
      { "text-color": p.eauTexte }, { minzoom: 6 }),

    // --- Rues et routes ---------------------------------------------------------------------------------------
    texte("rue_sentier", "transportation_name", ["all", trait, parmi("class", ["path", "track"])],
      { "symbol-placement": "line", "text-size": 10.5, "symbol-spacing": 300 }, { "text-color": p.texteRoute }, { minzoom: 15.5 }),
    texte("rue_nom", "transportation_name", ["all", trait, parmi("class", ["minor", "service", "busway"])],
      { "symbol-placement": "line", "text-size": selonZoom([[14, 10.5], [17, 12.5], [20, 15]], 1.2), "symbol-spacing": 300 },
      { "text-color": p.texteRoute }, { minzoom: 14.5 }),
    texte("route_nom", "transportation_name", ["all", trait, parmi("class", ["motorway", "trunk", "primary", "secondary", "tertiary"])],
      { "symbol-placement": "line", "text-size": selonZoom([[12, 10.5], [16, 12.5], [20, 16]], 1.2), "symbol-spacing": 350 },
      { "text-color": p.texteRoute }, { minzoom: 12.5 }),
    // Le numéro de la route (« D 81 ») : droit, répété le long de la voie.
    texte("route_numero", "transportation_name", ["all", trait, ["has", "ref"], ["<=", ["coalesce", ["get", "ref_length"], 9], 6]],
      { "text-field": ["get", "ref"], "text-font": ["Noto Sans Bold"], "text-size": 10, "symbol-placement": "line",
        "symbol-spacing": 400, "text-rotation-alignment": "viewport", "text-pitch-alignment": "viewport", "text-letter-spacing": 0.04 },
      { "text-color": p.texteRoute, "text-halo-width": 2 }, { minzoom: 9.5 }),

    // --- Points utiles : leur nom, de près --------------------------------------------------------------------
    texte("point_nom", "poi", parmi("subclass", ["drinking_water", "bicycle", "bicycle_rental", "bicycle_repair_station", "camp_site", "viewpoint", "alpine_hut", "wilderness_hut"]),
      { "text-field": ["match", ["get", "subclass"], "drinking_water", "Eau", "bicycle_repair_station", "Atelier vélo",
          "bicycle", ["coalesce", NOM, "Magasin de vélos"], "bicycle_rental", ["coalesce", NOM, "Location de vélos"],
          "camp_site", ["coalesce", NOM, "Camping"], "viewpoint", ["coalesce", NOM, "Point de vue"], ["coalesce", NOM, "Refuge"]],
        "text-size": 11, "text-offset": [0, 0.8], "text-anchor": "top", "text-optional": true, "text-max-width": 8 },
      { "text-color": p.texte }, { minzoom: 16 }),
    texte("sommet_nom", "mountain_peak", ["<=", ["coalesce", ["get", "rank"], 9], 2],
      { "text-field": ["case", ["has", "ele"], ["concat", NOM, "\n", ["to-string", ["get", "ele"]], " m"], NOM],
        "text-font": POLICE.italique, "text-size": 11, "text-max-width": 8 },
      { "text-color": p.sommet, "text-halo-width": 1.5 }, { minzoom: 11 }),
    texte("numero_maison", "housenumber", null,
      { "text-field": ["get", "housenumber"], "text-size": 10 }, { "text-color": p.texteRoute, "text-halo-width": 1 }, { minzoom: 17.5 }),

    // --- Lieux : du quartier à la grande ville ---------------------------------------------------------------
    lieu("quartier", ["suburb", "quarter", "neighbourhood"], 12, [[12, 10.5], [16, 13]],
      { "text-transform": "uppercase", "text-letter-spacing": 0.1 }, { "text-color": p.texteDoux }),
    lieu("hameau", ["hamlet", "isolated_dwelling"], 12.5, [[12, 10.5], [16, 13]], {}, { "text-color": p.texteDoux }),
    lieu("village", ["village"], 10, [[10, 11], [14, 14], [16, 17]]),
    lieu("bourg", ["town"], 7, [[7, 11.5], [11, 14.5], [15, 19]], { "text-font": ["Noto Sans Bold"] }),
    lieu("ville", ["city"], 4, [[4, 12], [8, 15.5], [12, 21], [15, 25]], { "text-font": ["Noto Sans Bold"] }),
    lieu("region", ["state"], 5, [[5, 10.5], [8, 13]],
      { "text-transform": "uppercase", "text-letter-spacing": 0.12 }, { "text-color": p.texteDoux }),
    lieu("pays", ["country"], 3, [[3, 12], [7, 16]], { "text-font": ["Noto Sans Bold"], "text-letter-spacing": 0.08 }),
  ].map((couche) => (couche.id === "lieu_region" ? { ...couche, maxzoom: 9 } : couche.id === "lieu_pays" ? { ...couche, maxzoom: 8 } : couche));
}

function styleEnLigne(nom, p) {
  const surface = (id, couche, filtre, couleur, extra = {}) => ({
    id, type: "fill", source: "openmaptiles", "source-layer": couche, ...(filtre ? { filter: filtre } : {}),
    paint: { "fill-color": couleur, ...(extra.paint ?? {}) }, ...(extra.minzoom ? { minzoom: extra.minzoom } : {}),
    ...(extra.maxzoom ? { maxzoom: extra.maxzoom } : {}),
  });
  const classe = (c) => ["==", ["get", "class"], c];
  const polygone = ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false];
  const couches = [
    { id: "fond", type: "background", paint: { "background-color": p.terre } },

    // --- Ce qui couvre le sol ---------------------------------------------------------------------------------
    surface("sol_cultures", "landcover", classe("farmland"), p.culture),
    surface("sol_herbe", "landcover", classe("grass"), p.herbe),
    surface("sol_foret", "landcover", classe("wood"), p.foret),
    surface("sol_zone_humide", "landcover", classe("wetland"), p.zoneHumide),
    surface("sol_roche", "landcover", classe("rock"), p.roche),
    surface("sol_sable", "landcover", classe("sand"), p.sable),
    surface("sol_glace", "landcover", classe("ice"), p.glace),
    surface("emprise_habitat", "landuse", parmi("class", ["residential", "suburb", "neighbourhood"]), p.bati,
      { paint: { "fill-opacity": ["interpolate", ["linear"], ["zoom"], 9, 0.9, 14, 0.4, 16, 0] } }),
    surface("emprise_activite", "landuse", parmi("class", ["industrial", "commercial", "retail", "garages", "railway", "quarry"]), p.industrie, { minzoom: 10 }),
    surface("emprise_cimetiere", "landuse", classe("cemetery"), p.cimetiere, { minzoom: 11 }),
    surface("emprise_hopital", "landuse", classe("hospital"), p.hopital, { minzoom: 11 }),
    surface("emprise_ecole", "landuse", parmi("class", ["school", "university", "college", "kindergarten"]), p.ecole, { minzoom: 11 }),
    surface("emprise_sport", "landuse", parmi("class", ["pitch", "stadium", "playground", "track"]), p.sport, { minzoom: 12 }),
    // Parcs nationaux et réserves : un voile vert et un liseré, sans cacher ce qu'il y a dessous.
    surface("reserve", "park", polygone, p.reserve, { paint: { "fill-opacity": 0.07 } }),
    {
      id: "reserve_bord", type: "line", source: "openmaptiles", "source-layer": "park", filter: polygone, minzoom: 8,
      paint: { "line-color": p.reserve, "line-opacity": 0.45, "line-width": selonZoom([[8, 0.6], [14, 1.6]], 1.2), "line-dasharray": [3, 2] },
    },

    ombrage(p),

    // --- Eau --------------------------------------------------------------------------------------------------
    {
      id: "eau_cours", type: "line", source: "openmaptiles", "source-layer": "waterway",
      filter: ["!=", ["get", "brunnel"], "tunnel"], layout: LIGNE,
      paint: {
        "line-color": p.eau,
        "line-width": ["interpolate", ["exponential", 1.4], ["zoom"],
          8, ["match", ["get", "class"], "river", 0.7, 0.2], 13, ["match", ["get", "class"], "river", 2.2, 0.8],
          17, ["match", ["get", "class"], "river", 6, 2.4], 20, ["match", ["get", "class"], "river", 14, 6]],
      },
    },
    surface("eau", "water", ["!=", ["get", "brunnel"], "tunnel"], p.eau),

    // --- Aérodromes -------------------------------------------------------------------------------------------
    surface("aeroport", "aeroway", polygone, p.aeroport, { minzoom: 11 }),
    {
      id: "aeroport_piste", type: "line", source: "openmaptiles", "source-layer": "aeroway", minzoom: 11,
      filter: ["all", ["match", ["geometry-type"], ["LineString", "MultiLineString"], true, false], parmi("class", ["runway", "taxiway"])],
      paint: { "line-color": p.pisteAvion, "line-width": ["interpolate", ["exponential", 1.5], ["zoom"],
        11, ["match", ["get", "class"], "runway", 2, 0.6], 16, ["match", ["get", "class"], "runway", 28, 6]] },
    },

    // --- Bâtiments --------------------------------------------------------------------------------------------
    {
      id: "batiment", type: "fill", source: "openmaptiles", "source-layer": "building", minzoom: 13.5,
      paint: {
        "fill-color": p.batiment, "fill-outline-color": p.batimentBord,
        "fill-opacity": ["interpolate", ["linear"], ["zoom"], 13.5, 0, 14.5, 1],
      },
    },

    ...routesEnLigne(p),

    // --- Limites administratives ------------------------------------------------------------------------------
    {
      id: "limite_region", type: "line", source: "openmaptiles", "source-layer": "boundary", minzoom: 6,
      filter: ["all", [">=", ["coalesce", ["get", "admin_level"], 0], 3], ["<=", ["coalesce", ["get", "admin_level"], 0], 6], ["!=", ["get", "maritime"], 1]],
      paint: { "line-color": p.frontiere, "line-opacity": 0.55, "line-width": selonZoom([[6, 0.5], [12, 1.1]], 1.2), "line-dasharray": [3, 2] },
    },
    {
      id: "limite_pays", type: "line", source: "openmaptiles", "source-layer": "boundary",
      filter: ["all", ["==", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
      layout: LIGNE,
      paint: { "line-color": p.frontiere, "line-width": selonZoom([[3, 0.8], [8, 1.6], [12, 2.4]], 1.2) },
    },

    ...nomsEnLigne(p),
  ];
  return {
    version: 8,
    name: `VeloGo (${nom})`,
    glyphs: `${OPENFREEMAP}/fonts/{fontstack}/{range}.pbf`,
    sources: {
      openmaptiles: { type: "vector", url: `${OPENFREEMAP}/planet`, attribution: "© OpenStreetMap · © OpenMapTiles" },
      // Le relief s'arrête au zoom 11 (une quinzaine de mètres par point) : bien assez pour une ombre douce, et une
      // seule tuile d'une centaine de kilo-octets couvre alors quatorze kilomètres de carte.
      relief: { type: "raster-dem", tiles: [MAPTERHORN], encoding: "terrarium", tileSize: 512, minzoom: 0, maxzoom: 11, attribution: "Mapterhorn" },
    },
    layers: couches,
  };
}

// ===============================================================================================================
// Carte hors ligne : schéma Protomaps + surcouche vélo
// ===============================================================================================================

/** La palette de VeloGo, dite dans les mots du fond Protomaps. */
function saveurProtomaps(p) {
  return {
    ...namedFlavor(p.sprites),
    background: p.terre, earth: p.terre,
    park_a: p.parc, park_b: p.parc, wood_a: p.foret, wood_b: p.foret, scrub_a: p.broussaille, scrub_b: p.broussaille,
    hospital: p.hopital, industrial: p.industrie, school: p.ecole, pedestrian: p.pieton,
    glacier: p.glace, sand: p.sable, beach: p.sable, aerodrome: p.aeroport, runway: p.pisteAvion,
    water: p.eau, zoo: p.parc, military: p.industrie, pier: p.pieton, buildings: p.batiment,
    tunnel_other_casing: p.tunnelBord, tunnel_minor_casing: p.tunnelBord, tunnel_link_casing: p.tunnelBord,
    tunnel_major_casing: p.tunnelBord, tunnel_highway_casing: p.tunnelBord,
    tunnel_other: p.tunnel, tunnel_minor: p.tunnel, tunnel_link: p.tunnel, tunnel_major: p.tunnel, tunnel_highway: p.tunnel,
    minor_service_casing: p.routeBord, minor_casing: p.routeBord, link_casing: p.routeBord,
    major_casing_late: p.routeBord, major_casing_early: p.routeBord,
    highway_casing_late: p.voieRapideBord, highway_casing_early: p.voieRapideBord,
    other: p.sentier, minor_service: p.desserte, minor_a: p.route, minor_b: p.route, link: p.route, major: p.route,
    highway: p.voieRapide, railway: p.rail, boundaries: p.frontiere,
    bridges_other_casing: p.routeBord, bridges_minor_casing: p.routeBord, bridges_link_casing: p.routeBord,
    bridges_major_casing: p.routeBord, bridges_highway_casing: p.voieRapideBord,
    bridges_other: p.sentier, bridges_minor: p.route, bridges_link: p.route, bridges_major: p.route, bridges_highway: p.voieRapide,
    roads_label_minor: p.texteRoute, roads_label_minor_halo: p.halo, roads_label_major: p.texteRoute, roads_label_major_halo: p.halo,
    ocean_label: p.eauTexte, subplace_label: p.texteDoux, subplace_label_halo: p.halo, city_label: p.texte, city_label_halo: p.halo,
    state_label: p.texteDoux, state_label_halo: p.halo, country_label: p.texteDoux,
    address_label: p.texteRoute, address_label_halo: p.halo,
    landcover: {
      grassland: p.herbe, barren: p.roche, urban_area: p.bati, farmland: p.culture, glacier: p.glace, scrub: p.broussaille, forest: p.foret,
    },
  };
}

/**
 * Retouches du fond Protomaps, couche par couche : les grands axes en teinte chaude (le fond les dessine comme les
 * autres routes), les chemins en pointillé fin, le bâti net, l'eau des rivières de la couleur des lacs.
 */
function retoucher(couche, p) {
  const c = structuredClone(couche);
  const grandAxe = (oui, non) => ["match", ["get", "kind_detail"], ["primary", "primary_link", "trunk", "trunk_link"], oui, non];
  switch (c.id) {
    case "roads_major": case "roads_bridges_major":
      c.paint["line-color"] = grandAxe(p.axe, p.route);
      break;
    case "roads_major_casing_late": case "roads_major_casing_early": case "roads_bridges_major_casing":
      c.paint["line-color"] = grandAxe(p.axeBord, p.routeBord);
      break;
    case "roads_other": case "roads_bridges_other":
      // Chemins, sentiers, allées : la surcouche vélo dit leur revêtement ; ici, juste leur présence.
      c.paint = { "line-color": p.sentier, "line-opacity": 0.75, "line-dasharray": [2, 1.6],
        "line-width": selonZoom([[13, 0.5], [15, 0.9], [17, 1.6], [20, 4]]) };
      c.layout = BOUT_DROIT;
      break;
    case "roads_rail":
      c.paint = { "line-color": p.rail, "line-width": selonZoom([[8, 0.4], [13, 0.9], [16, 1.8], [20, 4]]) };
      break;
    case "buildings":
      c.minzoom = 13.5;
      c.paint = { "fill-color": p.batiment, "fill-outline-color": p.batimentBord,
        "fill-opacity": ["interpolate", ["linear"], ["zoom"], 13.5, 0, 14.5, 1] };
      break;
    case "boundaries":
      c.paint = { ...c.paint, "line-color": p.frontiere, "line-opacity": 0.55, "line-width": 0.9 };
      break;
    case "pois":
      // Moins de pictogrammes : ils n'apparaissent qu'un cran de zoom plus tard que dans le fond d'origine.
      c.filter = ["all", c.filter[1], [">=", ["zoom"], ["+", ["get", "min_zoom"], 1]]];
      c.paint = { ...c.paint, "text-halo-color": p.halo, "text-halo-width": 1.2 };
      break;
    default:
  }
  if (c.type === "symbol" && c.paint && c.paint["text-halo-color"]) c.paint["text-halo-width"] = Math.max(c.paint["text-halo-width"] ?? 0, 1.4);
  return c;
}

/** Largeur de trait de la surcouche vélo, qui grossit avec le zoom. */
const largeur = (k) => selonZoom([[8, 0.6 * k], [12, 1.2 * k], [14, 1.9 * k], [17, 3.6 * k], [20, 8 * k]]);
const estPiste = ["match", ["get", "type"], ["piste", "piste_chaussee"], true, false];
const revetementDur = ["match", ["coalesce", ["get", "revetement"], "revetu"], ["revetu"], true, false];

/**
 * Itinéraires balisés (véloroutes, boucles locales, circuits VTT) : une bande pâle glissée SOUS les routes, qui
 * dépasse de chaque côté comme un surlignage. Couleur pleine et non transparente : plusieurs itinéraires passent
 * souvent par la même rue, et des bandes transparentes empilées y feraient un trait épais et sombre.
 */
function itinerairesVelo(p) {
  const itineraire = (id, reseaux, couleur, k, minzoom, extra = {}) => ({
    id: `velo_itineraire_${id}`, type: "line", source: "velo", "source-layer": "itineraires", minzoom,
    filter: ["match", ["get", "reseau"], reseaux, true, false],
    layout: LIGNE,
    paint: { "line-color": couleur, "line-width": largeur(k), ...extra },
  });
  return [
    itineraire("local", ["local"], p.haloLocal, 3.4, 12),
    itineraire("vtt", ["vtt"], p.haloVtt, 3.0, 12.5, { "line-dasharray": [2, 1.2] }),
    itineraire("regional", ["regional"], p.haloRegional, 4.0, 8),
    itineraire("national", ["national", "europeen"], p.haloNational, 4.6, 6),
  ];
}

function coucheVelo(p) {
  const voie = (id, filter, paint, minzoom = 11, layout = LIGNE) => ({
    id: `velo_${id}`, type: "line", source: "velo", "source-layer": "voies", minzoom, filter, layout, paint,
  });
  const point = (id, types, couleur, minzoom, rayon = 4) => ({
    id: `velo_point_${id}`, type: "circle", source: "velo", "source-layer": "points", minzoom,
    filter: ["match", ["get", "type"], types, true, false],
    paint: {
      "circle-color": couleur, "circle-radius": selonZoom([[minzoom, rayon * 0.6], [minzoom + 3, rayon]], 1.2),
      "circle-stroke-color": p.halo, "circle-stroke-width": 1.2,
    },
  });
  return [
    // --- Revêtements : ce qui n'est pas du bitume lisse ---------------------------------------------------
    voie("route_non_revetue", ["all", ["!", ["has", "chemin"]], ["!", revetementDur], ["!", estPiste], ["!=", ["get", "revetement"], "pave"]],
      { "line-color": p.nonRevetu, "line-width": largeur(0.6), "line-dasharray": [3, 2] }, 12, BOUT_DROIT),
    voie("paves", ["all", ["==", ["get", "revetement"], "pave"], ["!", estPiste]],
      { "line-color": p.pave, "line-width": largeur(0.6), "line-dasharray": [0.6, 1.6] }, 14),
    voie("chemin_meuble", ["all", ["has", "chemin"], ["==", ["get", "revetement"], "meuble"], ["!", estPiste]],
      { "line-color": p.nonRevetu, "line-width": largeur(0.55), "line-dasharray": [1, 1.8] }, 12.5, BOUT_DROIT),
    voie("chemin_stabilise", ["all", ["has", "chemin"], ["==", ["get", "revetement"], "stabilise"], ["!", estPiste]],
      { "line-color": p.nonRevetu, "line-width": largeur(0.6), "line-dasharray": [4, 1.5] }, 12.5, BOUT_DROIT),
    // Un chemin revêtu se roule comme une petite rue : il en a l'allure, en plus étroit.
    voie("chemin_revetu_bord", ["all", ["has", "chemin"], ["==", ["get", "revetement"], "revetu"], ["!", estPiste]],
      { "line-color": p.routeBord, "line-width": largeur(1.35) }, 13.5),
    voie("chemin_revetu", ["all", ["has", "chemin"], ["==", ["get", "revetement"], "revetu"], ["!", estPiste]],
      { "line-color": p.route, "line-width": largeur(0.85) }, 13.5),

    // --- Voies interdites aux vélos ------------------------------------------------------------------------
    voie("interdit", ["has", "interdit"],
      { "line-color": p.interdit, "line-width": largeur(0.5), "line-dasharray": [1.2, 1.8], "line-opacity": 0.7 }, 13, BOUT_DROIT),

    // --- Aménagements cyclables ----------------------------------------------------------------------------
    // Bandes cyclables dès qu'on distingue les rues ; voies partagées et double-sens cyclables, seulement de près
    // (en ville presque toutes les rues en ont : de loin, elles couvriraient la carte).
    voie("partage", ["match", ["get", "type"], ["partage", "contresens"], true, false],
      { "line-color": p.piste, "line-width": largeur(0.5), "line-dasharray": [1.2, 2.4], "line-opacity": 0.7 }, 15, BOUT_DROIT),
    voie("bande", ["==", ["get", "type"], "bande"],
      { "line-color": p.piste, "line-width": largeur(0.6), "line-dasharray": [2.4, 1.8], "line-opacity": 0.9 }, 13, BOUT_DROIT),
    voie("piste_lisere", estPiste,
      { "line-color": p.lisere, "line-width": largeur(1.9), "line-opacity": 0.85 }, 10),
    voie("piste_non_revetue", ["all", estPiste, ["!", revetementDur]],
      { "line-color": p.piste, "line-width": largeur(1), "line-dasharray": [3.5, 1.4] }, 9, BOUT_DROIT),
    voie("piste", ["all", estPiste, revetementDur],
      { "line-color": p.piste, "line-width": largeur(1) }, 9),

    // --- Points utiles --------------------------------------------------------------------------------------
    point("stationnement", ["stationnement"], p.stationnement, 16.5, 2.8),
    point("toilettes", ["toilettes"], p.toilettes, 15.5, 3.5),
    point("abri", ["abri"], p.abri, 14.5, 3.5),
    point("point_de_vue", ["point_de_vue"], p.pointDeVue, 14, 3.5),
    point("camping", ["camping"], p.camping, 13.5, 3.5),
    point("magasin", ["magasin", "location"], p.magasin, 14),
    point("reparation", ["reparation"], p.reparation, 14),
    point("eau", ["eau"], p.pointEau, 14, 4.2),
  ];
}

function etiquettesVelo(p) {
  return [
    {
      id: "velo_itineraire_ref", type: "symbol", source: "velo", "source-layer": "itineraires", minzoom: 10,
      filter: ["all", ["has", "ref"], ["match", ["get", "reseau"], ["national", "europeen", "regional"], true, false]],
      layout: {
        "symbol-placement": "line", "symbol-spacing": 350, "text-field": ["get", "ref"],
        "text-font": ["Noto Sans Medium"], "text-size": 11, "text-letter-spacing": 0.05,
      },
      paint: { "text-color": p.national, "text-halo-color": p.halo, "text-halo-width": 1.6 },
    },
    {
      id: "velo_col", type: "symbol", source: "velo", "source-layer": "points", minzoom: 10,
      filter: ["==", ["get", "type"], "col"],
      layout: {
        "icon-image": "peak", "icon-size": 0.9,
        "text-field": ["case", ["has", "ele"], ["concat", ["coalesce", ["get", "nom"], "Col"], "\n", ["get", "ele"], " m"], ["coalesce", ["get", "nom"], "Col"]],
        "text-font": ["Noto Sans Medium"], "text-size": 12, "text-offset": [0, 0.9], "text-anchor": "top", "text-max-width": 9,
      },
      paint: { "text-color": p.texte, "text-halo-color": p.halo, "text-halo-width": 1.6 },
    },
    {
      id: "velo_point_nom", type: "symbol", source: "velo", "source-layer": "points", minzoom: 16,
      filter: ["match", ["get", "type"], ["eau", "reparation", "magasin", "camping", "point_de_vue", "abri"], true, false],
      layout: {
        "text-field": ["match", ["get", "type"],
          "eau", "Eau", "reparation", "Atelier vélo", "magasin", ["coalesce", ["get", "nom"], "Magasin de vélos"],
          "camping", ["coalesce", ["get", "nom"], "Camping"], "point_de_vue", ["coalesce", ["get", "nom"], "Point de vue"],
          "abri", "Abri", ""],
        "text-font": ["Noto Sans Regular"], "text-size": 11, "text-offset": [0, 0.8], "text-anchor": "top", "text-optional": true,
      },
      paint: { "text-color": p.texte, "text-halo-color": p.halo, "text-halo-width": 1.4 },
    },
  ];
}

function styleHorsLigne(nom, p) {
  const fond = layers("fond", saveurProtomaps(p), { lang: "fr" }).map((couche) => retoucher(couche, p));
  // L'ombrage se glisse sous l'eau (un lac reste plat), les itinéraires balisés sous les routes, et le reste de
  // la surcouche vélo sous les noms.
  const iEau = fond.findIndex((l) => l.id === "water");
  const iRoutes = fond.findIndex((l) => l.id.startsWith("roads_tunnels"));
  const iNoms = fond.findIndex((l) => l.type === "symbol");
  if (!(iEau > 0 && iRoutes > iEau && iNoms > iRoutes)) throw new Error("le fond Protomaps a changé d'ordre : revoir styleHorsLigne");
  const couches = [
    ...fond.slice(0, iEau), ombrage(p), ...fond.slice(iEau, iRoutes), ...itinerairesVelo(p), ...fond.slice(iRoutes, iNoms),
    ...coucheVelo(p), ...fond.slice(iNoms), ...etiquettesVelo(p),
  ];
  return {
    version: 8,
    name: `VeloGo hors ligne (${nom})`,
    glyphs: `${HOTE}/polices/{fontstack}/{range}.pbf`,
    sprite: `${HOTE}/sprites/${p.sprites}`,
    sources: {
      // « volatile » : ces tuiles sont déjà sur le téléphone, inutile que la carte en garde une copie dans son cache.
      fond: { type: "vector", tiles: [`${HOTE}/fond/{z}/{x}/{y}.mvt`], minzoom: 0, maxzoom: 15, volatile: true, attribution: "© OpenStreetMap · Protomaps" },
      velo: { type: "vector", tiles: [`${HOTE}/velo/{z}/{x}/{y}.mvt`], minzoom: 6, maxzoom: 14, volatile: true },
      relief: { type: "raster-dem", tiles: [`${HOTE}/relief/{z}/{x}/{y}.webp`], encoding: "terrarium", tileSize: 512, minzoom: 0, maxzoom: 12, volatile: true },
    },
    layers: couches,
  };
}

// ---------------------------------------------------------------------------------------------------------------

export const STYLES = { "en-ligne": styleEnLigne, "hors-ligne": styleHorsLigne };

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  for (const [dossier, fabrique] of Object.entries(STYLES)) {
    mkdirSync(join(ASSETS, dossier), { recursive: true });
    for (const [nom, palette] of Object.entries(THEMES)) {
      const fichier = join(ASSETS, dossier, `style-${nom}.json`);
      const contenu = fabrique(nom, palette);
      const noms = contenu.layers.map((couche) => couche.id);
      const doublons = noms.filter((id, i) => noms.indexOf(id) !== i);
      if (doublons.length) throw new Error(`${fichier} : couches en double (${doublons.join(", ")})`);
      writeFileSync(fichier, JSON.stringify(contenu));
      console.log(`${fichier} : ${contenu.layers.length} couches`);
    }
  }
}
