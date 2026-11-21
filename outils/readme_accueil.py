#!/usr/bin/env python3
"""Écrit, à partir du README de la branche de travail, celui de la page d'accueil du dépôt (branche `main`).

Tant que la branche de travail n'est pas fusionnée, `main` ne contient que son README : les liens vers les autres
fichiers (image d'aperçu, nouveautés, notes d'architecture) doivent donc pointer vers la branche de travail, et
une ligne dit où se trouve le code. Le jour de la fusion, le README d'origine remplace cette copie.

Usage : python3 outils/readme_accueil.py > README_accueil.md
"""
import os
import re
import subprocess
import sys

DEPOT = "ramphortpl/velo"
RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def branche():
    return subprocess.run(["git", "-C", RACINE, "rev-parse", "--abbrev-ref", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()


def main():
    nom = sys.argv[1] if len(sys.argv) > 1 else branche()
    texte = open(os.path.join(RACINE, "README.md"), encoding="utf-8").read()

    def absolu(lien):
        image, libelle, cible = lien.group(1), lien.group(2), lien.group(3)
        if re.match(r"[a-z]+://|#", cible):
            return lien.group(0)
        if not os.path.exists(os.path.join(RACINE, cible)):
            sys.exit(f"lien vers un fichier absent : {cible}")
        if image:
            return f"![{libelle}](https://raw.githubusercontent.com/{DEPOT}/refs/heads/{nom}/{cible})"
        return f"[{libelle}](https://github.com/{DEPOT}/blob/{nom}/{cible})"

    texte, liens = re.subn(r"(!?)\[([^\]]*)\]\(([^)\s]+)\)", absolu, texte)

    # Les images posées en HTML (le logo, centré et à taille fixe) : même traitement pour leur adresse.
    def image_html(balise):
        cible = balise.group(2)
        if re.match(r"[a-z]+://|#", cible):
            return balise.group(0)
        if not os.path.exists(os.path.join(RACINE, cible)):
            sys.exit(f"image absente : {cible}")
        return f'{balise.group(1)}https://raw.githubusercontent.com/{DEPOT}/refs/heads/{nom}/{cible}{balise.group(3)}'

    texte, images = re.subn(r'(<img\b[^>]*?\bsrc=")([^"]+)(")', image_html, texte)
    liens += images
    note = (
        f"> Le code de l'appli se trouve pour l'instant sur la branche\n"
        f"> [`{nom}`](https://github.com/{DEPOT}/tree/{nom}) ; la branche `main` ne contient que cette page.\n\n"
    )
    # La note se place juste avant le premier titre de section.
    avant, titre, apres = texte.partition("\n## ")
    if not titre:
        sys.exit("README sans section : où placer la note ?")
    sys.stdout.write(avant.rstrip("\n") + "\n\n" + note + titre.lstrip("\n") + apres)
    print(f"{liens} lien(s) relus, branche {nom}", file=sys.stderr)


if __name__ == "__main__":
    main()
