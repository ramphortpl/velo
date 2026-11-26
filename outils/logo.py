#!/usr/bin/env python3
"""Fabrique tous les fichiers qui montrent le logo de VeloGo, à partir des deux images d'origine.

Les images d'origine (docs/logo/) sont celles fournies par l'auteur de l'appli :
- logo-source.png  : le logo « VeloGo » (vélo, repère de carte, trait rose) sur fond blanc ;
- icone-source.png : l'icône, le même logo posé sur un carré arrondi « verre » au décor de carte.

Ce que le script écrit :
- docs/logo/logo.png           le logo détouré (fond transparent) ;
- docs/logo/icone.png          l'icône, coins transparents et sans son ombre (README, pages web) ;
- docs/logo/partage.png        une image 1280 × 640 pour l'aperçu de partage du dépôt GitHub ;
- app/…/drawable-nodpi/logo_velogo.webp           le logo détouré, pour l'appli (affiche, écran « Sources et licences ») ;
- app/…/mipmap-*/ic_launcher_{background,foreground,monochrome}.*   l'icône adaptative d'Android ;
- app/…/drawable-nodpi/splash_logo.webp           le logo de l'écran de démarrage (même cadrage, en grand) ;
- app/…/drawable-*/ic_stat_velogo.png             l'icône de notification (le vélo du logo, en silhouette).

L'icône d'Android est faite de deux couches que le téléphone découpe à sa façon (rond, carré arrondi…) : le
décor de carte, étendu à toute la surface et débarrassé du logo qui y était dessiné, et par-dessus le logo net,
tenu dans la zone que toutes les formes d'icône laissent visible.

Usage : python3 outils/logo.py [dossier_des_aperçus]
(demande Pillow et numpy : pip install pillow numpy). Avec un dossier, le script y range aussi des planches de
contrôle : l'icône sous plusieurs formes, à côté de l'image d'origine.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = os.path.join(RACINE, "docs", "logo")
RES = os.path.join(RACINE, "app", "src", "main", "res")

# Densités d'écran d'Android : nombre de pixels pour un « dp ».
DENSITES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

# Icône adaptative : un carré de 108 dp, dont le téléphone montre au plus les 72 dp du milieu ; ce qui tient dans
# un cercle de 66 dp de diamètre est visible quelle que soit la forme. On garde une petite marge.
ICONE_DP = 108.0
VISIBLE_DP = 72.0
RAYON_SUR_DP = 32.5

# Le carré arrondi de icone-source.png, mesuré sur l'image : bords, rayon des coins, et largeur du liseré
# « verre » (reflets du bord, tranche du bas) qu'on ne reprend pas dans le décor.
VERRE = {"gauche": 61.0, "droite": 1191.0, "haut": 69.0, "bas": 1183.0, "rayon": 316.0}
LISERE = 30.0


def lire(nom):
    """Une image d'origine, en nombres entre 0 et 1."""
    return np.asarray(Image.open(os.path.join(SOURCES, nom)).convert("RGB")).astype(np.float64) / 255.0


def image(tableau):
    """Un tableau de nombres entre 0 et 1 (3 ou 4 canaux) en image."""
    return Image.fromarray(np.rint(np.clip(tableau, 0.0, 1.0) * 255.0).astype(np.uint8))


def ecrire(img, *chemin, **options):
    cible = os.path.join(*chemin)
    os.makedirs(os.path.dirname(cible), exist_ok=True)
    img.save(cible, **options)
    print(f"  {os.path.relpath(cible, RACINE)} : {img.width} × {img.height}, {os.path.getsize(cible) // 1024 or 1} Ko")


def reduire(img, taille):
    """Réduit une image à transparence sans frange : les couleurs sont pondérées par leur opacité."""
    if isinstance(taille, int):
        taille = (taille, taille)
    if img.mode == "RGBA":
        return img.convert("RGBa").resize(taille, Image.LANCZOS).convert("RGBA")
    return img.resize(taille, Image.LANCZOS)


# --- Le logo détouré ------------------------------------------------------------------------------------------

def detourer(rgb):
    """Rend transparent le fond blanc. Les trois couleurs du logo ont chacune une composante presque nulle :
    la plus faible des trois composantes d'un point dit donc de combien il est mêlé de blanc."""
    opacite = 1.0 - rgb.min(axis=2)
    couleur = (rgb - (1.0 - opacite[..., None])) / np.maximum(opacite[..., None], 1e-6)
    # Le blanc du fond n'est pas tout à fait pur : en dessous de 4 %, c'est du fond.
    opacite = np.clip((opacite - 0.04) / 0.96, 0.0, 1.0)
    couleur[opacite <= 0.0] = 0.0
    return np.dstack([np.clip(couleur, 0.0, 1.0), opacite])


def boite(opacite, seuil=0.02):
    ys, xs = np.where(opacite > seuil)
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def morceau(masque, x, y):
    """La partie d'un seul tenant du masque qui contient le point (x, y), ou à défaut le point le plus proche
    sur la même ligne : une lettre, une roue, le cadre du vélo…"""
    sur_la_ligne = np.where(masque[y])[0]
    depart = (int(sur_la_ligne[np.argmin(np.abs(sur_la_ligne - x))]), y)
    calque = Image.fromarray(masque.astype(np.uint8) * 255).copy()
    ImageDraw.floodfill(calque, depart, 128)
    return np.array(calque) == 128


def velo_du_logo(rgb, opacite):
    """La silhouette du vélo du logo : les deux roues, le « G » qu'elles encadrent, le cadre avec la selle et le
    guidon. Sans les lettres « Vel », le repère de carte ni le trait rose."""
    r, g, b = rgb[..., 0] * 255, rgb[..., 1] * 255, rgb[..., 2] * 255
    fonce = (r < 90) & (g < 140) & (b < 120)
    vif = (r < 90) & (g > 150)
    parties = morceau(fonce, 640, 460) | morceau(fonce, 707, 660) | morceau(vif, 500, 680) | morceau(vif, 1070, 690)
    # Les bords du dessin sont adoucis : on élargit un peu la sélection, et c'est l'opacité d'origine qui tranche.
    large = np.asarray(Image.fromarray(parties.astype(np.uint8) * 255).filter(ImageFilter.MaxFilter(5))) > 0
    return opacite * large


def plus_petit_cercle(opacite):
    """Centre et rayon du plus petit cercle qui contient tout le dessin (par approches successives)."""
    ys, xs = np.where(opacite > 0.5)
    points = np.column_stack([xs, ys]).astype(np.float64)[::7]
    centre = points.mean(axis=0)
    for i in range(1, 4000):
        loin = points[np.argmax(((points - centre) ** 2).sum(axis=1))]
        centre += (loin - centre) / (i + 1)
    tous = np.column_stack([xs, ys]).astype(np.float64)
    return centre, float(np.sqrt(((tous - centre) ** 2).sum(axis=1).max()))


# --- Le décor de l'icône --------------------------------------------------------------------------------------

def distance_au_verre(hauteur, largeur):
    """Distance de chaque point au bord du carré arrondi : négative dedans, positive dehors."""
    cx, cy = (VERRE["gauche"] + VERRE["droite"]) / 2, (VERRE["haut"] + VERRE["bas"]) / 2
    demi_l, demi_h = (VERRE["droite"] - VERRE["gauche"]) / 2, (VERRE["bas"] - VERRE["haut"]) / 2
    y, x = np.mgrid[0:hauteur, 0:largeur].astype(np.float64)
    dx = np.abs(x + 0.5 - cx) - (demi_l - VERRE["rayon"])
    dy = np.abs(y + 0.5 - cy) - (demi_h - VERRE["rayon"])
    dehors = np.sqrt(np.maximum(dx, 0.0) ** 2 + np.maximum(dy, 0.0) ** 2)
    dedans = np.minimum(np.maximum(dx, dy), 0.0)
    return dehors + dedans - VERRE["rayon"]


def encre(rgb):
    """Les points de l'icône où le logo est dessiné (ses trois couleurs franches)."""
    r, g, b = rgb[..., 0] * 255, rgb[..., 1] * 255, rgb[..., 2] * 255
    fonce = (r < 70) & (g < 120) & (b < 100)
    vif = (r < 45) & (g > 150)
    rose = (r > 170) & (g < 110) & (b > 60)
    return fonce | vif | rose


def combler(couleur, connu):
    """Remplit les zones inconnues à partir de ce qui les entoure : on réduit l'image de moitié en moitié en ne
    comptant que les points connus, puis on remonte en ne remplaçant que ce qui manquait."""
    niveaux = [(couleur * connu[..., None], connu.astype(np.float64))]
    while min(niveaux[-1][1].shape) > 1:
        c, p = niveaux[-1]
        h, w = p.shape[0] // 2 * 2, p.shape[1] // 2 * 2
        if h == 0 or w == 0:
            break
        c2 = c[:h, :w].reshape(h // 2, 2, w // 2, 2, 3).sum(axis=(1, 3))
        p2 = p[:h, :w].reshape(h // 2, 2, w // 2, 2).sum(axis=(1, 3))
        norme = np.maximum(p2, 1e-9)
        niveaux.append((c2 / norme[..., None] * np.minimum(p2, 1.0)[..., None], np.minimum(p2, 1.0)))
    c, p = niveaux[-1]
    plein = c / np.maximum(p, 1e-9)[..., None]
    for c, p in reversed(niveaux[:-1]):
        h, w = p.shape
        agrandi = np.asarray(image(plein).resize((w, h), Image.BILINEAR)).astype(np.float64) / 255.0
        plein = c + agrandi * (1.0 - p[..., None])
    return plein


def decor(icone):
    """Le décor de carte sur tout le carré de l'icône adaptative, à l'échelle de l'image d'origine."""
    hauteur, largeur = icone.shape[:2]
    dans_le_verre = distance_au_verre(hauteur, largeur) < -LISERE
    logo = np.asarray(Image.fromarray(encre(icone).astype(np.uint8) * 255).filter(ImageFilter.MaxFilter(37))) > 0
    connu = dans_le_verre & ~logo

    # Le carré visible (72 dp) est le carré arrondi d'origine ; la couche entière en fait une fois et demie.
    cote = int(round((VERRE["droite"] - VERRE["gauche"]) * ICONE_DP / VISIBLE_DP))
    cx, cy = (VERRE["gauche"] + VERRE["droite"]) / 2, (VERRE["haut"] + VERRE["bas"]) / 2
    x0, y0 = int(round(cote / 2 - cx)), int(round(cote / 2 - cy))
    grand = np.zeros((cote, cote, 3))
    masque = np.zeros((cote, cote), dtype=bool)
    grand[y0:y0 + hauteur, x0:x0 + largeur] = icone
    masque[y0:y0 + hauteur, x0:x0 + largeur] = connu
    return combler(grand, masque), masque


# --- Les formes d'icône des téléphones (pour les planches de contrôle) -----------------------------------------

def forme(taille, genre):
    """Opacité d'une forme d'icône sur un carré de [taille] points : « rond », « samsung » (carré très arrondi)
    ou « carre » (coins peu arrondis)."""
    y, x = (np.mgrid[0:taille * 4, 0:taille * 4] + 0.5) / (taille * 4) * 2 - 1
    if genre == "rond":
        dedans = x ** 2 + y ** 2 <= 1.0
    elif genre == "samsung":
        dedans = np.abs(x) ** 4.6 + np.abs(y) ** 4.6 <= 1.0
    else:
        rayon = 0.24
        dx, dy = np.maximum(np.abs(x) - (1 - rayon), 0), np.maximum(np.abs(y) - (1 - rayon), 0)
        dedans = dx ** 2 + dy ** 2 <= rayon ** 2
    return np.asarray(Image.fromarray(dedans.astype(np.uint8) * 255).resize((taille, taille), Image.LANCZOS)).astype(np.float64) / 255.0


def apercu_icone(fond, premier_plan, taille, genre, sur=(255, 255, 255)):
    """L'icône telle qu'un téléphone la montre : les 72 dp du milieu des deux couches, découpés à sa forme."""
    marge = (ICONE_DP - VISIBLE_DP) / 2 / ICONE_DP
    def milieu(img):
        w = img.width
        return reduire(img.crop((round(w * marge), round(w * marge), round(w * (1 - marge)), round(w * (1 - marge)))), taille)
    couches = Image.alpha_composite(milieu(fond).convert("RGBA"), milieu(premier_plan))
    opacite = Image.fromarray(np.rint(forme(taille, genre) * 255).astype(np.uint8))
    resultat = Image.new("RGB", (taille, taille), sur)
    resultat.paste(couches.convert("RGB"), (0, 0), opacite)
    return resultat


# --- Fabrication ----------------------------------------------------------------------------------------------

def main():
    apercus = sys.argv[1] if len(sys.argv) > 1 else None

    print("Logo détouré")
    brut = detourer(lire("logo-source.png"))
    gauche, haut, droite, bas = boite(brut[..., 3])
    marge = 24
    logo = image(brut[haut - marge:bas + marge, gauche - marge:droite + marge])
    ecrire(logo, SOURCES, "logo.png", optimize=True)
    ecrire(reduire(logo, (720, round(720 * logo.height / logo.width))), RES, "drawable-nodpi", "logo_velogo.webp", lossless=True, method=6)

    print("Icône adaptative")
    # Premier plan : le logo, centré sur son plus petit cercle, lequel tient dans la zone toujours visible.
    centre, rayon = plus_petit_cercle(brut[..., 3])
    cote = int(round(rayon * ICONE_DP / RAYON_SUR_DP))
    x0, y0 = int(round(cote / 2 - centre[0])), int(round(cote / 2 - centre[1]))
    premier_plan = np.zeros((cote, cote, 4))
    h, w = brut.shape[:2]
    premier_plan[max(y0, 0):y0 + h, max(x0, 0):x0 + w] = brut[max(-y0, 0):, max(-x0, 0):][:cote - max(y0, 0), :cote - max(x0, 0)]
    premier_plan = image(premier_plan)
    silhouette = Image.merge("RGBA", [Image.new("L", premier_plan.size, 255)] * 3 + [premier_plan.getchannel("A")])
    fond, _ = decor(lire("icone-source.png"))
    fond = image(fond)
    largeur_dp = (droite - gauche) / cote * ICONE_DP
    print(f"  logo large de {largeur_dp:.1f} dp sur les {VISIBLE_DP:.0f} visibles, dans un cercle de {2 * RAYON_SUR_DP:.0f} dp")
    # L'écran de démarrage montre le premier plan sur 288 dp, dans un rond des deux tiers : la couche de l'icône
    # (432 points au plus) y serait floue, d'où cette version en grand.
    ecrire(reduire(premier_plan, 1152), RES, "drawable-nodpi", "splash_logo.webp", lossless=True, method=6)
    for nom, facteur in DENSITES.items():
        pixels = int(round(ICONE_DP * facteur))
        ecrire(reduire(fond, pixels), RES, f"mipmap-{nom}", "ic_launcher_background.webp", quality=92, method=6)
        ecrire(reduire(premier_plan, pixels), RES, f"mipmap-{nom}", "ic_launcher_foreground.png", optimize=True)
        ecrire(reduire(silhouette, pixels), RES, f"mipmap-{nom}", "ic_launcher_monochrome.png", optimize=True)

    print("Icône de notification")
    velo = velo_du_logo(lire("logo-source.png"), brut[..., 3])
    vg, vh, vd, vb = boite(velo, 0.5)
    dessin = Image.fromarray(np.rint(velo[vh:vb, vg:vd] * 255).astype(np.uint8))
    for nom, facteur in DENSITES.items():
        pixels = int(round(24 * facteur))
        dedans = pixels - 2 * int(round(facteur))                   # un dp de marge de chaque côté
        echelle = min(dedans / dessin.width, dedans / dessin.height)
        petit = dessin.resize((max(1, round(dessin.width * echelle)), max(1, round(dessin.height * echelle))), Image.LANCZOS)
        opacite = Image.new("L", (pixels, pixels), 0)
        opacite.paste(petit, ((pixels - petit.width) // 2, (pixels - petit.height) // 2))
        ecrire(Image.merge("RGBA", [Image.new("L", (pixels, pixels), 255)] * 3 + [opacite]), RES, f"drawable-{nom}", "ic_stat_velogo.png", optimize=True)

    print("Pour GitHub")
    icone = lire("icone-source.png")
    opacite = np.clip(0.5 - (distance_au_verre(*icone.shape[:2]) + 1.0), 0.0, 1.0)
    icone = image(np.dstack([icone, opacite]))
    cadre = (round(VERRE["gauche"]) - 2, round(VERRE["haut"]) - 2, round(VERRE["droite"]) + 2, round(VERRE["bas"]) + 2)
    icone = icone.crop(cadre)
    ecrire(reduire(icone, (512, round(512 * icone.height / icone.width))), SOURCES, "icone.png", optimize=True)
    partage = Image.new("RGB", (1280, 640), (255, 255, 255))
    large = 860
    petit = reduire(logo, (large, round(large * logo.height / logo.width)))
    partage.paste(petit, ((1280 - petit.width) // 2, (640 - petit.height) // 2), petit)
    ecrire(partage, SOURCES, "partage.png", optimize=True)

    print("Pour Google Play")
    # Icône de la fiche du magasin : un carré plein de 512 points, sans coins arrondis ni ombre (le magasin la
    # découpe lui-même). Ce sont les 72 dp du milieu des deux couches de l'icône adaptative, comme sur un téléphone.
    marge = (ICONE_DP - VISIBLE_DP) / 2 / ICONE_DP
    def milieu(img, taille):
        w = img.width
        return reduire(img.crop((round(w * marge), round(w * marge), round(w * (1 - marge)), round(w * (1 - marge)))), taille)
    magasin = Image.alpha_composite(milieu(fond, 512).convert("RGBA"), milieu(premier_plan, 512))
    ecrire(magasin, RACINE, "docs", "play", "icone-512.png", optimize=True)
    # Bannière de la fiche : 1024 × 500, sans transparence. Le décor de carte de l'icône, et le logo au milieu.
    largeur, hauteur = 1024, 500
    decor_large = reduire(fond, largeur).convert("RGB")
    haut_decor = (decor_large.height - hauteur) // 2
    banniere = decor_large.crop((0, haut_decor, largeur, haut_decor + hauteur))
    # Un voile clair au centre, pour que le logo se lise sur les traits de la carte.
    voile = Image.new("L", (largeur, hauteur), 0)
    yy, xx = np.mgrid[0:hauteur, 0:largeur]
    eloignement = ((xx - largeur / 2) / (largeur * 0.46)) ** 2 + ((yy - hauteur / 2) / (hauteur * 0.52)) ** 2
    voile = Image.fromarray(np.rint(np.clip(1.0 - eloignement, 0.0, 1.0) ** 0.8 * 150).astype(np.uint8))
    banniere.paste(Image.new("RGB", (largeur, hauteur), (255, 255, 255)), (0, 0), voile)
    large_logo = 600
    petit_logo = reduire(logo, (large_logo, round(large_logo * logo.height / logo.width)))
    banniere.paste(petit_logo, ((largeur - petit_logo.width) // 2, (hauteur - petit_logo.height) // 2), petit_logo)
    ecrire(banniere, RACINE, "docs", "play", "banniere-1024x500.png", optimize=True)

    if apercus:
        os.makedirs(apercus, exist_ok=True)
        print("Planches de contrôle")
        origine = Image.open(os.path.join(SOURCES, "icone-source.png")).convert("RGB")
        taille = 384
        planche = Image.new("RGB", (4 * taille + 5 * 24, taille + 48), (255, 255, 255))
        planche.paste(origine.resize((taille, taille), Image.LANCZOS), (24, 24))
        for i, genre in enumerate(("samsung", "rond", "carre")):
            planche.paste(apercu_icone(fond, premier_plan, taille, genre), (24 + (i + 1) * (taille + 24), 24))
        ecrire(planche, apercus, "icone_formes.png")
        # À la taille réelle d'un écran d'accueil (48 dp), sur fond clair et sur fond sombre.
        reel = Image.new("RGB", (6 * 168 + 24, 2 * 168 + 24), (255, 255, 255))
        for ligne, sur in enumerate(((240, 244, 242), (24, 28, 32))):
            Image.Image.paste(reel, Image.new("RGB", (reel.width, 180), sur), (0, ligne * 180))
            for i, (genre, taille_px) in enumerate((("samsung", 144), ("rond", 144), ("carre", 144), ("samsung", 96), ("rond", 96), ("samsung", 72))):
                vignette = apercu_icone(fond, premier_plan, taille_px, genre, sur)
                reel.paste(vignette, (24 + i * 168 + (144 - taille_px) // 2, ligne * 180 + 18 + (144 - taille_px) // 2))
        ecrire(reel, apercus, "icone_taille_reelle.png")
        ecrire(fond, apercus, "decor_entier.png")
        # L'icône de notification, agrandie : blanc sur gris, comme dans la barre d'état.
        barre = Image.new("RGB", (5 * 120, 140), (60, 64, 67))
        for i, nom in enumerate(DENSITES):
            stat = Image.open(os.path.join(RES, f"drawable-{nom}", "ic_stat_velogo.png"))
            barre.paste(stat, (20 + i * 120, 20), stat)
        ecrire(barre, apercus, "notification.png")


if __name__ == "__main__":
    main()
