#!/usr/bin/env python3
"""Essai de bout en bout de VeloGo sur un émulateur Android (lancé par la CI GitHub).

Le script installe l'APK « essai » (le même code minifié que la version publiée), simule une sortie en envoyant
des positions GPS à l'émulateur, pilote l'interface comme le ferait un doigt, vérifie quelques valeurs et prend des
captures d'écran. Il sort en erreur si l'appli plante ou si une vérification échoue.

Usage : python3 outils/essai_emulateur.py chemin/vers/app.apk
"""
import math
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "fr.velogo.app.essai"
ACTIVITY = "fr.velogo.app.MainActivity"
OUT = "essai"
# Bord du lac d'Annecy : point de départ de la sortie simulée.
START_LAT, START_LON = 45.8992, 6.1294
SPEED_MPS = 7.0

failures = []
step_no = [0]


def run(args, timeout=90, check=False):
    return subprocess.run(args, capture_output=True, timeout=timeout, check=check)


def adb(*args, timeout=90):
    return run(["adb", *args], timeout=timeout).stdout.decode("utf-8", "replace")


def shell(command, timeout=90):
    return adb("shell", command, timeout=timeout)


def log(message):
    print(f"[essai] {message}", flush=True)


def fail(message):
    log(f"ÉCHEC : {message}")
    failures.append(message)


def shot(name):
    step_no[0] += 1
    path = os.path.join(OUT, f"{step_no[0]:02d}_{name}.png")
    data = run(["adb", "exec-out", "screencap", "-p"], timeout=60).stdout
    with open(path, "wb") as f:
        f.write(data)
    log(f"capture {path} ({len(data)} octets)")


def nodes():
    """Tous les éléments visibles : (texte, description, (x1, y1, x2, y2))."""
    for _ in range(3):
        shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        raw = run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], timeout=60).stdout.decode("utf-8", "replace")
        try:
            root = ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(1)
            continue
        out = []
        for node in root.iter("node"):
            match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if match:
                out.append((node.get("text", ""), node.get("content-desc", ""), tuple(int(v) for v in match.groups())))
        return out
    return []


def find(label, exact=True):
    for text, description, bounds in nodes():
        for value in (text, description):
            if (value == label) if exact else (label in value):
                return bounds
    return None


def wait_for(label, timeout=20, exact=True):
    deadline = time.time() + timeout
    while time.time() < deadline:
        bounds = find(label, exact)
        if bounds:
            return bounds
        time.sleep(1)
    return None


def tap(label, timeout=20, exact=True, required=True):
    bounds = wait_for(label, timeout, exact)
    if not bounds:
        if required:
            fail(f"élément introuvable : « {label} »")
        return False
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    shell(f"input tap {x} {y}")
    time.sleep(1.2)
    return True


def long_press(label, millis=1800):
    bounds = wait_for(label)
    if not bounds:
        fail(f"élément introuvable : « {label} »")
        return False
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    shell(f"input swipe {x} {y} {x} {y} {millis}")
    time.sleep(1.5)
    return True


def alive():
    return bool(shell(f"pidof {PACKAGE}").strip())


def check_alive(when):
    if not alive():
        fail(f"l'appli ne tourne plus ({when})")
        return False
    return True


class Gps:
    """Fait avancer une position simulée et l'envoie au téléphone.

    On passe par le « fournisseur de test » d'Android (cmd location) plutôt que par le GPS simulé de l'émulateur :
    ce dernier se bloque parfois au démarrage et entraîne tout le système avec lui. Limite : ces positions n'ont ni
    vitesse ni altitude ; l'appli déduit donc la vitesse du déplacement (l'altitude est couverte par les tests de calcul).
    """

    def __init__(self):
        self.lat, self.lon = START_LAT, START_LON
        self.distance = 0.0
        # Le droit « position fictive » est donné au shell d'adb, sous ses différentes identités possibles.
        for cible in ("com.android.shell", "shell", "root"):
            shell(f"appops set {cible} android:mock_location allow")
        for commande in (
            "cmd location providers add-test-provider gps --requiresSatellite --supportsSpeed --supportsBearing --supportsAltitude --powerRequirement 1",
            "cmd location providers set-test-provider-enabled gps true",
        ):
            retour = shell(commande + " 2>&1").strip()
            if retour:
                log(f"{commande.split(' --')[0]} : {retour[:300]}")

    def send(self):
        shell(f"cmd location providers set-test-provider-location gps --location {self.lat:.6f},{self.lon:.6f} --accuracy 4")

    def ride(self, seconds, bearing_deg=40.0):
        bearing = math.radians(bearing_deg)
        for _ in range(seconds):
            debut = time.time()
            self.lat += SPEED_MPS * math.cos(bearing) / 111_320.0
            self.lon += SPEED_MPS * math.sin(bearing) / (111_320.0 * math.cos(math.radians(self.lat)))
            self.distance += SPEED_MPS
            self.send()
            time.sleep(max(0.0, 1.0 - (time.time() - debut)))

    def stand(self, seconds):
        for _ in range(seconds):
            debut = time.time()
            self.send()
            time.sleep(max(0.0, 1.0 - (time.time() - debut)))


def read_km():
    """Lit la distance affichée (« 0,42 km ») sur l'écran guidon."""
    for text, description, _ in nodes():
        for value in (text, description):
            match = re.fullmatch(r"(\d+),(\d\d) km", value)
            if match:
                return int(match.group(1)) + int(match.group(2)) / 100.0
    return None


def main():
    apk = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    gps = Gps()

    log("installation")
    result = run(["adb", "install", "-r", "-g", apk], timeout=240)
    log(result.stdout.decode("utf-8", "replace").strip()[-200:])
    if b"Success" not in result.stdout:
        fail("installation de l'APK refusée")
        return
    adb("logcat", "-c")
    gps.send()

    log("démarrage de l'appli")
    shell(f"am start -W -n {PACKAGE}/{ACTIVITY}", timeout=120)
    time.sleep(10)
    gps.stand(4)
    shot("carte_au_demarrage")
    if not check_alive("après le démarrage"):
        return
    # Une sortie restée « en cours » (redémarrage imprévu de l'émulateur) : on la termine pour repartir d'un état connu.
    if find("Sortie interrompue"):
        log("une sortie interrompue traînait : on la termine")
        tap("Terminer")
        time.sleep(4)
        tap("Rouler", required=False)
    if not wait_for("Démarrer", 30):
        fail("le bouton « Démarrer » n'apparaît pas")
    if not wait_for("GPS ±", 25, exact=False):
        fail("aucune position GPS reçue par l'appli")

    log("démarrage d'une sortie")
    tap("Démarrer")
    # Première fois : explication sur l'économie de batterie.
    if wait_for("Plus tard", 6):
        shot("explication_batterie")
        tap("Plus tard")
    if not wait_for("Pause", 20):
        fail("l'enregistrement ne démarre pas (bouton « Pause » absent)")
    check_alive("après « Démarrer »")

    gps.ride(60)
    shot("guidon_en_roulant")
    gps.ride(40)
    km = read_km()
    expected = gps.distance / 1000.0
    log(f"distance affichée : {km} km ; distance simulée : {expected:.2f} km")
    if km is None:
        fail("distance illisible sur l'écran guidon")
    elif not (expected * 0.85 <= km <= expected * 1.1):
        fail(f"distance affichée {km} km trop loin des {expected:.2f} km simulés")

    log("carte pendant la sortie")
    tap("Carte")
    gps.ride(8)
    shot("carte_avec_trace")
    check_alive("sur la carte")

    log("pause automatique à l'arrêt")
    tap("Données")
    gps.stand(12)
    shot("guidon_a_l_arret")
    if not find("À L'ARRÊT"):
        fail("la pause automatique ne s'affiche pas à l'arrêt")
    gps.ride(15)

    log("pause manuelle puis reprise")
    tap("Pause")
    if not wait_for("Reprendre", 10):
        fail("le bouton « Reprendre » n'apparaît pas après « Pause »")
    shot("guidon_en_pause")
    tap("Reprendre")
    gps.ride(10)

    log("fin de la sortie")
    long_press("Terminer")
    time.sleep(6)
    shot("detail_de_la_sortie")
    check_alive("après « Terminer »")
    if not wait_for("DISTANCE", 15):
        fail("l'écran de détail de la sortie ne s'affiche pas")
    time.sleep(8)
    shot("detail_apres_recalage_altitude")

    log("historique")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(2)
    shot("historique")
    if not wait_for("Mes sorties", 10):
        fail("l'historique ne s'affiche pas")

    log("mise en arrière-plan puis retour (sauvegarde de l'état de l'interface)")
    shell("input keyevent KEYCODE_HOME")
    time.sleep(4)
    shell(f"am start -W -n {PACKAGE}/{ACTIVITY}", timeout=120)
    time.sleep(3)
    check_alive("au retour d'arrière-plan")

    log("réglages")
    tap("Réglages")
    time.sleep(1)
    shot("reglages")
    tap("Voir", required=False)
    time.sleep(1)
    shot("sources_et_licences")
    shell("input keyevent KEYCODE_BACK")

    log("fonds de carte")
    tap("Rouler")
    time.sleep(2)
    for layer, name in (("CyclOSM", "cyclosm"), ("Plan IGN", "plan_ign"), ("Photos aériennes", "photos_aeriennes"), ("Plan", "plan")):
        if tap("Changer de fond de carte"):
            tap(layer)
            time.sleep(9)
            shot(f"fond_{name}")
            check_alive(f"avec le fond {layer}")

    log("sortie interrompue (appli tuée pendant l'enregistrement)")
    tap("Démarrer")
    if wait_for("Plus tard", 4):
        tap("Plus tard")
    if wait_for("Pause", 15):
        gps.ride(30)
        shell(f"am force-stop {PACKAGE}")
        time.sleep(2)
        shell(f"am start -W -n {PACKAGE}/{ACTIVITY}", timeout=120)
        time.sleep(6)
        shot("sortie_interrompue")
        if wait_for("Sortie interrompue", 10):
            tap("Terminer")
            time.sleep(5)
            shot("sortie_interrompue_enregistree")
        else:
            fail("la sortie interrompue n'est pas proposée au redémarrage")
    else:
        fail("la seconde sortie ne démarre pas")
    check_alive("à la fin")


def finish():
    crash = adb("logcat", "-d", "-b", "crash", timeout=60)
    with open(os.path.join(OUT, "logcat.txt"), "w", encoding="utf-8") as f:
        f.write(adb("logcat", "-d", "-v", "time", timeout=120))
    with open(os.path.join(OUT, "plantages.txt"), "w", encoding="utf-8") as f:
        f.write(crash)
    if PACKAGE in crash or "fr.velogo" in crash:
        fail("l'appli a planté : voir essai/plantages.txt")
        print(crash[-6000:])
    with open(os.path.join(OUT, "resultat.txt"), "w", encoding="utf-8") as f:
        f.write("OK\n" if not failures else "ÉCHECS :\n" + "\n".join(f"- {m}" for m in failures) + "\n")
    if failures:
        log(f"{len(failures)} vérification(s) en échec :")
        for message in failures:
            log(f"  - {message}")
        sys.exit(1)
    log("tout est bon")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001 - on veut toujours les journaux, quoi qu'il arrive
        fail(f"erreur du script : {error!r}")
    finish()
