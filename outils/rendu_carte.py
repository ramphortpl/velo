#!/usr/bin/env python3
"""Capture la carte de VeloGo sur l'émulateur, sans rien vérifier : sert à comparer le rendu (noms de rues,
repère de position) entre deux réglages graphiques de l'émulateur.

Usage : python3 outils/rendu_carte.py chemin/vers/app.apk prefixe
"""
import sys
import time

import essai_emulateur as e


def main():
    apk, prefix = sys.argv[1], sys.argv[2]
    e.os.makedirs(e.OUT, exist_ok=True)
    gps = e.Gps()
    result = e.run(["adb", "install", "-r", "-g", apk], timeout=240)
    e.log(result.stdout.decode("utf-8", "replace").strip()[-200:])
    gps.send()
    e.shell(f"am start -W -n {e.PACKAGE}/{e.ACTIVITY}", timeout=120)
    time.sleep(10)
    gps.stand(25)
    e.shot(f"{prefix}_carte")
    gps.ride(12)
    e.shot(f"{prefix}_carte_en_mouvement")
    e.log("GPU : " + " | ".join(line.strip()[-90:] for line in e.adb("logcat", "-d", "-s", "Mbgl:I", timeout=60).splitlines() if "GPU" in line))


if __name__ == "__main__":
    main()
