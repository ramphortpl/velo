// Contrôle des styles fabriqués par generer.mjs contre la spécification de MapLibre :
//   1. validité, avec le validateur officiel ;
//   2. propriétés qui dépendent de l'objet dessiné alors que MapLibre Native (Android) ne le permet pas pour elles,
//      d'après le tableau « sdk-support » de la spécification elle-même. L'aperçu (MapLibre GL JS, dans un
//      navigateur) accepte davantage que le téléphone : sans ce contrôle, l'écart ne se verrait que sur l'appareil.
//
//   cd cartes/style && npm install && npm run controler
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { validateStyleMin } from "@maplibre/maplibre-gl-style-spec";

const require = createRequire(import.meta.url);
// Le fichier de référence complet (celui que le paquet publie sans ses indications de prise en charge).
const reference = JSON.parse(readFileSync(require.resolve("@maplibre/maplibre-gl-style-spec/src/reference/v8.json"), "utf8"));
const ASSETS = join(dirname(fileURLToPath(import.meta.url)), "../../app/src/main/assets");

/** Vrai si la valeur est une expression qui lit un attribut de l'objet dessiné. */
const litLObjet = (valeur) => /\["(get|has|geometry-type|id|properties|feature-state)"/.test(JSON.stringify(valeur));
/** Une version (« 5.2.0 ») dit que c'est pris en charge ; un lien vers un ticket, ou rien, que ça ne l'est pas. */
const prisEnCharge = (indication) => typeof indication === "string" && /^\d+\.\d+/.test(indication);

let problemes = 0;
const signaler = (message) => { problemes++; console.log(message); };

for (const famille of ["en-ligne", "hors-ligne"]) {
  for (const theme of ["clair", "sombre"]) {
    const nom = `${famille}/style-${theme}.json`;
    const style = JSON.parse(readFileSync(join(ASSETS, nom), "utf8"));
    for (const erreur of validateStyleMin(style)) signaler(`${nom} : ${erreur.message}`);
    for (const couche of style.layers) {
      for (const groupe of ["paint", "layout"]) {
        const definitions = reference[`${groupe}_${couche.type}`] ?? {};
        for (const [propriete, valeur] of Object.entries(couche[groupe] ?? {})) {
          const definition = definitions[propriete];
          if (!definition) { signaler(`${nom}, couche ${couche.id} : propriété inconnue « ${propriete} »`); continue; }
          const support = definition["sdk-support"] ?? {};
          if (!prisEnCharge(support["basic functionality"]?.android)) {
            signaler(`${nom}, couche ${couche.id} : « ${propriete} » n'existe pas dans MapLibre Native (Android)`);
          } else if (litLObjet(valeur) && !prisEnCharge(support["data-driven styling"]?.android)) {
            signaler(`${nom}, couche ${couche.id} : « ${propriete} » ne peut pas dépendre de l'objet dessiné sur Android`);
          }
        }
      }
    }
    console.log(`${nom} : ${style.layers.length} couches contrôlées`);
  }
}
if (problemes) {
  console.log(`${problemes} problème(s)`);
  process.exit(1);
}
console.log("Aucun problème.");
