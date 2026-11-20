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
    """Carrés d'un degré de côté (nommés par leur coin sud-ouest, comme chez BRouter) que touche la région.

    BRouter range ses données d'itinéraire par dalles de 5° x 5° (jusqu'à 250 Mo). Le pipeline les redécoupe en
    carrés de 1° (cartes/outils/dalles.py) : une région ne télécharge ainsi que ce dont elle a besoin.
    """
    ouest, sud, est, nord = geometrie.bounds
    dalles = []
    for lon in range(int(math.floor(ouest)), int(math.floor(est)) + 1):
        for lat in range(int(math.floor(sud)), int(math.floor(nord)) + 1):
            if geometrie.intersects(box(lon, lat, lon + 1, lat + 1)):
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
            # Données d'itinéraire : les carrés de 1° que touche la région élargie (on peut ainsi partir de chez
            # soi vers la région voisine sans que l'itinéraire s'arrête à la frontière administrative).
            "dalles": dalles_brouter(contour),
        })
    regions.sort(key=lambda r: r["nom"])
    with open(os.path.join(RACINE, "regions.json"), "w", encoding="utf-8") as f:
        json.dump(regions, f, ensure_ascii=False, indent=2)
        f.write("\n")
    for r in regions:
        print(r["id"], r["emprise"], r["dalles"])


if __name__ == "__main__":
    main()
