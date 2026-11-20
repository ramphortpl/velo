#!/usr/bin/env python3
"""Redécoupe les données d'itinéraire de BRouter (fichiers .rd5) en carrés d'un degré de côté.

BRouter range ses données par dalles de 5° x 5° : jusqu'à 250 Mo, dont une bonne part hors de France. Chaque
dalle contient 25 blocs indépendants d'un degré de côté. Cet outil recopie chaque bloc, tel quel, dans un petit
fichier au même format qui ne contient que lui : une région ne télécharge ainsi que les carrés qu'elle touche.
L'appli lit ces fichiers avec le moteur BRouter embarqué (seul le nom du fichier cherché y a été adapté).

Format d'un fichier .rd5 (voir btools.mapaccess.PhysicalFile et OsmFile dans les sources de BRouter) :
  - 200 octets : 25 entiers de 8 octets, la position de fin de chaque bloc (48 bits) et la version (16 bits) ;
  - les blocs, à la suite : un index de 32 x 32 positions puis les données de chaque « micro-cache » ;
  - en queue : date de création, somme de contrôle de l'en-tête, somme de contrôle de l'index de chaque bloc,
    type d'altitude.

Usage :
  dalles.py decouper <dalle.rd5> <dossier>                découpe une dalle 5° en fichiers dalle-<carré>.rd5
  dalles.py extraire <dalle.rd5> <sortie.rd5> <ouest,sud,est,nord>
                                                          garde seulement les micro-caches d'une petite zone
                                                          (sert à fabriquer le fichier d'essai des tests)
"""
import os
import re
import struct
import sys
import zlib

EN_TETE = 200
BLOCS = 25
DIVISEUR = 32


def crc(octets):
    """Somme de contrôle de BRouter : CRC-32 classique, sans l'inversion finale."""
    valeur = (zlib.crc32(octets) ^ 0xFFFFFFFF) & 0xFFFFFFFF
    return valeur - (1 << 32) if valeur >= (1 << 31) else valeur


class Dalle:
    """Une dalle .rd5 ouverte en lecture."""

    def __init__(self, chemin):
        self.chemin = chemin
        nom = os.path.basename(chemin)
        trouve = re.fullmatch(r"([EW])(\d+)_([NS])(\d+)\.rd5", nom)
        if not trouve:
            raise SystemExit(f"nom de dalle inattendu : {nom}")
        self.lon = int(trouve.group(2)) * (1 if trouve.group(1) == "E" else -1)
        self.lat = int(trouve.group(4)) * (1 if trouve.group(3) == "N" else -1)
        self.taille = os.path.getsize(chemin)
        self.f = open(chemin, "rb")
        entrees = struct.unpack(">25q", self.f.read(EN_TETE))
        self.versions = [e >> 48 for e in entrees]
        self.fins = [e & 0xFFFFFFFFFFFF for e in entrees]
        self.f.seek(self.fins[-1])
        queue = self.f.read()
        if len(queue) < 8 + 26 * 4:
            raise SystemExit(f"{nom} : format ancien ou fichier tronqué")
        self.creation, somme = struct.unpack(">qi", queue[:12])
        self.f.seek(0)
        if (somme ^ 2) != crc(self.f.read(EN_TETE)):
            raise SystemExit(f"{nom} : en-tête abîmé ou format non géré (il faut le format à 32 micro-caches par degré)")
        self.sommes_blocs = list(struct.unpack(">25i", queue[12:112]))
        self.type_altitude = queue[112:113]

    def bloc(self, k):
        debut = EN_TETE if k == 0 else self.fins[k - 1]
        self.f.seek(debut)
        return self.f.read(self.fins[k] - debut)

    def carre(self, k):
        """Coin sud-ouest (longitude, latitude) du bloc k."""
        return self.lon + k // 5, self.lat + k % 5

    def ecrire(self, sortie, blocs, sommes_blocs):
        """Écrit un fichier au format .rd5 avec les blocs donnés (dictionnaire numéro -> octets)."""
        en_tete = bytearray()
        position = EN_TETE
        for k in range(BLOCS):
            position += len(blocs.get(k, b""))
            en_tete += struct.pack(">q", (self.versions[k] << 48) | position)
        with open(sortie, "wb") as f:
            f.write(en_tete)
            for k in range(BLOCS):
                f.write(blocs.get(k, b""))
            f.write(struct.pack(">qi", self.creation, crc(bytes(en_tete)) ^ 2))
            f.write(struct.pack(">25i", *sommes_blocs))
            f.write(self.type_altitude)


def nom_carre(lon, lat):
    return ("E" if lon >= 0 else "W") + str(abs(lon)) + "_" + ("N" if lat >= 0 else "S") + str(abs(lat))


def decouper(chemin, dossier):
    """Un fichier par bloc non vide, nommé d'après son carré d'un degré. Renvoie la liste des fichiers écrits."""
    dalle = Dalle(chemin)
    os.makedirs(dossier, exist_ok=True)
    ecrits = []
    for k in range(BLOCS):
        contenu = dalle.bloc(k)
        if not contenu:
            continue
        lon, lat = dalle.carre(k)
        sortie = os.path.join(dossier, f"dalle-{nom_carre(lon, lat)}.rd5")
        dalle.ecrire(sortie, {k: contenu}, dalle.sommes_blocs)
        ecrits.append(sortie)
    return ecrits


def extraire(chemin, sortie, emprise):
    """Garde les seuls micro-caches (1/32 de degré de côté) qui touchent l'emprise : un fichier minuscule pour les tests."""
    ouest, sud, est, nord = emprise
    dalle = Dalle(chemin)
    blocs = {}
    sommes = list(dalle.sommes_blocs)
    for k in range(BLOCS):
        contenu = dalle.bloc(k)
        if not contenu:
            continue
        lon, lat = dalle.carre(k)
        if lon + 1 <= ouest or lon >= est or lat + 1 <= sud or lat >= nord:
            continue
        taille_index = DIVISEUR * DIVISEUR * 4
        fins = struct.unpack(f">{DIVISEUR * DIVISEUR}i", contenu[:taille_index])
        nouvelles_fins = []
        donnees = bytearray()
        gardes = 0
        for i, fin in enumerate(fins):
            debut = taille_index if i == 0 else fins[i - 1]
            ligne, colonne = divmod(i, DIVISEUR)
            o = lon + colonne / DIVISEUR
            s = lat + ligne / DIVISEUR
            if o + 1 / DIVISEUR > ouest and o < est and s + 1 / DIVISEUR > sud and s < nord and fin > debut:
                donnees += contenu[debut:fin]
                gardes += 1
            nouvelles_fins.append(taille_index + len(donnees))
        if gardes == 0:
            continue
        index = struct.pack(f">{DIVISEUR * DIVISEUR}i", *nouvelles_fins)
        blocs[k] = index + bytes(donnees)
        sommes[k] = crc(index)
        print(f"bloc {nom_carre(lon, lat)} : {gardes} micro-caches gardés, {len(blocs[k])} octets")
    dalle.ecrire(sortie, blocs, sommes)
    print(f"{sortie} : {os.path.getsize(sortie)} octets")


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "decouper":
        for fichier in decouper(sys.argv[2], sys.argv[3]):
            print(fichier, os.path.getsize(fichier))
    elif len(sys.argv) == 5 and sys.argv[1] == "extraire":
        extraire(sys.argv[2], sys.argv[3], [float(v) for v in sys.argv[4].split(",")])
    else:
        sys.exit(__doc__)
