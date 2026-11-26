#!/usr/bin/env python3
"""Prépare les captures d'écran de la fiche Google Play (docs/play/ecrans/) à partir de l'essai sur émulateur.

Les captures viennent de l'artefact « essai-emulateur » d'une fabrication : ce sont de vrais écrans de l'appli.
Le magasin en demande de 2 à 8, en PNG ou JPEG, dont le grand côté ne dépasse pas 2,3 fois le petit ; celles de
l'émulateur (1080 × 2400) conviennent, et sont simplement enregistrées en JPEG.

Usage : python3 outils/captures_play.py dossier_des_captures [autre_dossier…]
(demande la bibliothèque Pillow : pip install pillow)

Chaque écran est pris dans le premier dossier qui le contient ; un écran introuvable est simplement laissé de côté
(la météo manque dans bien des essais : le service limite les demandes des machines de GitHub).
"""
import glob
import os
import sys

from PIL import Image

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SORTIE = os.path.join(RACINE, "docs", "play", "ecrans")
# Dans l'ordre où la fiche les montrera : le nom du fichier produit, puis la capture de l'essai qui le fournit.
ECRANS = [
    ("1-carte", "carte_avec_trace"),
    ("2-guidon", "guidon_en_roulant"),
    ("3-itineraires", "itineraire_propositions"),
    ("4-guidage", "mode_avion_carte_en_roulant"),
    ("5-trajets", "trajets"),
    ("6-statistiques", "statistiques"),
    ("7-affiche", "itineraire_affiche"),
    ("8-meteo", "meteo"),
]
RAPPORT_MAX = 2.3


def capture(dossiers, nom):
    for dossier in dossiers:
        trouvees = sorted(glob.glob(os.path.join(dossier, f"[0-9][0-9]_{nom}.png")))
        if trouvees:
            return trouvees[0]
    return None


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    dossiers = sys.argv[1:]
    os.makedirs(SORTIE, exist_ok=True)
    for ancien in glob.glob(os.path.join(SORTIE, "*.jpg")):
        os.remove(ancien)
    faites = 0
    for nom, source in ECRANS:
        chemin = capture(dossiers, source)
        if chemin is None:
            print(f"{nom} : capture « {source} » introuvable, laissée de côté")
            continue
        image = Image.open(chemin).convert("RGB")
        grand, petit = max(image.size), min(image.size)
        if grand > RAPPORT_MAX * petit or petit < 320 or grand > 3840:
            sys.exit(f"{nom} : {image.width} × {image.height} ne convient pas au magasin")
        # En JPEG : le magasin l'accepte, et le dépôt ne s'alourdit pas de plusieurs Mo à chaque mise à jour.
        cible = os.path.join(SORTIE, f"{nom}.jpg")
        image.save(cible, quality=92, optimize=True)
        print(f"{nom} : {image.width} × {image.height}, {os.path.getsize(cible) // 1024} Ko")
        faites += 1
    if faites < 2:
        sys.exit("moins de deux captures : le magasin en demande au moins deux")


if __name__ == "__main__":
    main()
