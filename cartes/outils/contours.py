#!/usr/bin/env python3
"""Fabrique les contours des 13 régions métropolitaines utilisés pour découper les packs de cartes.

Source : tracés IGN Admin Express (Licence Ouverte), version simplifiée du dépôt gregoiredavid/france-geojson.
Chaque contour est élargi d'une douzaine de kilomètres pour que les cartes de deux régions voisines se recouvrent,
puis simplifié. Le script écrit cartes/contours/<region>.geojson et cartes/regions.json.

Usage : python3 cartes/outils/contours.py regions-version-simplifiee.geojson
(nécessite le module shapely ; à relancer seulement si le découpage régional change)
"""
import json
import math
import os
import sys
import unicodedata
import warnings

from shapely.geometry import box, mapping, shape
from shapely.ops import transform

MARGE_KM = 12.0
RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Outre-mer : hors périmètre (France métropolitaine et Corse uniquement).
EXCLUES = {"01", "02", "03", "04", "06"}


def slug(nom):
    sans_accents = unicodedata.normalize("NFD", nom).encode("ascii", "ignore").decode()
    return "".join(c if c.isalnum() else "-" for c in sans_accents.lower()).strip("-").replace("--", "-")


def dalles_brouter(geometrie):
    """Dalles BRouter de 5° x 5° (nommées par leur coin sud-ouest) que touche la région."""
    ouest, sud, est, nord = geometrie.bounds
    dalles = []
    for lon in range(int(math.floor(ouest / 5.0)) * 5, int(math.floor(est / 5.0)) * 5 + 1, 5):
        for lat in range(int(math.floor(sud / 5.0)) * 5, int(math.floor(nord / 5.0)) * 5 + 1, 5):
            if geometrie.intersects(box(lon, lat, lon + 5, lat + 5)):
                dalles.append(("E" if lon >= 0 else "W") + str(abs(lon)) + "_" + ("N" if lat >= 0 else "S") + str(abs(lat)))
    return sorted(dalles)


def main():
    warnings.simplefilter("ignore", DeprecationWarning)
    source = json.load(open(sys.argv[1], encoding="utf-8"))
    os.makedirs(os.path.join(RACINE, "contours"), exist_ok=True)
    regions = []
    for feature in source["features"]:
        code = feature["properties"]["code"]
        if code in EXCLUES:
            continue
        nom = feature["properties"]["nom"]
        identifiant = slug(nom)
        geometrie = shape(feature["geometry"])
        # Élargissement en « kilomètres » : on aplatit localement la longitude avant de tamponner.
        k = math.cos(math.radians(geometrie.centroid.y))
        aplatie = transform(lambda x, y: (x * k, y), geometrie)
        elargie = aplatie.buffer(MARGE_KM / 111.32).simplify(0.01)
        contour = transform(lambda x, y: (round(x / k, 4), round(y, 4)), elargie)
        with open(os.path.join(RACINE, "contours", identifiant + ".geojson"), "w", encoding="utf-8") as f:
            json.dump({"type": "Feature", "properties": {"id": identifiant, "nom": nom}, "geometry": mapping(contour)}, f, separators=(",", ":"))
        ouest, sud, est, nord = geometrie.bounds
        regions.append({
            "id": identifiant,
            "nom": nom,
            "code": code,
            "emprise": [round(ouest, 3), round(sud, 3), round(est, 3), round(nord, 3)],
            # Dalles d'itinéraire : d'après le territoire réel, pas la marge (inutile de télécharger 180 Mo pour 1 km).
            "dalles": dalles_brouter(geometrie),
        })
    regions.sort(key=lambda r: r["nom"])
    with open(os.path.join(RACINE, "regions.json"), "w", encoding="utf-8") as f:
        json.dump(regions, f, ensure_ascii=False, indent=2)
        f.write("\n")
    for r in regions:
        print(r["id"], r["emprise"], r["dalles"])


if __name__ == "__main__":
    main()
