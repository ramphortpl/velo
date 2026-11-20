// Fabrique le style de la carte vélo hors ligne (clair et sombre) :
//   fond Protomaps + ombrage du relief + surcouche vélo (cartes/SurcoucheVelo.java).
//
//   cd cartes/style && npm install && npm run generer
//
// Les deux fichiers produits sont versionnés dans app/src/main/assets/hors-ligne/ : la compilation de l'appli
// n'a donc pas besoin de Node. À relancer quand on retouche les couleurs ou les couches ci-dessous.
import { layers, namedFlavor } from "@protomaps/basemaps";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Adresse fictive : dans l'appli, ces requêtes sont interceptées et servies depuis les packs du téléphone.
const HOTE = "https://hors-ligne.velogo.invalid";
const SORTIE = join(dirname(fileURLToPath(import.meta.url)), "../../app/src/main/assets/hors-ligne");

const PALETTES = {
  clair: {
    flavor: "light",
    piste: "#00875A", lisere: "#FFFFFF",
    national: "#8E24AA", regional: "#5E35B1", local: "#1E88E5", vtt: "#EF6C00",
    cheminRevetu: "#5F6B63", nonRevetu: "#8D6E63", pave: "#607D8B", interdit: "#C62828",
    eau: "#0288D1", reparation: "#EF6C00", magasin: "#6A1B9A", abri: "#6D4C41", toilettes: "#546E7A",
    camping: "#2E7D32", pointDeVue: "#C2185B", stationnement: "#455A64",
    texte: "#1B2B24", halo: "#FFFFFF",
    ombre: "#4B5A52", lumiere: "#FFFFFF", relief: 0.38,
  },
  sombre: {
    flavor: "dark",
    piste: "#3DDC97", lisere: "#0B131A",
    national: "#CE93D8", regional: "#B39DDB", local: "#64B5F6", vtt: "#FFB74D",
    cheminRevetu: "#A7B4AC", nonRevetu: "#BCAAA4", pave: "#B0BEC5", interdit: "#EF9A9A",
    eau: "#4FC3F7", reparation: "#FFB74D", magasin: "#CE93D8", abri: "#BCAAA4", toilettes: "#B0BEC5",
    camping: "#81C784", pointDeVue: "#F48FB1", stationnement: "#90A4AE",
    texte: "#E8EEF0", halo: "#0B131A",
    ombre: "#000000", lumiere: "#5C6B78", relief: 0.5,
  },
};

// Retouches du fond Protomaps : routes mieux dessinées, grands axes (trafic) en jaune et orangé, chemins visibles
// en forêt, noms plus contrastés. Le fond d'origine est volontairement pâle ; à vélo on veut lire le réseau.
const FOND = {
  light: {
    minor_service_casing: "#C4BFB6", minor_casing: "#C4BFB6", link_casing: "#C4BFB6",
    major_casing_late: "#C9B98A", major_casing_early: "#C9B98A", highway_casing_late: "#D2A66A", highway_casing_early: "#D2A66A",
    bridges_minor_casing: "#B5B0A7", bridges_link_casing: "#B5B0A7", bridges_major_casing: "#BDAA78", bridges_highway_casing: "#C99A5B", bridges_other_casing: "#B5B0A7",
    other: "#B8B0A3", bridges_other: "#B8B0A3", minor_service: "#F4F2EE", minor_a: "#FAF9F6", minor_b: "#FFFFFF",
    major: "#FFF3C4", bridges_major: "#FFF3C4", link: "#FFF3C4", bridges_link: "#FFF3C4", highway: "#FFD9A0", bridges_highway: "#FFD9A0",
    earth: "#ECE9E3", buildings: "#D8D3CA", water: "#8FD0EC", railway: "#8F9A9D",
    roads_label_minor: "#6B6266", roads_label_major: "#5E5558", city_label: "#33383A", subplace_label: "#6E6E6E", address_label: "#8A8286",
  },
  dark: {
    other: "#4E4B47", bridges_other: "#4E4B47", minor_service: "#3A3A3A", minor_a: "#454545", minor_b: "#454545",
    major: "#5C5545", bridges_major: "#5C5545", link: "#5C5545", bridges_link: "#5C5545", highway: "#6E5B3C", bridges_highway: "#6E5B3C",
    earth: "#1B1F22", buildings: "#15181A", water: "#22384A", railway: "#111111",
    roads_label_minor: "#8C8C8C", roads_label_major: "#A0A0A0", city_label: "#C9CED2", subplace_label: "#8E8E8E", address_label: "#6E6E6E",
  },
};

/** Largeur de trait qui grossit avec le zoom. */
const largeur = (k) => ["interpolate", ["exponential", 1.5], ["zoom"], 8, 0.7 * k, 12, 1.4 * k, 14, 2.2 * k, 17, 4.5 * k, 20, 10 * k];
const estPiste = ["match", ["get", "type"], ["piste", "piste_chaussee"], true, false];
const estBande = ["match", ["get", "type"], ["bande", "partage", "contresens"], true, false];
const revetementDur = ["match", ["coalesce", ["get", "revetement"], "revetu"], ["revetu"], true, false];
const ligne = { "line-cap": "round", "line-join": "round" };

function coucheVelo(p) {
  const itineraire = (id, reseaux, couleur, k, minzoom, extra = {}, opacite = 0.45) => ({
    id: `velo_itineraire_${id}`, type: "line", source: "velo", "source-layer": "itineraires", minzoom,
    filter: ["match", ["get", "reseau"], reseaux, true, false],
    layout: ligne,
    paint: { "line-color": couleur, "line-opacity": opacite, "line-width": largeur(k), ...extra },
  });
  const voie = (id, filter, paint, minzoom = 11, layout = ligne) => ({
    id: `velo_${id}`, type: "line", source: "velo", "source-layer": "voies", minzoom, filter, layout, paint,
  });
  const point = (id, types, couleur, minzoom, rayon = 4.5) => ({
    id: `velo_point_${id}`, type: "circle", source: "velo", "source-layer": "points", minzoom,
    filter: ["match", ["get", "type"], types, true, false],
    paint: {
      "circle-color": couleur, "circle-radius": ["interpolate", ["linear"], ["zoom"], minzoom, rayon * 0.55, minzoom + 3, rayon],
      "circle-stroke-color": p.halo, "circle-stroke-width": 1.2,
    },
  });
  return [
    // --- Itinéraires balisés : un large halo de couleur sous la voie, lisible sans masquer la route ---------
    itineraire("local", ["local"], p.local, 2.2, 11, {}, 0.3),
    itineraire("vtt", ["vtt"], p.vtt, 2.0, 12, { "line-dasharray": [2, 1.5] }, 0.4),
    itineraire("regional", ["regional"], p.regional, 3.0, 8),
    itineraire("national", ["national", "europeen"], p.national, 3.4, 6),

    // --- Revêtements : ce qui n'est pas du bitume lisse ---------------------------------------------------
    voie("route_non_revetue", ["all", ["!", ["has", "chemin"]], ["!", revetementDur], ["!", estPiste], ["!=", ["get", "revetement"], "pave"]],
      { "line-color": p.nonRevetu, "line-width": largeur(0.75), "line-dasharray": [3, 2] }, 11, { "line-cap": "butt", "line-join": "round" }),
    voie("paves", ["all", ["==", ["get", "revetement"], "pave"], ["!", estPiste]],
      { "line-color": p.pave, "line-width": largeur(0.75), "line-dasharray": [0.6, 1.6] }, 13),
    voie("chemin_meuble", ["all", ["has", "chemin"], ["==", ["get", "revetement"], "meuble"], ["!", estPiste]],
      { "line-color": p.nonRevetu, "line-width": largeur(0.7), "line-dasharray": [1, 1.8] }, 12, { "line-cap": "butt", "line-join": "round" }),
    voie("chemin_stabilise", ["all", ["has", "chemin"], ["==", ["get", "revetement"], "stabilise"], ["!", estPiste]],
      { "line-color": p.nonRevetu, "line-width": largeur(0.8), "line-dasharray": [4, 1.5] }, 12, { "line-cap": "butt", "line-join": "round" }),
    voie("chemin_revetu", ["all", ["has", "chemin"], ["==", ["get", "revetement"], "revetu"], ["!", estPiste]],
      { "line-color": p.cheminRevetu, "line-width": largeur(0.75) }, 12),

    // --- Voies interdites aux vélos ------------------------------------------------------------------------
    voie("interdit", ["has", "interdit"],
      { "line-color": p.interdit, "line-width": largeur(0.6), "line-dasharray": [1.2, 1.8], "line-opacity": 0.85 }, 12, { "line-cap": "butt", "line-join": "round" }),

    // --- Aménagements cyclables ----------------------------------------------------------------------------
    voie("bande", estBande,
      { "line-color": p.piste, "line-width": largeur(0.8), "line-dasharray": [2.2, 1.6] }, 12, { "line-cap": "butt", "line-join": "round" }),
    voie("piste_lisere", estPiste,
      { "line-color": p.lisere, "line-width": largeur(1.9), "line-opacity": 0.9 }, 9),
    voie("piste_non_revetue", ["all", estPiste, ["!", revetementDur]],
      { "line-color": p.piste, "line-width": largeur(1.1), "line-dasharray": [3.5, 1.4] }, 9, { "line-cap": "butt", "line-join": "round" }),
    voie("piste", ["all", estPiste, revetementDur],
      { "line-color": p.piste, "line-width": largeur(1.1) }, 9),

    // --- Points utiles --------------------------------------------------------------------------------------
    point("stationnement", ["stationnement"], p.stationnement, 16, 3),
    point("toilettes", ["toilettes"], p.toilettes, 15),
    point("abri", ["abri"], p.abri, 15),
    point("point_de_vue", ["point_de_vue"], p.pointDeVue, 14),
    point("camping", ["camping"], p.camping, 13),
    point("magasin", ["magasin", "location"], p.magasin, 14),
    point("reparation", ["reparation"], p.reparation, 13),
    point("eau", ["eau"], p.eau, 14, 5),
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
      id: "velo_col", type: "symbol", source: "velo", "source-layer": "points", minzoom: 9,
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

function style(nom, p) {
  const fond = layers("fond", { ...namedFlavor(p.flavor), ...FOND[p.flavor] }, { lang: "fr" });
  const ombrage = {
    id: "relief_ombrage", type: "hillshade", source: "relief",
    paint: {
      "hillshade-exaggeration": p.relief, "hillshade-shadow-color": p.ombre,
      "hillshade-highlight-color": p.lumiere, "hillshade-accent-color": p.ombre,
    },
  };
  // L'ombrage se glisse sous l'eau (un lac reste plat) ; la surcouche vélo passe sous les noms.
  const iEau = fond.findIndex((l) => l.id === "water");
  const iNoms = fond.findIndex((l) => l.type === "symbol");
  const couches = [
    ...fond.slice(0, iEau), ombrage, ...fond.slice(iEau, iNoms),
    ...coucheVelo(p), ...fond.slice(iNoms), ...etiquettesVelo(p),
  ];
  return {
    version: 8,
    name: `VeloGo hors ligne (${nom})`,
    glyphs: `${HOTE}/polices/{fontstack}/{range}.pbf`,
    sprite: `${HOTE}/sprites/${p.flavor}`,
    sources: {
      fond: { type: "vector", tiles: [`${HOTE}/fond/{z}/{x}/{y}.mvt`], minzoom: 0, maxzoom: 15, attribution: "© OpenStreetMap · Protomaps" },
      velo: { type: "vector", tiles: [`${HOTE}/velo/{z}/{x}/{y}.mvt`], minzoom: 6, maxzoom: 14 },
      relief: { type: "raster-dem", tiles: [`${HOTE}/relief/{z}/{x}/{y}.webp`], encoding: "terrarium", tileSize: 512, minzoom: 0, maxzoom: 12 },
    },
    layers: couches,
  };
}

mkdirSync(SORTIE, { recursive: true });
for (const [nom, palette] of Object.entries(PALETTES)) {
  const fichier = join(SORTIE, `style-${nom}.json`);
  const contenu = style(nom, palette);
  writeFileSync(fichier, JSON.stringify(contenu));
  console.log(`${fichier} : ${contenu.layers.length} couches`);
}
