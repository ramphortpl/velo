#!/usr/bin/env python3
"""Vérifie les textes de la fiche Google Play (docs/play/fiche.md) : le magasin refuse ceux qui dépassent.

Nom de l'appli : 30 caractères. Description courte : 80. Description complète : 4 000.
Usage : python3 outils/fiche_play.py
"""
import os
import re
import sys

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIMITES = {"Nom de l'appli": 30, "Description courte": 80, "Description complète": 4000}


def blocs(texte):
    """Le texte entre ``` qui suit chaque titre « ## … »."""
    trouves = {}
    for titre, corps in re.findall(r"^## (.+?)\n(.*?)(?=^## |\Z)", texte, flags=re.S | re.M):
        bloc = re.search(r"```\n(.*?)\n```", corps, flags=re.S)
        if bloc:
            trouves[titre.strip()] = bloc.group(1)
    return trouves


def main():
    with open(os.path.join(RACINE, "docs", "play", "fiche.md"), encoding="utf-8") as fichier:
        trouves = blocs(fichier.read())
    erreurs = 0
    for titre, limite in LIMITES.items():
        texte = trouves.get(titre)
        if texte is None:
            print(f"{titre} : introuvable dans la fiche")
            erreurs += 1
            continue
        longueur = len(texte)
        print(f"{titre} : {longueur} caractères sur {limite}")
        if longueur > limite or not texte.strip():
            erreurs += 1
    return 1 if erreurs else 0


if __name__ == "__main__":
    sys.exit(main())
