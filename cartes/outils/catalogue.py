#!/usr/bin/env python3
"""Outils du pipeline de cartes hors ligne (lancés par .github/workflows/cartes.yml).

  catalogue.py selection              -> liste JSON des régions à générer (pour la matrice du workflow)
  catalogue.py fragment <id> <f>...   -> décrit les fichiers d'une région (taille, empreinte) dans fragment-<id>.json
                                         et leur donne leur nom de publication (avec le début de l'empreinte)
  catalogue.py publier <fragment>...  -> envoie dans la Release les fichiers qui n'y sont pas déjà
  catalogue.py dalles                 -> récupère sur brouter.de les données d'itinéraire qui ont changé et les
                                         redécoupe en carrés d'un degré (dalles.py)
  catalogue.py assembler <dossier>    -> assemble catalogue.json à partir des fragments
"""
import datetime
import glob
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import urllib.request

import dalles as decoupe

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def regions():
    with open(os.path.join(RACINE, "regions.json"), encoding="utf-8") as f:
        return json.load(f)


def selection():
    """cartes/selection.txt : « toutes », ou des identifiants de régions séparés par des espaces."""
    chemin = os.path.join(RACINE, "selection.txt")
    demande = os.environ.get("REGIONS_DEMANDEES", "").strip()
    if not demande and os.path.exists(chemin):
        demande = " ".join(l.strip() for l in open(chemin, encoding="utf-8") if l.strip() and not l.startswith("#"))
    connues = [r["id"] for r in regions()]
    if not demande or demande == "toutes":
        return connues
    choisies = [r for r in demande.replace(",", " ").split() if r in connues]
    return choisies or connues


def empreinte(chemin):
    h = hashlib.sha256()
    with open(chemin, "rb") as f:
        for bloc in iter(lambda: f.read(1 << 20), b""):
            h.update(bloc)
    return h.hexdigest()


def decrire(chemin):
    """Décrit un fichier et lui donne son nom de publication, qui contient le début de son empreinte.

    Un fichier dont le contenu change change donc de nom : l'ancien reste en ligne jusqu'à ce que le nouveau
    catalogue soit publié, et un téléchargement en cours pendant une régénération n'est jamais cassé.
    """
    nom = os.path.basename(chemin)
    sha = empreinte(chemin)
    base, extension = os.path.splitext(nom)
    publie = f"{base}-{sha[:10]}{extension}"
    os.replace(chemin, os.path.join(os.path.dirname(chemin), publie))
    return {
        "type": nom.split("-")[0] if "-" in nom else "dalle",
        "nom": nom,
        "fichier": publie,
        "taille": os.path.getsize(os.path.join(os.path.dirname(chemin), publie)),
        "sha256": sha,
    }


def fragment(identifiant, fichiers):
    contenu = {"id": identifiant, "fichiers": [decrire(f) for f in fichiers]}
    with open(f"fragment-{identifiant}.json", "w", encoding="utf-8") as f:
        json.dump(contenu, f, ensure_ascii=False, indent=2)
    print(json.dumps(contenu, ensure_ascii=False))


def en_ligne():
    """Noms des fichiers déjà présents dans la Release de cartes (ou None si on ne peut pas le savoir)."""
    chemin = os.environ.get("FICHIERS_EN_LIGNE")
    if chemin:
        with open(chemin, encoding="utf-8") as f:
            return {ligne.strip() for ligne in f if ligne.strip()}
    etiquette = os.environ.get("ETIQUETTE", "cartes")
    resultat = subprocess.run(
        ["gh", "release", "view", etiquette, "--json", "assets", "--jq", ".assets[].name"],
        capture_output=True, text=True,
    )
    if resultat.returncode != 0:
        return None
    return {ligne.strip() for ligne in resultat.stdout.splitlines() if ligne.strip()}


def publier(fragments):
    """Envoie dans la Release les fichiers décrits par des fragments, sauf ceux qui y sont déjà (même contenu)."""
    presents = en_ligne() or set()
    etiquette = os.environ.get("ETIQUETTE", "cartes")
    for chemin in fragments:
        with open(chemin, encoding="utf-8") as f:
            contenu = json.load(f)
        for fichier in contenu["fichiers"]:
            nom = fichier["fichier"]
            if nom in presents:
                print(f"{nom} : déjà en ligne")
            elif os.path.exists(nom):
                print(f"{nom} : envoi ({fichier['taille'] / 1e6:.0f} Mo)", flush=True)
                subprocess.run(["gh", "release", "upload", etiquette, nom, "--clobber"], check=True)
            else:
                print(f"{nom} : conservé tel quel (inchangé)")


def source_de(carre):
    """Dalle BRouter de 5° x 5° qui contient un carré d'un degré (« E8_N42 » -> « E5_N40 »)."""
    trouve = re.fullmatch(r"([EW])(\d+)_([NS])(\d+)", carre)
    lon = int(trouve.group(2)) * (1 if trouve.group(1) == "E" else -1)
    lat = int(trouve.group(4)) * (1 if trouve.group(3) == "N" else -1)
    lon5 = math.floor(lon / 5) * 5
    lat5 = math.floor(lat / 5) * 5
    return ("E" if lon5 >= 0 else "W") + str(abs(lon5)) + "_" + ("N" if lat5 >= 0 else "S") + str(abs(lat5))


def dalles():
    """Données d'itinéraire BRouter, redécoupées en carrés d'un degré (voir dalles.py).

    BRouter publie des dalles de 5° x 5°. On ne retélécharge que celles qui ont changé sur brouter.de depuis le
    dernier passage (le catalogue précédent garde leur date), et on n'en garde que les carrés que touche au moins
    une région : inutile d'héberger l'Espagne ou l'Italie.
    """
    voulus = sorted({d for r in regions() for d in r["dalles"]})
    par_source = {}
    for carre in voulus:
        par_source.setdefault(source_de(carre), set()).add(f"dalle-{carre}.rd5")
    anciens, anciennes_sources = [], {}
    if os.path.exists("catalogue-precedent.json"):
        try:
            with open("catalogue-precedent.json", encoding="utf-8") as f:
                ancien = json.load(f)
            anciens = [d for d in ancien.get("dalles", []) if d.get("fichier") and d.get("source")]
            anciennes_sources = ancien.get("sources_dalles", {})
        except (ValueError, KeyError):
            pass
    fichiers, sources = [], {}
    for source in sorted(par_source):
        url = f"https://brouter.de/brouter/segments4/{source}.rd5"
        requete = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "VeloGo-cartes (GitHub Actions)"})
        with urllib.request.urlopen(requete, timeout=60) as reponse:
            etat = {"modifie": reponse.headers.get("Last-Modified", ""), "taille": int(reponse.headers.get("Content-Length", "0") or 0)}
        sources[source] = etat
        if etat["modifie"] and anciennes_sources.get(source) == etat:
            repris = [d for d in anciens if d["source"] == source and d["nom"] in par_source[source]]
            print(f"{source} : inchangée depuis le dernier passage ({len(repris)} carrés repris)")
            fichiers += repris
            continue
        print(f"{source} : téléchargement ({etat['taille'] / 1e6:.0f} Mo, {etat['modifie']})", flush=True)
        subprocess.run(["curl", "-fSL", "--retry", "4", "--retry-delay", "15", "-A", "VeloGo-cartes (GitHub Actions)", "-o", f"{source}.rd5", url], check=True)
        for morceau in decoupe.decouper(f"{source}.rd5", "."):
            if os.path.basename(morceau) in par_source[source]:
                description = decrire(morceau)
                description["source"] = source
                fichiers.append(description)
            else:
                os.remove(morceau)
        os.remove(f"{source}.rd5")
    fichiers.sort(key=lambda d: d["nom"])
    with open("fragment-dalles.json", "w", encoding="utf-8") as f:
        json.dump({"id": "dalles", "fichiers": fichiers, "sources": sources}, f, ensure_ascii=False, indent=2)
    print(f"{len(fichiers)} carrés d'itinéraire, {sum(x['taille'] for x in fichiers) / 1e6:.0f} Mo")


def assembler(dossier):
    fragments = {}
    sources_dalles = None
    for chemin in glob.glob(os.path.join(dossier, "**", "fragment-*.json"), recursive=True):
        with open(chemin, encoding="utf-8") as f:
            contenu = json.load(f)
        fragments[contenu["id"]] = contenu["fichiers"]
        if contenu["id"] == "dalles":
            sources_dalles = contenu.get("sources", {})
    catalogue = {
        "version": 1,
        "date": datetime.date.today().isoformat(),
        "sources": {
            "fond": "Protomaps " + os.environ.get("BUILD_PROTOMAPS", ""),
            "relief": "Mapterhorn",
            "donnees": "OpenStreetMap, IGN (relief), BRouter (itinéraires)",
        },
        "france": fragments.get("france", []),
        "dalles": fragments.get("dalles", []),
        # État des dalles d'origine sur brouter.de : sert à ne retélécharger que ce qui a changé.
        "sources_dalles": sources_dalles or {},
        "regions": [],
    }
    # Les régions qui n'ont pas été régénérées cette fois gardent leur description précédente.
    precedent = {}
    if os.path.exists("catalogue-precedent.json"):
        try:
            with open("catalogue-precedent.json", encoding="utf-8") as f:
                ancien = json.load(f)
            # Seules les descriptions au format actuel (avec nom de publication) sont reprises.
            complet = lambda fichiers: bool(fichiers) and all(f.get("fichier") for f in fichiers)
            precedent = {r["id"]: r["fichiers"] for r in ancien.get("regions", []) if complet(r["fichiers"])}
            if not catalogue["france"] and complet(ancien.get("france")):
                catalogue["france"] = ancien["france"]
            if sources_dalles is None and complet(ancien.get("dalles")):
                catalogue["dalles"] = ancien["dalles"]
                catalogue["sources_dalles"] = ancien.get("sources_dalles", {})
        except (ValueError, KeyError):
            precedent = {}
    for region in regions():
        fichiers = fragments.get(region["id"]) or precedent.get(region["id"])
        if fichiers:
            catalogue["regions"].append({
                "id": region["id"],
                "nom": region["nom"],
                "emprise": region["emprise"],
                "dalles": region["dalles"],
                "fichiers": fichiers,
            })
    with open("catalogue.json", "w", encoding="utf-8") as f:
        json.dump(catalogue, f, ensure_ascii=False, indent=1)
        f.write("\n")
    # Ménage : les fichiers en ligne que plus rien ne référence (anciennes versions) seront supprimés
    # par le workflow, une fois le nouveau catalogue publié.
    references = {"catalogue.json"}
    for fichier in catalogue["france"] + catalogue["dalles"]:
        references.add(fichier["fichier"])
    for region in catalogue["regions"]:
        references.update(f["fichier"] for f in region["fichiers"])
    presents = en_ligne()
    with open("a-supprimer.txt", "w", encoding="utf-8") as f:
        for nom in sorted((presents or set()) - references):
            f.write(nom + "\n")
    # Résumé lisible pour la page de la Release.
    def mo(octets):
        return f"{octets / 1e6:.0f} Mo"
    lignes = ["| Région | Fond | Vélo | Relief | Total |", "|---|---|---|---|---|"]
    for region in catalogue["regions"]:
        tailles = {f["type"]: f["taille"] for f in region["fichiers"]}
        lignes.append(f"| {region['nom']} | {mo(tailles.get('fond', 0))} | {mo(tailles.get('velo', 0))} | {mo(tailles.get('relief', 0))} | {mo(sum(tailles.values()))} |")
    with open("resume.md", "w", encoding="utf-8") as f:
        f.write(f"Cartes hors ligne de VeloGo, générées le {catalogue['date']}.\n\n")
        f.write("Ces fichiers sont téléchargés par l'appli (Réglages > Cartes hors ligne) : inutile de les récupérer à la main.\n\n")
        f.write("\n".join(lignes) + "\n\n")
        f.write(f"Vue d'ensemble de la France : {mo(sum(x['taille'] for x in catalogue['france']))}. ")
        f.write(f"Données d'itinéraire BRouter : {mo(sum(x['taille'] for x in catalogue['dalles']))} pour toute la France, en {len(catalogue['dalles'])} carrés d'un degré.\n\n")
        f.write("Données © les contributeurs d'OpenStreetMap (ODbL) ; fond Protomaps ; relief Mapterhorn (IGN, Licence Ouverte) ; BRouter.\n")
    print(open("resume.md", encoding="utf-8").read())


if __name__ == "__main__":
    commande = sys.argv[1]
    if commande == "selection":
        print(json.dumps(selection()))
    elif commande == "fragment":
        fragment(sys.argv[2], sys.argv[3:])
    elif commande == "publier":
        publier(sys.argv[2:])
    elif commande == "dalles":
        dalles()
    elif commande == "assembler":
        assembler(sys.argv[2])
    else:
        sys.exit(__doc__)
