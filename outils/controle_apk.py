#!/usr/bin/env python3
"""Contrôles d'un APK ou d'un fichier AAB fabriqué, sans l'installer.

    python3 outils/controle_apk.py pages  <fichier.apk|.aab>
    python3 outils/controle_apk.py mapbox <fichier.apk|.aab> <oui|non|faux>

« pages » : chaque bibliothèque native 64 bits doit accepter les pages mémoire de 16 Ko (exigence de Google Play
depuis Android 15). Dans l'APK des Releases GitHub les bibliothèques sont compressées : « zipalign » ne regarde alors
que leur place dans le fichier, pas la bibliothèque elle-même. On lit donc l'en-tête de chacune.

« mapbox » : dit si le jeton public de Mapbox est dans l'appli, sans jamais l'écrire, et le compare à ce qu'on
attend (« oui » : le secret MAPBOX_ACCESS_TOKEN a été donné à la fabrication ; « non » ou « faux » : il ne l'a pas
été). Un jeton présent sans secret viendrait du dépôt, où il n'a rien à faire ; un jeton secret (« sk. ») ne doit
jamais se trouver dans une appli.
"""
import re
import struct
import sys
import zipfile

PAGE_16K = 0x4000
PT_LOAD = 1
# Seules les architectures 64 bits sont concernées : aucun téléphone 32 bits n'a de pages de 16 Ko.
ARCHITECTURES_64 = ("arm64-v8a", "x86_64")

# Un jeton de Mapbox : « pk. » ou « sk. », puis deux blocs en base64 séparés par un point (le premier commence par « eyJ »).
JETON_PUBLIC = re.compile(rb"pk\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")
JETON_SECRET = re.compile(rb"sk\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")


def alignements(elf: bytes) -> list[int]:
    """Alignement de chaque segment chargé en mémoire d'une bibliothèque ELF 64 bits."""
    if elf[:4] != b"\x7fELF":
        raise ValueError("pas une bibliothèque ELF")
    if elf[4] != 2:
        raise ValueError("bibliothèque 32 bits")
    ordre = "<" if elf[5] == 1 else ">"
    e_phoff, = struct.unpack_from(ordre + "Q", elf, 0x20)
    e_phentsize, e_phnum = struct.unpack_from(ordre + "HH", elf, 0x36)
    trouves = []
    for i in range(e_phnum):
        debut = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from(ordre + "I", elf, debut)
        if p_type == PT_LOAD:
            p_align, = struct.unpack_from(ordre + "Q", elf, debut + 0x30)
            trouves.append(p_align)
    return trouves


def pages(chemin: str) -> int:
    echecs = 0
    vues = 0
    with zipfile.ZipFile(chemin) as archive:
        for nom in sorted(archive.namelist()):
            if not nom.endswith(".so") or "/lib/" not in "/" + nom:
                continue
            architecture = nom.split("/")[-2]
            if architecture not in ARCHITECTURES_64:
                continue
            vues += 1
            trouves = alignements(archive.read(nom))
            plus_petit = min(trouves) if trouves else 0
            bon = plus_petit >= PAGE_16K
            print(f"  {'bon' if bon else 'ÉCHEC'}  {nom} : segments alignés sur {plus_petit // 1024} Ko")
            if not bon:
                echecs += 1
    if vues == 0:
        print(f"::error::{chemin} : aucune bibliothèque native 64 bits trouvée")
        return 1
    if echecs:
        print(f"::error::{chemin} : {echecs} bibliothèque(s) native(s) n'acceptent pas les pages mémoire de 16 Ko")
        return 1
    print(f"{chemin} : les {vues} bibliothèques natives 64 bits acceptent les pages mémoire de 16 Ko")
    return 0


def mapbox(chemin: str, attendu: str) -> int:
    public = False
    secret = False
    with zipfile.ZipFile(chemin) as archive:
        for nom in archive.namelist():
            # Le code de l'appli (où le jeton est rangé à la fabrication), ses ressources et son manifeste.
            if nom.endswith((".dex", ".xml", ".arsc", ".json", ".properties", ".pb")):
                contenu = archive.read(nom)
                public = public or JETON_PUBLIC.search(contenu) is not None
                secret = secret or JETON_SECRET.search(contenu) is not None
    if secret:
        print(f"::error::{chemin} contient un jeton SECRET de Mapbox (« sk. ») : il ne doit jamais être mis dans une appli. À supprimer du compte Mapbox.")
        return 1
    if attendu == "oui" and not public:
        print(f"::error::{chemin} : le secret MAPBOX_ACCESS_TOKEN existe, mais le jeton n'est pas arrivé dans l'appli.")
        return 1
    if attendu != "oui" and public:
        print(f"::error::{chemin} contient un jeton Mapbox alors que la fabrication n'en a pas reçu : il vient du dépôt, où il ne doit pas être.")
        return 1
    print(f"{chemin} : cartes de Mapbox {'présentes (jeton public dans l’appli)' if public else 'absentes (pas de jeton)'}, comme attendu")
    return 0


def main() -> int:
    if len(sys.argv) == 3 and sys.argv[1] == "pages":
        return pages(sys.argv[2])
    if len(sys.argv) == 4 and sys.argv[1] == "mapbox":
        return mapbox(sys.argv[2], sys.argv[3])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
