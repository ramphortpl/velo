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
# Corte, au centre de la Corse : point de départ des sorties simulées. La Corse est la plus petite région de
# cartes hors ligne (80 Mo) : on peut la télécharger pendant l'essai.
START_LAT, START_LON = 42.3060, 9.1500
REGION = "Corse"
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


def wait_for_pattern(pattern, timeout=20):
    """Attend un élément dont le texte ou la description correspond à l'expression régulière."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for text, description, bounds in nodes():
            for value in (text, description):
                if re.fullmatch(pattern, value):
                    return value
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


def read_altitude():
    """Lit l'altitude affichée sur l'écran guidon : la plus grande valeur en mètres (l'autre est le dénivelé)."""
    values = []
    for text, description, _ in nodes():
        for value in (text, description):
            match = re.fullmatch(r"(\d[\d\u202f ]*) m", value)
            if match:
                values.append(int(re.sub(r"\D", "", match.group(1))))
    return max(values) if values else None


def weather(gps):
    """Météo : la pastille de la carte, puis la feuille détaillée.

    Simple constat, jamais un échec : le service météo limite les demandes par adresse IP, et celles des machines
    de la CI sont partagées avec beaucoup d'autres. S'il refuse de répondre, l'appli n'affiche rien, comme prévu.
    """
    log("météo")
    chip = None
    deadline = time.time() + 25
    while time.time() < deadline and chip is None:
        for text, description, bounds in nodes():
            if description.startswith("Météo :"):
                chip = (description, bounds)
                break
        gps.ride(2)
    if chip is None:
        log("météo : rien d'affiché (service météo injoignable ou quota dépassé depuis la CI)")
        return
    log(f"météo affichée : {chip[0]}")
    x, y = (chip[1][0] + chip[1][2]) // 2, (chip[1][1] + chip[1][3]) // 2
    shell(f"input tap {x} {y}")
    time.sleep(2)
    if wait_for("Heure par heure", 8):
        shot("meteo")
    else:
        shot("meteo_incomplete")
        log("météo : la feuille détaillée ne montre pas les prévisions heure par heure")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    check_alive("après la météo")


def route_by_address():
    """Itinéraire vers une adresse cherchée en ligne, calculé sur le téléphone, puis début de guidage."""
    log("itinéraire : recherche d'adresse, calcul, guidage")
    if not tap("Itinéraire"):
        return
    if not wait_for("Calculer l'itinéraire", 10):
        fail("le panneau d'itinéraire ne s'ouvre pas")
        return
    tap("Choisir l'arrivée")
    time.sleep(2)
    shell('input text "gare%sde%scorte"')
    if not wait_for("Gare de Corte", 25):
        shot("itineraire_recherche_sans_resultat")
        fail("la recherche d'adresse ne propose pas la gare de Corte")
        shell("input keyevent KEYCODE_BACK")
        shell("input keyevent KEYCODE_BACK")
        tap("Fermer l'itinéraire", required=False)
        return
    shot("itineraire_recherche")
    tap("Gare de Corte")
    time.sleep(1)
    tap("Calculer l'itinéraire")
    found = wait_for_pattern(r"\d itinéraires?", 60)
    shot("itineraire_propositions")
    if not found:
        fail("aucun itinéraire calculé vers la gare de Corte")
        tap("Fermer l'itinéraire", required=False)
        return
    log(f"propositions : {found}")
    tap("Démarrer le guidage")
    if wait_for("Pas maintenant", 6):
        tap("Pas maintenant")
    if not wait_for("Guidage :", 10, exact=False):
        fail("le bandeau de guidage n'apparaît pas")
    time.sleep(3)
    shot("guidage_sur_la_carte")
    tap("Arrêter le guidage")
    check_alive("après le guidage")


def offline_maps(gps):
    """Télécharge la carte d'une région, l'affiche, puis vérifie que tout marche en mode avion."""
    log("cartes hors ligne : téléchargement d'une région")
    tap("Réglages")
    time.sleep(1)
    # L'écran des réglages a pu rester défilé vers le bas : on remonte en haut de page.
    for _ in range(3):
        shell("input swipe 540 700 540 2000 250")
        time.sleep(0.6)
    tap("Ouvrir")
    if not wait_for("Cartes hors ligne", 10):
        fail("l'écran des cartes hors ligne ne s'ouvre pas")
        return
    if not wait_for(f"Télécharger {REGION}", 40):
        shot("cartes_liste_indisponible")
        fail("la liste des cartes hors ligne ne s'affiche pas (catalogue introuvable ?)")
        shell("input keyevent KEYCODE_BACK")
        return
    shot("cartes_hors_ligne_liste")
    tap(f"Télécharger {REGION}")
    time.sleep(5)
    shot("cartes_hors_ligne_telechargement")
    installed = False
    deadline = time.time() + 420
    while time.time() < deadline:
        if find(f"Supprimer {REGION}"):
            installed = True
            break
        if find("Réessayer"):
            break
        time.sleep(4)
    shot("cartes_hors_ligne_installee")
    if not installed:
        fail("la carte hors ligne n'a pas été installée (téléchargement trop long ou en échec)")
        shell("input keyevent KEYCODE_BACK")
        return
    log("carte hors ligne installée")
    if not tap("Utiliser la carte hors ligne", timeout=10):
        return
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1)
    tap("Rouler")
    gps.stand(10)
    shot("carte_velo_hors_ligne")
    check_alive("avec la carte hors ligne")

    route_by_address()

    log("mode avion : la carte et l'altitude doivent fonctionner sans réseau")
    shell("cmd connectivity airplane-mode enable")
    try:
        time.sleep(3)
        shell(f"am force-stop {PACKAGE}")
        time.sleep(2)
        shell(f"am start -W -n {PACKAGE}/{ACTIVITY}", timeout=120)
        gps.stand(12)
        shot("mode_avion_carte")
        if not check_alive("au redémarrage en mode avion"):
            return

        log("mode avion : itinéraire vers un point touché sur la carte, puis guidage avec enregistrement")
        guided = False
        if tap("Itinéraire"):
            time.sleep(2)
            # Appui long sur la carte, quelques centaines de mètres au nord de la position.
            shell("input swipe 540 640 540 640 1400")
            time.sleep(1)
            if find("Aller ici"):
                tap("Aller ici")
            if wait_for("Calculer l'itinéraire", 10):
                tap("Calculer l'itinéraire")
                found = wait_for_pattern(r"\d itinéraires?", 60)
                shot("mode_avion_itineraire")
                if found:
                    tap("Démarrer le guidage")
                    # Le guidage propose d'enregistrer la sortie : on accepte, c'est elle qui sert à la suite.
                    if wait_for("Enregistrer aussi la sortie ?", 6):
                        tap("Enregistrer")
                        guided = True
                else:
                    fail("aucun itinéraire calculé hors ligne")
                    tap("Fermer l'itinéraire", required=False)
            else:
                fail("le point touché sur la carte n'a pas été pris comme arrivée")
                tap("Fermer l'itinéraire", required=False)
        if not guided:
            tap("Démarrer")
        if wait_for("Plus tard", 4):
            tap("Plus tard")
        if not wait_for("Pause", 15):
            fail("la sortie en mode avion ne démarre pas")
            return
        if guided and not wait_for("Guidage :", 10, exact=False):
            fail("le guidage hors ligne n'est pas affiché pendant la sortie")
        gps.ride(45, bearing_deg=200.0)
        altitude = read_altitude()
        log(f"altitude affichée en mode avion : {altitude} m")
        shot("mode_avion_guidon")
        # Le GPS simulé ne donne aucune altitude : si un nombre s'affiche, il vient du relief hors ligne.
        if altitude is None or not (300 <= altitude <= 800):
            fail(f"altitude hors ligne absente ou invraisemblable à Corte : {altitude}")
        tap("Carte")
        gps.ride(6, bearing_deg=200.0)
        shot("mode_avion_carte_en_roulant")
        tap("Arrêter le guidage", timeout=3, required=False)
        tap("Données")
        long_press("Terminer")
        time.sleep(8)
        shot("mode_avion_detail_de_la_sortie")
        if not wait_for("Altitude issue du relief hors ligne", 15):
            fail("la sortie n'a pas été recalée sur le relief hors ligne")
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1)
        tap("Rouler")
    finally:
        shell("cmd connectivity airplane-mode disable")
        time.sleep(8)


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
    weather(gps)

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
    # La page s'allonge au fil des jalons : on la fait défiler jusqu'à trouver le bouton, où qu'il soit.
    for _ in range(12):
        if find("Voir"):
            break
        shell("input swipe 540 1900 540 700 300")
        time.sleep(0.7)
    shot("reglages_bas_de_page")
    if tap("Voir", timeout=5, required=False):
        time.sleep(1)
        shot("sources_et_licences")
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1)
    else:
        fail("le bouton « Voir » des sources et licences est introuvable")

    log("fonds de carte")
    tap("Rouler")
    time.sleep(2)
    for layer, name in (("CyclOSM", "cyclosm"), ("Plan IGN", "plan_ign"), ("Photos aériennes", "photos_aeriennes"), ("Plan", "plan")):
        if tap("Changer de fond de carte"):
            tap(layer)
            time.sleep(9)
            shot(f"fond_{name}")
            check_alive(f"avec le fond {layer}")

    offline_maps(gps)

    log("sortie interrompue (appli tuée pendant l'enregistrement)")
    tap("Rouler", required=False)
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
