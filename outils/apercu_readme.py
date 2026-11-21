#!/usr/bin/env python3
"""Fabrique l'image d'aperçu du README (docs/apercu.jpg) à partir des captures de l'essai sur émulateur.

Les captures viennent de l'artefact « essai-emulateur » d'une fabrication réussie (onglet Actions de GitHub) :
ce sont de vrais écrans de l'appli, pris pendant la sortie simulée à Corte.

Usage : python3 outils/apercu_readme.py dossier_des_captures [autre_dossier…] docs/apercu.jpg
(demande la bibliothèque Pillow : pip install pillow)

Chaque écran est pris dans le premier dossier qui le contient. C'est utile pour la météo : le service limite les
demandes des machines de GitHub, et sa capture manque dans bien des essais ; on donne alors en second le dossier
d'un essai plus ancien où elle existe.
"""
import glob
import os
import sys

from PIL import Image, ImageDraw, ImageFont

# Titre de chaque écran, et la capture qui le montre (son nom sans le numéro d'ordre, qui change d'un essai à l'autre).
ECRANS = [
    ("Carte pendant la sortie", "carte_avec_trace"),
    ("Écran guidon, même hors ligne", "mode_avion_guidon"),
    ("Guidage avec flèche", "mode_avion_carte_en_roulant"),
    ("Itinéraires : la carte en grand", "itineraire_propositions"),
    ("Affiche à partager", "itineraire_affiche"),
    ("Onglet « Trajets »", "trajets"),
    ("Météo", "meteo"),
    ("Statistiques", "statistiques"),
    ("Segment chronométré", "segment_detail"),
]
COLONNES = 3
LARGEUR = 500          # largeur d'un écran dans l'image
MARGE = 32
ECART = 28             # entre deux colonnes
TITRE = 70             # hauteur réservée au titre, au-dessus de chaque écran
ENTRE_LIGNES = 36
FOND = (246, 248, 247)
BORD = (202, 207, 203)
VERT = (0, 84, 64)
POLICES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/Library/Fonts/Arial Bold.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
]


def police(taille):
    for chemin in POLICES:
        if os.path.exists(chemin):
            return ImageFont.truetype(chemin, taille)
    return ImageFont.load_default()


def capture(dossiers, nom):
    """La capture « NN_<nom>.png » (le numéro d'ordre dépend du déroulé de l'essai), dans le premier dossier qui l'a."""
    for rang, dossier in enumerate(dossiers):
        trouvees = sorted(glob.glob(os.path.join(dossier, f"[0-9][0-9]_{nom}.png")))
        if trouvees:
            if rang:
                print(f"« {nom} » vient de {dossier}")
            return Image.open(trouvees[0]).convert("RGB")
    sys.exit(f"capture introuvable : ??_{nom}.png dans {', '.join(dossiers)}")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    dossiers, sortie = sys.argv[1:-1], sys.argv[-1]
    images = [capture(dossiers, nom) for _, nom in ECRANS]
    hauteur = round(LARGEUR * images[0].height / images[0].width)
    lignes = (len(ECRANS) + COLONNES - 1) // COLONNES
    total = Image.new(
        "RGB",
        (2 * MARGE + COLONNES * LARGEUR + (COLONNES - 1) * ECART, MARGE // 2 + lignes * (TITRE + hauteur) + (lignes - 1) * ENTRE_LIGNES + MARGE),
        FOND,
    )
    dessin = ImageDraw.Draw(total)
    lettres = police(27)
    for i, ((titre, _), image) in enumerate(zip(ECRANS, images)):
        x = MARGE + (i % COLONNES) * (LARGEUR + ECART)
        y = MARGE // 2 + (i // COLONNES) * (TITRE + hauteur + ENTRE_LIGNES)
        gauche, haut, droite, bas = dessin.textbbox((0, 0), titre, font=lettres)
        dessin.text((x + (LARGEUR - (droite - gauche)) / 2 - gauche, y + (TITRE - (bas - haut)) / 2 - haut), titre, fill=VERT, font=lettres)
        total.paste(image.resize((LARGEUR, hauteur), Image.LANCZOS), (x, y + TITRE))
        dessin.rectangle((x - 1, y + TITRE - 1, x + LARGEUR, y + TITRE + hauteur), outline=BORD)
    total.save(sortie, quality=86, optimize=True, progressive=True)
    print(f"{sortie} : {total.width} × {total.height} px, {os.path.getsize(sortie) // 1024} Ko")


if __name__ == "__main__":
    main()
