#!/usr/bin/env python3
"""Essai de bout en bout de VeloGo sur un émulateur Android (lancé par la CI GitHub).

Le script installe l'APK « essai » (le même code minifié que la version publiée), simule une sortie en envoyant
des positions GPS à l'émulateur, pilote l'interface comme le ferait un doigt, vérifie quelques valeurs et prend des
captures d'écran. Il sort en erreur si l'appli plante ou si une vérification échoue.

Avec un second APK (la version destinée à Google Play), il l'installe à la fin par-dessus le premier et vérifie
qu'elle démarre, garde les sorties, calcule un itinéraire et ne propose aucune mise à jour hors du magasin. La CI
lui donne l'APK que l'outil de Google (bundletool) tire du fichier AAB, comme le fait le Play Store, et non un APK
construit directement : c'est ce chemin-là qui est essayé.

Usage : python3 outils/essai_emulateur.py chemin/vers/app-github.apk [chemin/vers/app-play.apk]
"""
import math
import os
import re
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "fr.velogo.app.essai"
ACTIVITY = "fr.velogo.app.MainActivity"
OUT = "essai"
# Écrit quand l'émulateur n'a aucun réseau utilisable : la CI recommence alors l'essai sur un émulateur neuf.
NO_NETWORK = os.path.join(OUT, "sans_reseau.txt")
# Corte, au centre de la Corse : point de départ des sorties simulées. La Corse est la plus petite région de
# cartes hors ligne (80 Mo) : on peut la télécharger pendant l'essai.
START_LAT, START_LON = 42.3060, 9.1500
REGION = "Corse"
SPEED_MPS = 7.0
# Sur Android 12 et suivants, l'appli demande la position approchée du téléphone au fournisseur « fused ».
APPROXIMATE_PROVIDER = "fused"
APPROXIMATE_ACCURACY_M = 40

failures = []
step_no = [0]
# Les fenêtres d'erreur d'Android déjà rencontrées (pour ne les signaler qu'une fois chacune).
system_dialogs_seen = []


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


def dump():
    """L'arbre des éléments affichés, tel quel, ou None si l'écran n'a pas pu être lu."""
    for _ in range(3):
        shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        raw = run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], timeout=60).stdout.decode("utf-8", "replace")
        try:
            return ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(1)
    return None


def system_dialog(root):
    """Une fenêtre d'Android posée devant tout le reste : « … isn't responding » ou « … keeps stopping ».

    Renvoie (son titre, le cadre du bouton qui la ferme), ou None. Sur l'émulateur de la CI, l'écran d'accueil
    (« Pixel Launcher ») met parfois si longtemps à démarrer qu'Android propose de le fermer : cette fenêtre
    reste alors devant l'appli, et plus rien de ce qu'on cherche n'est visible (fabrication n° 21)."""
    title = None
    buttons = {}
    for node in root.iter("node"):
        text = node.get("text", "")
        if re.search(r"isn't responding|keeps stopping|has stopped", text):
            title = text
        elif text in ("Wait", "Close app", "OK"):
            buttons[text] = bounds_of(node)
    if title is None:
        return None
    # On attend une appli qui rame ; on ferme la fenêtre d'une appli qui a planté.
    order = ("Wait", "OK", "Close app") if "responding" in title else ("Close app", "OK")
    for name in order:
        if buttons.get(name):
            return title, buttons[name]
    return title, None


def screen():
    """L'arbre des éléments affichés, ou None si l'écran n'a pas pu être lu. Les fenêtres d'erreur d'Android
    (« … isn't responding ») sont écartées au passage : si c'est VeloGo qui ne répond plus, c'est un échec."""
    root = None
    for _ in range(5):
        root = dump()
        if root is None:
            return None
        blocking = system_dialog(root)
        if blocking is None:
            return root
        title, button = blocking
        if title not in system_dialogs_seen:
            system_dialogs_seen.append(title)
            log(f"fenêtre d'Android écartée : « {title} »")
            if title.startswith("VeloGo"):
                fail(f"Android a affiché « {title} »")
        if button:
            shell(f"input tap {(button[0] + button[2]) // 2} {(button[1] + button[3]) // 2}")
        else:
            shell("input keyevent KEYCODE_BACK")
        time.sleep(1.5)
    return root


def bounds_of(node):
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(int(v) for v in match.groups()) if match else None


def pixels():
    """L'écran, point par point : (largeur, hauteur, octets rouge-vert-bleu-opacité), ou None s'il n'a pas pu être lu.

    Sans l'option « -p », screencap renvoie les points tels quels, derrière un court en-tête : pas besoin de
    savoir lire une image PNG."""
    raw = run(["adb", "exec-out", "screencap"], timeout=60).stdout
    if len(raw) < 16:
        log(f"lecture de l'écran : {len(raw)} octets reçus")
        return None
    width, height = struct.unpack("<II", raw[:8])
    header = len(raw) - width * height * 4
    if width <= 0 or height <= 0 or header not in (12, 16):
        log(f"lecture de l'écran : {len(raw)} octets pour {width} × {height} points, format inattendu")
        return None
    return width, height, raw[header:]


def logo_colours(top=0.0, bottom=1.0):
    """Compte, dans une bande de l'écran, les points aux trois couleurs du logo : (vert foncé, vert vif, rose)."""
    screen_pixels = pixels()
    if screen_pixels is None:
        return None
    width, height, data = screen_pixels
    dark = bright = pink = 0
    for y in range(int(height * top), int(height * bottom), 2):
        row = y * width * 4
        for x in range(0, width, 2):
            r, g, b = data[row + x * 4], data[row + x * 4 + 1], data[row + x * 4 + 2]
            if r < 40 and 45 <= g <= 115 and 25 <= b <= 95:
                dark += 1
            elif r < 60 and g > 165 and 100 <= b <= 195:
                bright += 1
            elif r > 195 and g < 75 and 80 <= b <= 180:
                pink += 1
    return dark, bright, pink


def nodes():
    """Tous les éléments visibles : (texte, description, (x1, y1, x2, y2))."""
    root = screen()
    out = []
    if root is not None:
        for node in root.iter("node"):
            bounds = bounds_of(node)
            if bounds:
                out.append((node.get("text", ""), node.get("content-desc", ""), bounds))
    return out


def guidance_banner():
    """Ce que dit le bandeau de guidage (sa description, « Guidage : 200 m, À gauche · Rue de la Gare »), ou None."""
    for _, description, _ in nodes():
        if description.startswith("Guidage :"):
            return description
    return None


def note_guidance(when):
    """Relève le bandeau de guidage dans le journal. Un simple constat : selon l'endroit, la première consigne peut
    être double (« À gauche, puis à droite ») ou tomber sur une voie sans nom, et n'afficher aucun nom de rue."""
    banner = guidance_banner()
    if banner is None:
        log(f"bandeau de guidage {when} : absent")
    elif " · " in banner:
        log(f"bandeau de guidage {when} : « {banner} » (nom de rue affiché)")
    else:
        log(f"bandeau de guidage {when} : « {banner} » (sans nom de rue)")
    return banner


def text_field():
    """Le champ de saisie affiché : (son contenu, son cadre), ou None s'il n'y en a pas."""
    root = screen()
    if root is not None:
        for node in root.iter("node"):
            bounds = bounds_of(node)
            if bounds and node.get("class") == "android.widget.EditText":
                return node.get("text", ""), bounds
    return None


def wait_keyboard(timeout=8):
    """Attend que le clavier soit affiché : les touches envoyées pendant qu'il démarre se perdent."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if "mInputShown=true" in shell("dumpsys input_method"):
            break
        time.sleep(0.5)
    time.sleep(1.5)


def type_text(text, attempts=6):
    """Écrit [text] dans le champ de saisie et vérifie que tout y est.

    Sur l'émulateur, une partie des touches peut se perdre (clavier qui démarre, machine chargée) : on relit donc
    le champ, on complète ce qui manque et on efface ce qui ne correspond pas, jusqu'à y lire le texte voulu.
    """
    wanted = text.lower()
    cleared = None
    for attempt in range(attempts):
        field = text_field()
        if field is None:
            time.sleep(1)
            continue
        current = field[0]
        if current.lower() == wanted:
            return True
        keep = len(current) if wanted.startswith(current.lower()) else 0
        if current and not keep and current != cleared:
            # Autre chose dans le champ (un ancien nom, une saisie abîmée) : on l'efface, puis on relit.
            shell("input keyevent KEYCODE_MOVE_END")
            shell("input keyevent " + " ".join(["KEYCODE_DEL"] * min(len(current) + 2, 80)))
            cleared = current
            time.sleep(1)
            continue
        if attempt and keep:
            log(f"saisie incomplète (« {current} ») : on complète")
        shell('input text "%s"' % text[keep:].replace(" ", "%s"))
        time.sleep(1.5)
    field = text_field()
    return field is not None and field[0].lower() == wanted


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


def start_video(name, seconds=45):
    """Commence à filmer l'écran (au plus [seconds] secondes). Simple confort : si l'émulateur ne sait pas filmer,
    l'essai continue sans vidéo."""
    try:
        process = subprocess.Popen(
            ["adb", "shell", "screenrecord", "--time-limit", str(seconds), "--bit-rate", "4000000", f"/sdcard/{name}.mp4"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
    except OSError:
        return None
    time.sleep(1.5)
    return name, process


def stop_video(video):
    """Arrête le film et le range avec les captures d'écran."""
    if video is None:
        return
    name, process = video
    # Interrompu poliment, l'enregistreur termine son fichier ; tué net, il le laisserait illisible.
    shell("pkill -INT screenrecord")
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
    time.sleep(2)
    target = os.path.join(OUT, f"{name}.mp4")
    run(["adb", "pull", f"/sdcard/{name}.mp4", target], timeout=120)
    size = os.path.getsize(target) if os.path.exists(target) else 0
    if size > 20_000:
        log(f"vidéo enregistrée : {name}.mp4 ({size // 1024} Ko)")
    else:
        log("pas de vidéo : l'émulateur n'a pas su filmer l'écran")


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
        # L'endroit d'où un itinéraire vers un point touché sur la carte a été calculé (voir play_route).
        self.route_start = None
        # Le droit « position fictive » est donné au shell d'adb, sous ses différentes identités possibles.
        for cible in ("com.android.shell", "shell", "root"):
            shell(f"appops set {cible} android:mock_location allow")
        # Le GPS, et le fournisseur « fused » : c'est lui qui donne à l'appli la position approchée du téléphone
        # (Wi-Fi, réseau) tant que le GPS se tait. Simulé lui aussi, il ne dit que ce que l'essai lui fait dire.
        for commande in (
            "cmd location providers add-test-provider gps --requiresSatellite --supportsSpeed --supportsBearing --supportsAltitude --powerRequirement 1",
            "cmd location providers set-test-provider-enabled gps true",
            f"cmd location providers add-test-provider {APPROXIMATE_PROVIDER} --requiresNetwork --powerRequirement 1",
            f"cmd location providers set-test-provider-enabled {APPROXIMATE_PROVIDER} true",
        ):
            retour = shell(commande + " 2>&1").strip()
            if retour:
                log(f"{commande.split(' --')[0]} : {retour[:300]}")

    def send(self):
        shell(f"cmd location providers set-test-provider-location gps --location {self.lat:.6f},{self.lon:.6f} --accuracy 4")

    def wait_for(self, label, timeout=20, exact=True):
        """Attend un élément à l'écran en continuant d'envoyer la position : l'appli ne dit que le GPS fonctionne
        que tant qu'il parle (six secondes de silence, et elle annonce qu'il cherche)."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.send()
            bounds = find(label, exact)
            if bounds:
                return bounds
        return None

    def send_approximate(self):
        """Une position approchée, comme le téléphone en déduit du Wi-Fi : à 150 m du vrai départ, à 40 m près."""
        shell(
            f"cmd location providers set-test-provider-location {APPROXIMATE_PROVIDER} "
            f"--location {self.lat + 0.00135:.6f},{self.lon:.6f} --accuracy {APPROXIMATE_ACCURACY_M}"
        )

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


def approximate_position(gps):
    """GPS muet (il cherche ses satellites) : l'appli doit montrer la position approchée du téléphone, le dire
    clairement, et ne rien casser quand on lui demande de relancer le GPS."""
    log("GPS muet : position approchée du téléphone")
    if not wait_for("Recherche GPS", 30):
        fail("la pastille « Recherche GPS » n'apparaît pas alors que le GPS n'a encore rien donné")
        shot("gps_pastille_absente")
        return
    # L'appli laisse d'abord quatre secondes au GPS ; ensuite la position approchée s'affiche.
    for _ in range(9):
        gps.send_approximate()
        time.sleep(1)
    shot("carte_position_approchee")
    if not tap("Recherche GPS"):
        return
    texte = None
    limite = time.time() + 12
    while time.time() < limite and texte is None:
        gps.send_approximate()
        for text, _, _ in nodes():
            if "position approchée" in text:
                texte = text
        time.sleep(1)
    shot("gps_feuille_position_approchee")
    if texte is None:
        fail("GPS muet : la feuille « Signal GPS » n'annonce pas de position approchée")
    elif str(APPROXIMATE_ACCURACY_M) not in texte:
        fail(f"position approchée : précision absente du texte (« {texte[:160]} »)")
    else:
        log(f"position approchée annoncée : « {texte[:110]}… »")
    # « Relancer le GPS » refait l'écoute et envoie des ordres au GPS du téléphone : rien ne doit planter.
    if tap("Relancer le GPS", required=False):
        time.sleep(3)
        check_alive("après « Relancer le GPS »")
        if not find("Le GPS cherche ses satellites"):
            fail("après « Relancer le GPS », la feuille ne dit plus que le GPS cherche")
    else:
        fail("bouton « Relancer le GPS » absent de la feuille")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    if find("Le GPS cherche ses satellites"):
        fail("la feuille « Signal GPS » ne se referme pas")


def gps_sheet_when_fixed(gps):
    """Le GPS répond : la feuille le dit, sans rien proposer à corriger."""
    bounds = gps.wait_for("GPS ±", 10, exact=False)
    if not bounds:
        fail("la pastille du GPS n'affiche plus sa précision")
        return
    shell(f"input tap {(bounds[0] + bounds[2]) // 2} {(bounds[1] + bounds[3]) // 2}")
    if gps.wait_for("Le GPS fonctionne bien", 12):
        log("feuille « Signal GPS » : le GPS fonctionne bien")
    else:
        fail("la feuille « Signal GPS » ne dit pas que le GPS fonctionne")
    shot("gps_feuille_bon_signal")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)


def app_icon():
    """La fiche de l'appli dans les réglages d'Android : l'icône y est découpée comme sur l'écran d'accueil.
    Elle doit montrer le logo, reconnu à ses trois couleurs."""
    log("icône de l'appli (fiche des réglages d'Android)")
    shell(f"am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{PACKAGE}")
    if not wait_for("VeloGo", 20, exact=False):
        shot("fiche_de_l_appli_absente")
        log("la fiche de l'appli ne s'est pas ouverte : icône non vérifiée")
        shell("am force-stop com.android.settings")
        return
    time.sleep(2)
    shot("icone_de_l_appli")
    colours = logo_colours(0.0, 0.6)
    if colours is None:
        # La lecture de l'écran échoue parfois sur l'émulateur : une seconde tentative, après un instant.
        time.sleep(3)
        colours = logo_colours(0.0, 0.6)
    if colours is None:
        log("écran illisible : icône non vérifiée")
    else:
        dark, bright, pink = colours
        log(f"couleurs du logo dans l'icône : vert foncé {dark}, vert vif {bright}, rose {pink} (un point sur quatre)")
        if dark < 40 or bright < 15 or pink < 8:
            fail("l'icône de l'appli ne montre pas le logo (ses trois couleurs sont absentes de la fiche de l'appli)")
    shell("am force-stop com.android.settings")
    time.sleep(1)


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


def segment_from_ride():
    """Depuis le détail de la sortie qu'on vient de faire : création d'un segment, qui doit s'y retrouver lui-même."""
    log("segment : création à partir de la sortie")
    if not tap("Plus d'actions", timeout=8, required=False):
        fail("le menu de la sortie est introuvable")
        return
    if not tap("Créer un segment…", timeout=8, required=False):
        fail("« Créer un segment » est absent du menu de la sortie")
        shell("input keyevent KEYCODE_BACK")
        return
    if not wait_for("Nouveau segment", 8):
        fail("l'écran de création d'un segment ne s'ouvre pas")
        return
    shot("segment_creation")
    # Le nom est proposé d'office : il n'y a qu'à valider le morceau choisi par défaut (le tiers central).
    # (Un glissement vers le haut d'abord, au cas où le bouton serait sous le bas de l'écran.)
    shell("input swipe 540 2150 540 1500 300")
    time.sleep(1)
    tap("Créer le segment")
    if not wait_for("PASSAGES", 15):
        shot("segment_non_cree")
        fail("le segment créé ne s'affiche pas")
        return
    # La recherche dans les sorties déjà enregistrées se fait en tâche de fond : on lui laisse le temps.
    deadline = time.time() + 30
    while time.time() < deadline and (find("Recherche de tes passages", exact=False) or find("Recherche dans les autres", exact=False)):
        time.sleep(1)
    shot("segment_detail")
    if find("Aucun passage", exact=False):
        fail("la sortie d'origine n'a pas été reconnue sur le segment qu'on vient d'en tirer")
    check_alive("après la création d'un segment")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(2)
    # De retour sur la sortie : elle affiche maintenant son passage sur le segment.
    if not wait_for("Premier passage", 10):
        shot("sortie_sans_segment")
        fail("le détail de la sortie ne montre pas son passage sur le segment")


def statistics():
    """Depuis l'historique : les statistiques, puis la liste des segments."""
    log("statistiques et segments")
    if not tap("Statistiques", timeout=8, required=False):
        fail("le bouton « Statistiques » est absent de l'historique")
        return
    if not wait_for("Depuis le début", 10):
        fail("l'écran des statistiques ne s'affiche pas")
    shot("statistiques")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    if not tap("Segments", timeout=8, required=False):
        fail("le bouton « Segments » est absent de l'historique")
        return
    if not wait_for("Mes segments", 10) or not wait_for("RECORD", 10):
        fail("la liste des segments ne montre pas le segment créé, avec son record")
    shot("segments")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    check_alive("après les statistiques")


def route_by_address(gps):
    """Itinéraire vers une adresse cherchée en ligne, calculé sur le téléphone, affiche à partager, puis guidage."""
    log("itinéraire : recherche d'adresse, calcul, guidage")
    if not tap("Itinéraire"):
        return
    if not wait_for("Calculer l'itinéraire", 10):
        fail("le panneau d'itinéraire ne s'ouvre pas")
        return
    tap("Choisir l'arrivée")
    wait_keyboard()
    if not type_text("gare de corte"):
        log("la saisie de l'adresse n'est pas arrivée entière dans le champ de recherche")
    found_place = wait_for("Gare de Corte", 25)
    # Le service d'adresses ne répond pas toujours du premier coup aux machines de la CI (fabrication n° 28) :
    # l'appli propose alors de réessayer, et on le fait comme le ferait quelqu'un, deux fois au plus.
    for _ in range(2):
        if found_place or not find("Réessayer"):
            break
        log("le service d'adresses n'a pas répondu : nouvel essai")
        time.sleep(4)
        tap("Réessayer", required=False)
        found_place = wait_for("Gare de Corte", 25)
    if not found_place:
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
    # Le panneau arrive replié : la carte se cadre sur les itinéraires, en grand.
    time.sleep(3)
    shot("itineraire_propositions")
    if not found:
        fail("aucun itinéraire calculé vers la gare de Corte")
        tap("Fermer l'itinéraire", required=False)
        return
    log(f"propositions : {found}")

    # Le détail se déplie (profil, nature des voies), puis se replie.
    if tap("Détails", timeout=6, required=False):
        if not wait_for("Voir la carte", 6):
            fail("le détail des itinéraires ne se déplie pas")
        time.sleep(2)
        shot("itineraire_details")
        save_route()
        tap("Voir la carte", timeout=6, required=False)
        if not wait_for("Détails", 6):
            fail("le panneau d'itinéraire ne se replie pas")
        time.sleep(2)
    else:
        fail("le bouton « Détails » du panneau d'itinéraire est absent")

    route_poster()

    tap("Démarrer le guidage")
    if wait_for("Pas maintenant", 6):
        tap("Pas maintenant")
    if not wait_for("Guidage :", 10, exact=False):
        fail("le bandeau de guidage n'apparaît pas")
    # À l'arrêt (le GPS continue d'envoyer la même position) : la flèche montre déjà où part l'itinéraire.
    gps.stand(5)
    note_guidance("au départ")
    shot("guidage_au_depart")
    # En roulant : la carte suit dans le sens de la marche, inclinée, la flèche vers le bas de l'écran.
    gps.ride(12, bearing_deg=200.0)
    note_guidance("en roulant")
    shot("guidage_sur_la_carte")
    # On déplace la carte : le bouton « Recentrer » doit apparaître, et ramener à la vue de guidage.
    shell("input swipe 540 1100 300 800 400")
    time.sleep(1)
    if wait_for("Recentrer", 6):
        shot("guidage_carte_deplacee")
        tap("Recentrer")
        gps.ride(4, bearing_deg=200.0)
        if find("Recentrer"):
            fail("le bouton « Recentrer » reste affiché après avoir recentré la carte")
    else:
        shot("guidage_sans_recentrer")
        fail("le bouton « Recentrer » n'apparaît pas quand on déplace la carte pendant le guidage")
    tap("Arrêter le guidage")
    time.sleep(2)
    shot("carte_apres_guidage")
    check_alive("après le guidage")
    saved_routes()


def loop_route():
    """Une boucle, sans réseau : départ et arrivée au même endroit, une étape placée par un appui long sur la carte.

    Le panneau d'itinéraire est ouvert, sans arrivée. À la fin, il est remis dans cet état."""
    log("itinéraire : boucle (retour au départ)")
    if not tap("Boucle", timeout=8, required=False):
        shot("boucle_bouton_absent")
        fail("le bouton « Boucle » est absent du panneau d'itinéraire")
        return
    if not wait_for("Retour au départ (boucle)", 6):
        fail("après « Boucle », l'arrivée n'est pas « Retour au départ »")
        return
    # En boucle, un appui long sur la carte ajoute une étape (ici quelques centaines de mètres au nord).
    shell("input swipe 540 640 540 640 1400")
    if wait_for("ÉTAPE 1", 8):
        tap("Calculer l'itinéraire")
        found = wait_for_pattern(r"\d itinéraires?", 60)
        time.sleep(3)
        shot("itineraire_boucle")
        if not found:
            fail("aucune boucle calculée")
        else:
            log(f"boucle : {found}")
            # Une boucle qui s'arrête à son retour n'est pas un trajet « qui repasse par son départ puis continue ».
            if find("Repasse par le départ", exact=False):
                fail("une simple boucle est annoncée comme repassant par son départ")
            check_alive("après le calcul d'une boucle")
        tap("Modifier les points", timeout=6, required=False)
        wait_for("Calculer l'itinéraire", 8)
        tap("Retirer l'étape 1", timeout=6, required=False)
    else:
        shot("boucle_sans_etape")
        fail("en boucle, l'appui long sur la carte n'ajoute pas d'étape")
    tap("Ne plus revenir au départ", timeout=6, required=False)
    if not wait_for("Choisir l'arrivée", 6):
        fail("le panneau d'itinéraire ne revient pas à « Choisir l'arrivée » après la boucle")


def save_route():
    """Dans le détail déplié : « Enregistrer cet itinéraire », pour le retrouver dans l'onglet « Trajets »."""
    for _ in range(4):
        if find("Enregistrer cet itinéraire"):
            break
        # Le bouton est en bas du détail : on fait défiler le panneau.
        shell("input swipe 540 1700 540 1000 300")
        time.sleep(1)
    if not tap("Enregistrer cet itinéraire", timeout=4, required=False):
        shot("itineraire_sans_bouton_enregistrer")
        fail("le bouton « Enregistrer cet itinéraire » est introuvable dans le détail")
        return
    if not wait_for("Enregistrer l'itinéraire", 6):
        fail("la fenêtre d'enregistrement de l'itinéraire ne s'ouvre pas")
        return
    tap("Enregistrer")
    if not wait_for("Itinéraire enregistré", 8, exact=False):
        log("pas de message de confirmation après l'enregistrement de l'itinéraire")
    check_alive("après l'enregistrement d'un itinéraire")


def saved_routes():
    """L'onglet « Trajets » : l'itinéraire enregistré s'y retrouve, se renomme et se rouvre sur la carte."""
    log("onglet « Trajets » : retrouver, renommer et rouvrir l'itinéraire enregistré")
    if not tap("Trajets", timeout=8, required=False):
        fail("l'onglet « Trajets » est absent de la barre du bas")
        return
    if not wait_for("Mes trajets", 10):
        shot("trajets_absent")
        fail("l'écran des trajets enregistrés ne s'affiche pas")
        tap("Rouler", required=False)
        return
    if not wait_for("Enregistré aujourd'hui", 10, exact=False):
        shot("trajets_vide")
        fail("l'itinéraire enregistré n'apparaît pas dans l'onglet « Trajets »")
        tap("Rouler", required=False)
        return
    time.sleep(2)
    shot("trajets")
    # La distance annoncée par la liste : le trajet rouvert devra afficher exactement la même.
    distance = wait_for_pattern(r"\d+(,\d)? k?m", 4)

    # Renommer, depuis le menu de la ligne.
    name = "Boucle essai"
    if tap("Actions pour", timeout=6, exact=False, required=False) and tap("Renommer", timeout=6, required=False):
        if wait_for("Renommer le trajet", 6):
            field = text_field()
            if field:
                x, y = (field[1][0] + field[1][2]) // 2, (field[1][1] + field[1][3]) // 2
                shell(f"input tap {x} {y}")
                wait_keyboard()
            if not type_text(name):
                log("le nouveau nom n'est pas arrivé entier dans le champ")
            tap("Enregistrer")
            if wait_for(name, 8):
                shot("trajets_renomme")
            else:
                shot("trajets_non_renomme")
                fail("le trajet renommé ne porte pas son nouveau nom dans la liste")
                name = None
        else:
            fail("la fenêtre « Renommer le trajet » ne s'ouvre pas")
            name = None
    else:
        fail("le menu d'un trajet enregistré (renommer, partager, supprimer) est introuvable")
        name = None
    check_alive("après le renommage d'un trajet")

    # Un appui rouvre le trajet sur la carte, prêt à être suivi.
    opened = tap(name) if name else tap("Enregistré aujourd'hui", exact=False)
    if opened and wait_for("Démarrer le guidage", 15):
        time.sleep(3)
        shot("trajet_rouvert")
        if name and not find(name):
            fail("le trajet rouvert n'affiche pas son nom dans le panneau d'itinéraire")
        if not distance:
            fail("la liste des trajets n'affiche pas la distance du trajet")
        elif not find(distance):
            fail(f"le trajet rouvert n'annonce pas la distance de la liste ({distance})")
    else:
        shot("trajet_non_rouvert")
        fail("un appui sur un trajet enregistré ne le rouvre pas sur la carte")
    tap("Fermer l'itinéraire", timeout=6, required=False)
    time.sleep(1)
    if find("Mes trajets"):
        tap("Rouler", required=False)
    check_alive("après la réouverture d'un trajet")


def route_poster():
    """La feuille de partage : l'affiche de l'itinéraire doit se dessiner, avec son fond de carte."""
    log("itinéraire : affiche à partager")
    if not tap("Partager l'itinéraire", timeout=8, required=False):
        fail("le bouton de partage de l'itinéraire est absent")
        return
    if not wait_for("Partager l'affiche", 10):
        shot("partage_absent")
        fail("la feuille de partage de l'itinéraire ne s'ouvre pas")
        return
    ready = wait_for("Aperçu de l'affiche", 15)
    # Le fond de carte arrive ensuite (quelques secondes avec du réseau).
    with_map = wait_for("Voilà ce que tes amis recevront.", 25)
    shot("itineraire_affiche")
    if not ready:
        fail("l'affiche de l'itinéraire n'a pas été dessinée")
    elif not with_map:
        # Pas un échec : sans réseau (ou serveur de tuiles lent), l'affiche montre le tracé seul.
        log("affiche dessinée sans fond de carte (réseau lent ou absent)")
    else:
        log("affiche dessinée avec son fond de carte")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1)
    if find("Partager l'affiche"):
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1)
    check_alive("après la préparation de l'affiche")


def online():
    """Vrai si le téléphone atteint Internet : Android a validé un de ses réseaux ou, à défaut, un nom se résout."""
    for line in shell("dumpsys connectivity", timeout=60).splitlines():
        if "NetworkAgentInfo" not in line:
            continue
        for capabilities in re.findall(r"Capabilities: ([A-Z0-9_&]+)", line):
            names = capabilities.split("&")
            if "VALIDATED" in names and "INTERNET" in names:
                return True
    # Second signe, indépendant du premier : le nom d'un serveur se résout. Les réponses au ping sont filtrées sur
    # les machines de GitHub ; seule compte la première ligne, qui donne l'adresse trouvée.
    return "PING github.com (" in shell("ping -c 1 -W 2 github.com 2>&1", timeout=30)


def wait_online(timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if online():
            return True
        time.sleep(3)
    return False


def network_or_give_up():
    """L'émulateur de la CI démarre parfois sans réseau utilisable : son Wi-Fi ne résout aucun nom, et le réseau
    mobile n'est pas toujours là pour prendre le relais (fabrications n° 30 et 31). Sans réseau, ni fond de carte,
    ni cartes hors ligne, ni recherche d'adresse : l'essai ne dirait rien de l'appli.

    On attend le réseau, on le relance par le mode avion, et s'il ne vient toujours pas on le note dans un
    fichier : la CI recommence alors une fois, sur un émulateur neuf."""
    if os.path.exists(NO_NETWORK):
        os.remove(NO_NETWORK)
    if wait_online(45):
        return True
    log("pas de réseau sur l'émulateur : on le relance par le mode avion")
    shell("cmd connectivity airplane-mode enable")
    time.sleep(6)
    shell("cmd connectivity airplane-mode disable")
    if wait_online(75):
        log("réseau rétabli après le mode avion")
        return True
    with open(NO_NETWORK, "w", encoding="utf-8") as f:
        f.write("Émulateur sans réseau utilisable : aucun réseau validé par Android, aucun nom résolu.\n")
    return False


def waiting_announcement():
    """Ce que l'écran dit d'un téléchargement qu'Android retient : « wifi » (il attend le Wi-Fi), « reseau » (il
    attend un réseau), « muet » (l'ancien défaut : « va démarrer… », sans explication), ou None s'il est parti."""
    if find("En attente du Wi-Fi", exact=False):
        return "wifi"
    if find("En attente du réseau", exact=False):
        return "reseau"
    if find("Le téléchargement va démarrer…"):
        return "muet"
    return None


def allow_mobile_data():
    """Désactive « Wi-Fi uniquement », tout en bas de l'écran des cartes hors ligne, puis remonte en haut de page."""
    row = None
    for _ in range(10):
        row = find("épargner ton forfait", exact=False)
        # Assez haut dans l'écran pour être touché franchement (pas sous la barre du bas).
        if row and (row[1] + row[3]) // 2 < 2000:
            break
        shell("input swipe 540 1800 540 800 300")
        time.sleep(0.7)
    if not row or not tap("épargner ton forfait", timeout=5, exact=False, required=False):
        fail("le réglage « Wi-Fi uniquement » est introuvable sur l'écran des cartes hors ligne")
    for _ in range(8):
        shell("input swipe 540 700 540 2000 250")
        time.sleep(0.5)


def wait_gone(label, timeout=20):
    """Attend qu'un texte ait disparu de l'écran."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not find(label, exact=False):
            return True
        time.sleep(1)
    return False


def offline_maps(gps):
    """Télécharge la carte d'une région (d'abord sans Wi-Fi : elle doit dire qu'elle l'attend), l'affiche, puis
    vérifie que tout marche en mode avion."""
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
    # D'abord sans Wi-Fi. Le réglage « Wi-Fi uniquement » est actif : Android garde le téléchargement en attente,
    # et l'appli doit le dire, au lieu de rester sur « Le téléchargement va démarrer… ».
    shell("svc wifi disable")
    time.sleep(5)
    tap(f"Télécharger {REGION}")
    wait_for("En attente du", 30, exact=False)
    shot("cartes_hors_ligne_attente_du_wifi")
    said = waiting_announcement()
    if said == "wifi":
        log("sans Wi-Fi : l'appli annonce qu'elle attend le Wi-Fi")
    elif said == "reseau":
        # Le réseau mobile de l'émulateur ne donne pas toujours accès à Internet (fabrication n° 32) : sans
        # Wi-Fi, le téléphone n'a alors plus de réseau du tout, et c'est ce que l'appli doit dire.
        log("sans Wi-Fi, et sans Internet par le réseau mobile : l'appli annonce qu'elle attend le réseau")
    elif said == "muet":
        fail("sans Wi-Fi, l'appli reste sur « Le téléchargement va démarrer… » sans dire ce qu'elle attend")
    else:
        # Android n'a pas compté le réseau mobile de l'émulateur comme un forfait : il n'y avait rien à attendre.
        log("constat : sans Wi-Fi, le téléchargement est parti quand même (attente du Wi-Fi non vérifiée)")
    # Le Wi-Fi revient : le téléchargement doit partir tout seul.
    shell("svc wifi enable")
    wifi_back = time.time()
    time.sleep(5)
    shot("cartes_hors_ligne_telechargement")
    installed = False
    forced = False
    deadline = time.time() + 480
    while time.time() < deadline:
        if find(f"Supprimer {REGION}"):
            installed = True
            break
        if find("Réessayer"):
            break
        if not forced and time.time() - wifi_back > 75 and find("En attente du Wi-Fi", exact=False):
            # Le Wi-Fi de l'émulateur ne revient pas toujours (fabrication n° 30 : il n'en a jamais eu, et la
            # carte ne s'est pas téléchargée). On autorise alors le forfait dans l'appli, qui doit relancer
            # d'elle-même ce qui attendait.
            forced = True
            log("le Wi-Fi de l'émulateur ne revient pas : « Wi-Fi uniquement » désactivé dans l'appli")
            allow_mobile_data()
            if not wait_gone("En attente du Wi-Fi", 40):
                shot("cartes_hors_ligne_forfait_sans_effet")
                fail("« Wi-Fi uniquement » désactivé, le téléchargement attend toujours le Wi-Fi")
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

    route_by_address(gps)

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
        gps.route_start = (gps.lat, gps.lon)
        if tap("Itinéraire"):
            time.sleep(2)
            loop_route()
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
        if guided:
            note_guidance("hors ligne")
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
    if not network_or_give_up():
        fail("l'émulateur n'a aucun réseau utilisable : essai impossible (ce n'est pas l'appli)")
        return
    gps = Gps()

    log("installation")
    result = run(["adb", "install", "-r", "-g", apk], timeout=240)
    log(result.stdout.decode("utf-8", "replace").strip()[-200:])
    if b"Success" not in result.stdout:
        fail("installation de l'APK refusée")
        return
    adb("logcat", "-c")
    gps.send()
    # L'émulateur vient de démarrer : on laisse l'écran d'accueil finir de s'installer, et on écarte la fenêtre
    # « Pixel Launcher isn't responding » si elle est déjà là.
    time.sleep(8)
    screen()

    log("démarrage de l'appli")
    shell(f"am start -W -n {PACKAGE}/{ACTIVITY}", timeout=120)
    time.sleep(10)
    if not check_alive("après le démarrage"):
        shot("carte_au_demarrage")
        return
    # Une sortie restée « en cours » (redémarrage imprévu de l'émulateur) : on la termine pour repartir d'un état connu.
    if find("Sortie interrompue"):
        log("une sortie interrompue traînait : on la termine")
        tap("Terminer")
        time.sleep(4)
        tap("Rouler", required=False)
    if not wait_for("Démarrer", 30):
        fail("le bouton « Démarrer » n'apparaît pas")
    # Le GPS n'a encore rien envoyé : c'est le moment de vérifier ce que l'appli fait en l'attendant.
    approximate_position(gps)
    gps.stand(4)
    shot("carte_au_demarrage")
    if not gps.wait_for("GPS ±", 25, exact=False):
        fail("aucune position GPS reçue par l'appli")
    else:
        gps_sheet_when_fixed(gps)

    log("démarrage d'une sortie")
    # Filmé : Google Play demande une courte vidéo de ce que fait le service d'enregistrement.
    video = start_video("enregistrement_d_une_sortie")
    tap("Démarrer")
    # Première fois : explication sur l'économie de batterie.
    if wait_for("Plus tard", 6):
        shot("explication_batterie")
        tap("Plus tard")
    if not wait_for("Pause", 20):
        fail("l'enregistrement ne démarre pas (bouton « Pause » absent)")
    check_alive("après « Démarrer »")
    gps.ride(6)
    # La notification d'enregistrement, telle qu'on la voit en tirant le volet du haut de l'écran.
    shell("cmd statusbar expand-notifications")
    time.sleep(3)
    shot("notification_d_enregistrement")
    shell("cmd statusbar collapse")
    time.sleep(1.5)
    stop_video(video)

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
    segment_from_ride()

    log("historique")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(2)
    shot("historique")
    if not wait_for("Mes sorties", 10):
        fail("l'historique ne s'affiche pas")
    statistics()

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
    if tap("Lire", timeout=5, required=False):
        if not wait_for("Politique de confidentialité de VeloGo", 10):
            fail("la politique de confidentialité ne s'affiche pas")
        shot("confidentialite")
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1)
    else:
        fail("le bouton « Lire » de la confidentialité est introuvable dans les réglages")

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
    app_icon()
    first_opening()
    if len(sys.argv) > 2:
        play_version(sys.argv[2], gps)


def first_opening():
    """Première ouverture : tant que la position n'est pas autorisée, l'appli dit ce qu'elle en fera avant de la
    demander, et donne accès à sa politique de confidentialité. On retire donc l'autorisation (Android arrête
    alors l'appli), on rouvre, on regarde, puis on la rend."""
    log("explication avant la demande de position")
    # Arrêt complet d'abord : retirer une autorisation à une appli ouverte la fait redémarrer là où elle en était
    # (ici, sur le détail de la dernière sortie), alors qu'une première ouverture commence sur la carte.
    shell(f"am force-stop {PACKAGE}")
    time.sleep(1)
    for permission in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"):
        shell(f"pm revoke {PACKAGE} android.permission.{permission}")
    time.sleep(2)
    shell(f"am start -W -n {PACKAGE}/{ACTIVITY}", timeout=120)
    time.sleep(8)
    shot("explication_position")
    if not wait_for("Où es-tu ?", 20):
        fail("sans autorisation de position, l'explication n'est pas affichée sur la carte")
    else:
        if not find("Autoriser la position"):
            fail("l'explication sur la position n'a pas son bouton « Autoriser la position »")
        if not find("Open-Meteo", exact=False):
            fail("l'explication ne dit pas ce qui sort du téléphone (Open-Meteo, IGN)")
        if tap("Confidentialité", timeout=5, required=False):
            if not wait_for("Politique de confidentialité de VeloGo", 10):
                fail("la politique de confidentialité ne s'ouvre pas depuis l'explication sur la position")
            shot("confidentialite_depuis_l_explication")
            shell("input keyevent KEYCODE_BACK")
            time.sleep(1)
        else:
            fail("le lien « Confidentialité » manque sous l'explication sur la position")
    check_alive("sans autorisation de position")
    for permission in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"):
        shell(f"pm grant {PACKAGE} android.permission.{permission}")
    shell(f"am force-stop {PACKAGE}")
    time.sleep(1)


def play_version(apk, gps):
    """La version destinée à Google Play. Elle s'installe par-dessus l'autre sans rien effacer (même clé, même
    identifiant), démarre, garde les sorties, calcule un itinéraire, ne propose aucune mise à jour hors du magasin
    et ne déclare pas la demande directe d'exemption de batterie."""
    log("version Google Play : installation par-dessus la version GitHub")
    result = run(["adb", "install", "-r", "-g", apk], timeout=240)
    log(result.stdout.decode("utf-8", "replace").strip()[-200:])
    if b"Success" not in result.stdout:
        fail("la version Play ne s'installe pas par-dessus la version GitHub")
        return
    declared = shell(f"dumpsys package {PACKAGE}")
    if "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in declared:
        fail("la version Play déclare encore la demande directe d'exemption de batterie")
    if "ACCESS_BACKGROUND_LOCATION" in declared:
        fail("la version Play demande la position en arrière-plan")
    shell(f"am start -W -n {PACKAGE}/{ACTIVITY}", timeout=120)
    time.sleep(10)
    if not check_alive("version Play, au démarrage"):
        shot("play_demarrage")
        return
    if not wait_for("Démarrer", 30):
        fail("version Play : le bouton « Démarrer » n'apparaît pas")
    shot("play_carte")
    tap("Historique")
    if not wait_for("Mes sorties", 10):
        fail("version Play : l'historique ne s'affiche pas")
    elif not find("Statistiques"):
        fail("version Play : les sorties enregistrées avec l'autre version ont disparu")
    shot("play_historique")
    tap("Réglages")
    time.sleep(1)
    updates = "Les mises à jour arrivent par Google Play."
    for _ in range(14):
        if find(updates):
            break
        shell("input swipe 540 1900 540 700 300")
        time.sleep(0.7)
    shot("play_reglages")
    if not find(updates):
        fail("version Play : la mention des mises à jour par Google Play est absente des réglages")
    if find("Vérifier") or find("Vérifier automatiquement"):
        fail("version Play : la recherche de mises à jour hors du magasin est encore proposée")
    tap("Rouler", required=False)
    play_route(gps)
    check_alive("version Play, à la fin")


def play_route(gps):
    """Un itinéraire calculé par la version Play. Dans un fichier AAB, les profils et les tables du moteur
    d'itinéraire sont rangés à part du code : s'ils manquaient dans l'APK que l'outil de Google en tire, la carte
    s'afficherait encore, mais plus aucun itinéraire ne se calculerait.

    On repart de l'endroit d'où le calcul sans réseau a déjà réussi, vers le même point touché sur la carte : la
    seule chose qui change est l'APK."""
    if not gps.route_start:
        # Pas de carte hors ligne (son téléchargement a échoué plus haut, et c'est déjà compté) : rien à calculer.
        log("version Google Play : itinéraire non essayé, faute de carte hors ligne")
        return
    log("version Google Play : itinéraire vers un point touché sur la carte")
    gps.lat, gps.lon = gps.route_start
    gps.stand(12)
    if not tap("Itinéraire", timeout=10, required=False):
        shot("play_itineraire_bouton_absent")
        fail("version Play : le bouton d'itinéraire est introuvable sur la carte")
        return
    time.sleep(2)
    # Appui long sur la carte, quelques centaines de mètres au nord de la position.
    shell("input swipe 540 640 540 640 1400")
    time.sleep(1)
    if find("Aller ici"):
        tap("Aller ici")
    if wait_for("Calculer l'itinéraire", 10):
        tap("Calculer l'itinéraire")
        found = wait_for_pattern(r"\d itinéraires?", 60)
        time.sleep(3)
        shot("play_itineraire")
        if found:
            log(f"version Play : {found}")
        else:
            fail("version Play : aucun itinéraire calculé")
    else:
        shot("play_itineraire_sans_arrivee")
        fail("version Play : le point touché sur la carte n'a pas été pris comme arrivée")
    tap("Fermer l'itinéraire", timeout=5, required=False)


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
