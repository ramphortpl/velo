# VeloGo — notes d'architecture

Appli Android native de vélo, usage personnel, **France uniquement**, 100 % gratuite, sans compte.
Interface en français, unités métriques. Téléphone cible : Samsung Galaxy A05s (Android 15, arm64,
**ni baromètre, ni boussole, ni gyroscope**, écran LCD 90 Hz peu lumineux).

Ce fichier résume l'architecture et les décisions. Il est tenu à jour à chaque jalon.

## Commandes

```bash
./gradlew test                 # tous les tests (core + app)
./gradlew :core:test           # calculs seuls : rapide, sans Android
./gradlew :routing:test        # itinéraires : vrais calculs BRouter sur un petit fichier de données réelles
./gradlew lintDebug            # vérifications Android (bloquant en CI)
./gradlew :app:assembleDebug   # APK de test
./gradlew :app:assembleRelease # APK release (signé seulement si VELOGO_KEYSTORE_PASSWORD est défini)
./gradlew :app:recordRoborazziDebug  # captures d'écran des écrans (PNG dans app/build/outputs/roborazzi/)
./gradlew :app:assembleSmoke   # APK d'essai pour émulateur (code minifié de la release, clé de test, x86_64)
cd cartes/style && npm install && npm run generer   # régénère le style de la carte hors ligne (assets/hors-ligne)
```

SDK requis : plateforme `android-37.1`, build-tools 37, JDK 21.

## Modules

| Module | Rôle |
|---|---|
| `core` | Kotlin pur, aucune dépendance Android : géodésie, moteur de sortie, altitude/pente/dénivelé, GPX, mise en forme, lecture des fichiers PMTiles et du relief, suivi d'itinéraire, météo, statistiques, segments. Tout calcul y vit pour être testé sur JVM. |
| `brouter` | Sources Java du moteur d'itinéraire BRouter 1.7.10 (MIT), copiées telles quelles : il n'existe pas sur Maven. Deux écarts avec l'original, décrits dans `brouter/README.md`. |
| `routing` | Kotlin pur : habillage de BRouter (`BRouterPlanner`), choix des profils selon le vélo et la préférence (`RoutingProfiles`), classement des voies. Profils et `lookups.dat` en ressources. |
| `app` | Android : Compose, service d'enregistrement, carte MapLibre, base Room, réglages, itinéraires et guidage. |

Paquets de `app` (`fr.velogo.app`) : `ui/` (écrans Compose, thème), `recording/` (service de premier plan,
enregistreur), `data/` (Room, DataStore, export GPX, analyse des sorties, sauvegarde), `map/` (MapLibre), `location/` (GPS), `elevation/`
(recalage d'altitude IGN, relief hors ligne), `offline/` (cartes hors ligne : catalogue, téléchargements, serveur
de tuiles local), `nav/` (itinéraires : calcul, guidage, voix, itinéraires enregistrés), `search/` (recherche
d'adresses IGN), `weather/` (météo Open-Meteo : client, mémoire, alerte pluie), `update/` (mises à jour via les
Releases GitHub).

Hors de l'appli : `cartes/` (fabrication des cartes hors ligne : profil Planetiler, contours des régions, style),
`outils/` (essai sur émulateur), `.github/workflows/` (`build.yml` pour l'APK, `cartes.yml` pour les cartes).

## Architecture de l'appli

- **Injection à la main** : `AppContainer` crée les objets partagés à la demande ; les écrans y accèdent par
  `LocalContainer` et `veloViewModel { ... }`. Pas de Hilt ni de Koin.
- **Enregistrement** : `RecordingService` (service de premier plan de type `location`) alimente `RideRecorder`,
  qui fait tourner `RideEngine` (module `core`) sur un seul fil d'exécution, publie un `StateFlow<RideSnapshot>`
  pour l'écran et sauvegarde la trace toutes les 8 s ou 10 points. Les commandes (démarrer, pause, reprendre,
  terminer) passent par une file et sont traitées une par une. Une sortie restée « en cours » en base est
  reprise au redémarrage du service ou proposée à la reprise à l'ouverture de l'appli.
- **GPS** : `GpsSource` = GPS brut d'Android (`LocationManager`, 1 Hz, sans services Google), partagé ; il
  s'éteint quand plus personne n'écoute. Altitude convertie au niveau de la mer (`AltitudeConverterCompat`).
  Chaque position porte le **sens de la marche** (`GpsFix.courseDeg`, calculé par `core/geo/CourseEstimator`) :
  le cap du GPS quand on roule, sinon celui déduit du déplacement, et à l'arrêt le dernier connu.
- **Carte** : `VeloMap` (MapView dans `AndroidView`, mode SurfaceView) pilotée par `VeloMapState`, qui garde ce
  qui doit être dessiné et le redessine après chaque changement de fond. La trace en direct est découpée en
  morceaux figés + une « queue » de 300 points pour rester fluide après des heures. La position est un point
  tant que le sens de la marche est inconnu, puis une flèche (`map_puck_dot`, `map_puck_arrow`). Pendant un
  guidage, `setNavigation(true)` met la carte en **vue de guidage** : suivi dans le sens de la marche
  (`CameraMode.TRACKING_GPS`), inclinée (50°), rapprochée, position descendue vers le bas de l'écran
  (`setNavigationInsets`). `fit` cadre une emprise vue de dessus ; `onUserMove` et `onRouteTapped` préviennent
  l'écran quand on déplace la carte ou qu'on touche un itinéraire proposé.
- **Navigation** : l'écran « Rouler » (carte + enregistrement) reste composé en permanence sous les autres ;
  Trajets, Historique et Réglages se posent par-dessus via Navigation 3 (`AppRoot`). La carte n'est jamais
  recréée en changeant d'onglet ; son rendu est coupé dès qu'elle est recouverte.
- **Cartes hors ligne** : `OfflineMaps` réunit le catalogue (`catalogue.json` de la Release `cartes`), les
  fichiers installés (`OfflineStore`, dossier privé de l'appli sur le stockage partagé ou la carte SD, une notice
  `.json` par fichier vérifié) et les téléchargements (`PackDownloader`, qui s'appuie sur le `DownloadManager`
  d'Android puis vérifie le SHA-256). Le fond « Vélo hors ligne » charge un style embarqué
  (`assets/hors-ligne/style-*.json`) dont les tuiles, polices et icônes pointent vers l'adresse fictive
  `https://hors-ligne.velogo.invalid/…` : `OfflineTileInterceptor` (dans le client OkHttp de MapLibre) y répond
  depuis les PMTiles du téléphone via `OfflineRouter`, sans réseau. `ReliefSource` lit l'altitude du sol dans les
  packs de relief ; `RideRecorder` la joint à chaque position (`Fix.terrainAltitudeM`).
- **Itinéraires et guidage** : `NavigationController` (dans le conteneur, indépendant des écrans) tient
  l'itinéraire en préparation (`PlannerState`) et le guidage en cours (`GuidanceState`). `RouteService` calcule
  les trois propositions avec `BRouterPlanner` (rapide, sûr, peu de dénivelé ; doublons réunis, variantes en
  complément) et remplace le profil d'altitude par celui du relief hors ligne quand il existe. Le suivi lui-même
  est dans `core/nav` : `RouteFollower` (progression, sortie d'itinéraire), `GuidanceAnnouncer` et
  `GuidancePhrases` (quand parler, et quoi dire). `VoiceGuide` est le seul endroit qui touche au son.
  L'écran « Rouler » affiche `PlannerPanel` en bas de la carte et `GuidanceBanner` sous la barre du haut.
  Une fois les itinéraires proposés, le panneau est **replié par défaut** (propositions compactes + bouton de
  départ) et la carte se cadre sur le trajet dans la place restante ; « Détails » ou la poignée le déplient.
- **Affiche à partager** : `RoutePoster` dessine l'affiche d'un itinéraire (1080 × 1350, `android.graphics`,
  sans passer par l'écran) ; `RouteSnapshot` photographie le fond de carte hors écran (`MapSnapshotter`, même
  client réseau que la carte, donc carte hors ligne comprise) et, s'il n'arrive pas en douze secondes, l'affiche
  montre le tracé seul ; `RouteShare` l'envoie en JPEG avec une légende. `ShareRouteContent` est la feuille
  d'aperçu (titre et deuxième ligne modifiables) ; `RouteShareSheet` l'habille (photo du fond, dessin, envoi) et
  sert aux deux endroits qui partagent un itinéraire : le panneau de la carte et l'onglet « Trajets ».
- **Onglet « Trajets »** (`ui/routes/SavedRoutesScreen`) : les itinéraires enregistrés (`SavedRouteRepository`,
  table `saved_routes`), les plus récents d'abord. La liste ne charge pas les tracés (plusieurs centaines de Ko
  chacun) : la vignette de chaque ligne vient de `SavedRouteRepository.preview` (tracé allégé à 90 points),
  préparée en tâche de fond. Un appui relit le trajet et le rend au `NavigationController` (`showSaved`), puis
  repasse sur la carte ; si un guidage est en cours, une confirmation prévient qu'il s'arrêtera. Le menu d'une
  ligne partage l'affiche, renomme ou supprime (avec confirmation). `savedRouteRows` met en forme les lignes.
- **Météo** : `WeatherController` (dans le conteneur) tient la météo du lieu (`WeatherState`) et celle de
  l'itinéraire préparé ou suivi (`RouteWeatherState`). `OpenMeteo` interroge l'API (une seule requête pour
  plusieurs lieux) et lit la réponse ; `WeatherRepository` garde les dernières prévisions du lieu en mémoire et
  sur disque (`cacheDir/meteo/ici.txt`) : rien n'est redemandé avant dix minutes ou quatre kilomètres, et sans
  réseau les dernières reçues servent tant qu'elles couvrent l'heure qu'il est. Les calculs sont dans
  `core/weather` : `RainForecast` et `RainAlerter` (pluie au quart d'heure, une alerte par averse), `Wind` (de
  face, de dos), `RouteWeather` (où regarder la météo le long d'un itinéraire, heures de passage selon le relief,
  résumé). La météo n'allume jamais le GPS : elle prend la dernière position connue (`GpsSource.lastKnown`).
  Hors sortie, c'est l'écran « Rouler » qui demande les mises à jour quand il est affiché ; pendant une sortie
  ou un guidage, le contrôleur se met à jour seul toutes les deux minutes (le réseau n'est sollicité que toutes
  les dix) pour prévenir de la pluie écran éteint (vibration, notification silencieuse, voix si elle est activée).
- **Statistiques, records, segments** : les calculs sont dans `core/stats` (`RideStats` : totaux par semaine,
  mois, année et records ; `BestEfforts` : meilleurs temps sur 5 à 100 km par fenêtre glissante) et
  `core/segments` (`SegmentMatcher` : retrouve dans une trace les passages sur un segment, heures d'entrée et de
  sortie interpolées ; `SegmentCutter` : découpe un morceau de sortie entre deux distances et le mesure).
  `RideAnalysis` les applique une fois par sortie terminée, en tâche de fond, et range le
  résultat en base (`best_efforts`, `segment_efforts`) ; chaque sortie retient la version de l'analyse reçue
  (`analysisVersion`) : augmenter `RideAnalysis.VERSION` fait reprendre toutes les sorties au lancement suivant.
  Un segment (`SegmentRepository`) est un morceau de sortie choisi sur le profil d'altitude ; à sa création il
  est cherché dans toutes les sorties dont l'emprise le contient. L'écran de création et l'enregistrement du
  segment passent par le même `SegmentCutter` : les chiffres annoncés sont ceux du segment créé. Les écrans
  (`ui/stats`, `ui/segments`) ne font que mettre en forme (`buildStats`, `segmentRows`, `effortRows`).
- **Sauvegarde** : `RideArchive` écrit toutes les sorties dans une archive ZIP de fichiers GPX (la balise `<src>`
  note l'origine de l'altitude) et relit une archive ou un GPX seul (`TrackImport.prepare` recalcule les vitesses,
  absentes d'un GPX ; une sortie déjà présente, à une minute près, n'est pas ajoutée deux fois).
- **Textes** : interface uniquement en français, textes écrits directement dans les composables (seuls ceux
  du service et des notifications sont dans `strings.xml`).
- **Écrans testables** : chaque écran a un composable « contenu » sans accès aux données (`RideChrome`,
  `HistoryContent`, `RideDetailBody`, `SettingsContent`, `OfflineMapsContent`, `PlannerPanel`, `PlaceSearchContent`,
  `SavedRoutesList`, `SavedRoutesContent`, `ShareRouteContent`, `WeatherContent`, `StatsContent`,
  `SegmentsContent`, `SegmentDetailBody`, `SegmentEditor`) rendu en image par `ScreenshotTest`. `RoutePosterTest` dessine l'affiche
  pour de bon et range ses images au même endroit (`affiche*.png`).
  Attention : une animation sans fin (barre de progression indéterminée) bloque la capture.
  `SavedRoutesContentTest` pilote l'onglet « Trajets » au doigt (`createComposeRule`). Limite constatée : une
  fenêtre (`AlertDialog`) qui contient un champ de saisie n'est jamais « au repos » pour cet outil sous
  Robolectric, même vide de tout code de l'appli ; ce genre de fenêtre se vérifie donc sur l'émulateur.

## Vérification sans téléphone

Il n'y a pas d'émulateur dans l'environnement de développement. Quatre niveaux de vérification :

1. `core` : tests unitaires des calculs (sorties simulées, références GeographicLib, tuiles PMTiles comparées à
   l'outil officiel `pmtiles`).
2. `app` : `ScreenshotTest` (Robolectric + Roborazzi) rend les écrans en PNG au format du Galaxy A05s ; tests du
   serveur de tuiles local, du catalogue et de `ReliefSource` (une vraie tuile de relief, altitudes comparées à
   celles du service de l'IGN) ; météo testée contre un faux serveur (intercepteur OkHttp, aucun réseau) ;
   segments, meilleurs temps et sauvegarde testés de bout en bout sur une vraie base Room en mémoire
   (`RideAnalysisTest`, `RideArchiveTest`), trajets enregistrés aussi (`SavedRouteRepositoryTest`) ;
   `DatabaseMigrationTest` fabrique une base à l'ancien format d'après
   son schéma exporté, y range une sortie et l'ouvre avec l'appli d'aujourd'hui (à compléter à chaque nouvelle
   version de la base). Sur l'ordinateur, ces tests ouvrent la base avec le SQLite d'Android
   simulé par Robolectric (`AndroidSQLiteDriver`) : celui que l'appli embarque (`BundledSQLiteDriver`) n'a pas de
   bibliothèque native hors Android. `UpdateCheckerTest` lit des listes de Releases de la forme exacte de celles
   de GitHub (relevée sur la première version publiée).
3. `routing` : itinéraires calculés pour de vrai autour de Corte avec `src/test/resources/dalles` (80 Ko de
   données BRouter réelles) : profils, étapes, évitement des grands axes, consignes, données manquantes.
4. CI : le job « Essai sur émulateur » installe l'APK `smoke` sur un émulateur Android 15 et déroule
   `outils/essai_emulateur.py` : sortie simulée à Corte (positions envoyées par le fournisseur de test d'Android,
   plus fiable que le GPS simulé de l'émulateur), distance affichée, pause automatique, historique, fonds de carte,
   téléchargement de la carte de Corse, itinéraire vers une adresse (panneau replié puis déplié, enregistrement de
   l'itinéraire, affiche à partager), guidage (flèche au départ, vue de guidage en roulant, « Recentrer » après
   avoir déplacé la carte), onglet « Trajets » (le trajet enregistré s'y retrouve, se renomme, se rouvre sur la
   carte), puis en mode avion : carte, altitude, itinéraire vers un point de la carte, guidage avec
   enregistrement ; enfin reprise après arrêt forcé.
   Après la première sortie : création d'un segment (la sortie doit s'y retrouver), statistiques, liste des segments.
   La saisie au clavier y est vérifiée (`type_text` relit le champ, complète ou efface) : envoyées pendant que le
   clavier démarre, les touches d'`adb shell input text` se perdent (fabrication n° 16 : « gare d » au lieu de
   « gare de corte », et tout l'essai de l'itinéraire en ligne sauté).
   Les captures d'écran sont publiées en artefact. La météo n'y est qu'un constat, jamais un échec : Open-Meteo
   limite les demandes par adresse IP, et celles des machines de la CI (comme celle de l'environnement de
   développement) sont partagées, donc souvent refusées (« Daily API request limit exceeded »).

L'émulateur de la CI doit tourner avec le moteur graphique ANGLE (`-gpu swangle_indirect`) : avec son rendu
logiciel par défaut (`swiftshader_indirect`), les textes et le repère de position des cartes MapLibre ne
s'affichent pas, alors que la même appli les affiche parfaitement avec ANGLE (vérifié le 20/11/2026).

Le comportement réel sur le Galaxy A05s (GPS, économie de batterie Samsung, lisibilité au soleil) ne peut être
validé que par l'utilisateur.

## Stack (versions vérifiées le 2026-11-20)

Kotlin 2.4.21 · AGP 9.5.1 (Kotlin intégré, pas de plugin `kotlin-android`) · Gradle 9.9.2 · Compose BOM 2026.11.01
(Material 3 1.5) · Navigation 3 · Room 3.0 (KSP, `androidx.room3`, pilote SQLite embarqué) · DataStore ·
MapLibre Native Android 13.7.1 **variante OpenGL ES** · OkHttp 4.12 (la version qu'embarque MapLibre) · minSdk 26, targetSdk 37, compileSdk 37.1
(exigé par Compose 1.13).

Les versions sont centralisées dans `gradle/libs.versions.toml`.

## Décisions

- **MapLibre en OpenGL ES** (`android-sdk-opengl`) : la variante Vulkan par défaut reçoit encore des correctifs de
  plantage pour les GPU Adreno 6xx (celui de l'A05s).
- **Altitude sans baromètre** : altitude GPS ramenée au niveau de la mer, lissée par filtre de Kalman
  (`core/ride/Altitude.kt`). Deux réglages (`AltitudeSource`) : `GNSS` (très lissé, seuil de dénivelé 8 m) et
  `TERRAIN` (modèle de relief, seuil 2 m). Les paramètres GNSS ont été réglés par balayage sur des sorties simulées.
- **Distance** : somme des pas GPS sur l'ellipsoïde WGS84 (`Geo.distanceM`), validée contre GeographicLib.
  La distance ne s'accumule pas à l'arrêt, même si la pause automatique est désactivée.
- **Musique** : l'appli ne demande jamais le focus audio ; la notification d'enregistrement est silencieuse.
- **Données** : tout est local (Room). Pas de sauvegarde cloud (`allowBackup=false`), transfert d'appareil autorisé.
- **France uniquement** : carte bornée à `Geo.FRANCE`, interface en français seulement (`localeFilters`).
- **Recalage d'altitude** : à la fin d'une sortie, l'altitude est remplacée par celle du relief
  (`ElevationCorrector`) : API altimétrie de la Géoplateforme IGN si le réseau répond, sinon relief des cartes
  hors ligne, en gommant viaducs et tunnels grâce à l'altitude GPS (`TerrainCorrection.fuse`). Sans réseau ni carte,
  la sortie garde son altitude GPS (source `GNSS`) et le recalage est retenté aux lancements suivants.
- **Altitude en direct** : dans une région téléchargée, l'affichage (altitude, pente, dénivelé) suit le relief
  hors ligne (réglage `TERRAIN`) ; ailleurs, le GPS. La trace enregistre toujours l'altitude GPS quand elle existe,
  car le recalage de fin de sortie s'en sert. Le relief hors ligne (zoom 12, ~13 m par pixel) s'écarte de moins
  d'un mètre du service de l'IGN sur les points vérifiés.
- **Cartes hors ligne : trois fichiers par région** dans la Release GitHub `cartes` : `fond-<région>.pmtiles`
  (extrait du basemap Protomaps, zooms 0 à 15), `velo-<région>.pmtiles` (surcouche vélo Planetiler, zooms 6 à 14,
  couches `itineraires`, `voies`, `points`), `relief-<région>.pmtiles` (Mapterhorn, terrarium WebP 512 px, zooms 0
  à 12), plus une vue d'ensemble de la France et les dalles BRouter `.rd5` pour le jalon 3. Les fichiers publiés
  portent le début de leur empreinte dans leur nom : une régénération ne casse jamais un téléchargement en cours.
- **Pas de `pmtiles://` dans MapLibre** : la prise en charge native est encore jeune et ne sait pas réunir
  plusieurs régions dans une seule source. Notre lecteur PMTiles (`core/tiles`) et l'intercepteur OkHttp font le
  même travail en restant sous notre contrôle (tuile absente = 404, plage de polices absente = réponse vide).
  Quand le fond hors ligne est affiché, `MapLibre.setConnected(true)` empêche MapLibre d'attendre le réseau.
- **BRouter embarqué en sources** (module `brouter`), R8 le garde entier (`-keep class btools.**`) car il crée
  des classes par leur nom. Version de `lookups.dat` (11.2) à garder identique à celle des données de brouter.de.
- **Données d'itinéraire par carrés d'un degré** : `cartes/outils/dalles.py` redécoupe les dalles de 5° de BRouter
  en fichiers `dalle-<carré>.rd5` au même format (un bloc par fichier) ; `NodesCache.fileForSegment` cherche ces
  noms-là. Une région liste ses carrés (`dalles` dans `cartes/regions.json`) et les télécharge avec ses cartes ;
  les carrés partagés entre régions voisines ne sont supprimés que quand plus aucune ne s'en sert.
- **Profils** : route = `fastbike`, VTC/gravel = `trekking`, VTT = `mtb` ; « sûr » pousse l'évitement du trafic,
  « peu de dénivelé » renchérit les montées (`RoutingProfiles.choose`). Les paramètres injectés sont toujours
  numériques (1/0). La durée vient du modèle cinématique de BRouter avec la puissance du réglage « Allure ».
- **Marges de la carte « collantes »** : dans MapLibre, les marges données à un cadrage (`fit`) restent
  attachées à la caméra. Sans remise à zéro, la position suivie se retrouvait dans le haut de l'écran après le
  calcul d'un itinéraire. Tout passage en suivi (`startTracking`) règle donc explicitement marges et inclinaison,
  une fois la transition finie (pendant une transition, la carte ignore ces réglages).
- **Sens de la marche sans boussole** : il vient du déplacement. À l'arrêt on garde le dernier cap (le bruit du
  GPS ferait tourner la flèche) ; au départ d'un guidage, tant qu'on n'a pas roulé, la flèche prend la direction
  de l'itinéraire (`Route.bearingAt`). Le fournisseur de positions de test de l'émulateur ne donne ni cap ni
  vitesse : c'est ce calcul qui y fait apparaître la flèche.
- **Repères de position aux couleurs fixées** : la teinte que MapLibre propose d'appliquer au repère effacerait
  le contour blanc de la flèche ; les deux dessins portent donc leurs couleurs.
- **Fichiers partagés gardés un jour** (`ShareFiles`) : l'appli qui les reçoit peut encore les lire après notre
  retour au premier plan ; les effacer à chaque nouveau partage cassait le précédent.
- **Guidage vocal** : synthèse vocale d'Android, focus audio `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` le temps de la
  phrase (la musique baisse, ne s'arrête pas), désactivé par défaut. Aucune autre demande de focus dans l'appli.
- **Pas de nom de rue dans les consignes** : les données BRouter n'en contiennent pas.
- **Itinéraires enregistrés à six décimales** (`RouteCodec`, `Polyline.FINE`, dix centimètres) : à cinq décimales
  (un mètre), chaque point bougeait un peu et l'itinéraire relu n'avait plus tout à fait sa longueur (de −1 à
  +10 m mesurés sur des itinéraires de 1 à 6 km autour de Corte) ; vu sur l'émulateur, un trajet de « 2,4 km »
  dans la liste se rouvrait à « 2,5 km ». Le champ `decimales` du texte enregistré vaut 5 quand il est absent :
  les itinéraires des versions précédentes se relisent comme avant. Les vignettes restent à cinq décimales.
- **Un trajet enregistré se suit tel quel** : rouvert, il garde ses consignes de virage mais n'est jamais
  recalculé si on s'en écarte (comme une trace GPX importée) : recalculer vers l'arrivée d'une boucle la
  réduirait à rien.
- **Météo par Open-Meteo, modèles Météo-France** (`models=meteofrance_seamless` : AROME 1,5 km puis ARPEGE) :
  gratuit, sans clé ni compte, CC BY 4.0. L'API de Météo-France elle-même demande un compte et une clé : écartée.
  La position est envoyée arrondie à deux décimales (environ un kilomètre).
- **Conventions de temps d'Open-Meteo** : la pluie datée de 15 h est celle tombée entre 14 h et 15 h (et celle
  datée de 10 h 15, entre 10 h et 10 h 15). `OpenMeteo.parse` la ramène au début de la période : dans
  `WeatherPoint` et `RainStep`, la pluie est toujours celle de la période qui **commence** à l'heure indiquée.
- **Alerte pluie silencieuse** : vibration faite par l'appli + notification sans son (canal `meteo`), jamais de
  son de notification, pour ne pas toucher à la musique. Elle n'est dite à voix haute que si le guidage vocal
  est activé (même baisse brève de la musique que les consignes). Une averse déjà en cours au départ n'est pas
  annoncée.
- **Heures de passage** le long d'un itinéraire : durée totale de BRouter répartie selon la pente (modèle de
  puissance constante dans `RouteWeather.passageTimes`), pas au prorata de la distance.
- **Base Room en version 3** : version 2 = table `saved_routes` (jalon 3) ; version 3 = tables `segments`,
  `segment_efforts`, `best_efforts` et colonne `rides.analysisVersion` (jalon 5). Migrations automatiques
  (`AutoMigration`), schémas exportés dans `app/schemas`. Ne jamais modifier un schéma déjà publié : ajouter une
  version.
- **Temps des segments et des meilleurs temps = temps réel écoulé**, arrêts compris (comme une course), et non
  temps en mouvement : plus simple à comprendre et impossible à « améliorer » en s'arrêtant.
- **Segments reconnus à 30 m près**, dans le sens du tracé, en entier ; au plus 80 m roulés hors du tracé
  (tunnel, GPS qui décroche). La recherche avance dans une fenêtre juste devant la position, pour ne pas sauter
  d'un lacet à l'autre.
- **Sauvegarde en GPX dans un ZIP** plutôt qu'une copie de la base : lisible par toutes les applis, et relisible
  par les versions futures quelle que soit l'évolution du schéma. Elle ne contient ni les segments ni les
  itinéraires enregistrés.
- **Téléchargements confiés au `DownloadManager` d'Android** plutôt qu'à WorkManager : reprise après coupure,
  attente du Wi-Fi et notification sont gérées par le système, sans service ni autorisation supplémentaires.
- **Portrait uniquement** pour l'instant (`screenOrientation="portrait"`) : la disposition paysage de l'écran
  guidon n'est pas au point ; à reprendre aux finitions si besoin.
- **R8** : la release est minifiée. Les clés de navigation (`*Key`) sont gardées explicitement.
- **Identité** : vert émeraude + bleu nuit, police Barlow Condensed (OFL) pour les chiffres, icônes Material
  Symbols (Apache 2.0). Thème « plein soleil » noir sur blanc pour l'écran guidon.

## Signature et livraison

- Clé : `signing/velogo-release.p12` (PKCS12, RSA 4096, chiffrée AES-256 par un mot de passe aléatoire de
  40 caractères). **Le mot de passe n'est que dans le secret GitHub `VELOGO_KEYSTORE_PASSWORD`** ; ne jamais
  l'écrire dans le dépôt.
- Le workflow vérifie le secret dès le début (`keytool -list` sur la clé du dépôt) : absent, il prévient et
  continue sans publier ; présent mais faux, il le dit tout de suite (avec sa longueur) et fait échouer le job
  après les tests. Les espaces et retours à la ligne collés autour du mot de passe sont ignorés, dans le
  workflow comme dans `app/build.gradle.kts`.
- `.github/workflows/build.yml` : à chaque push, tests + lint et APK signé (job `apk`), essai sur émulateur
  (job `essai`), puis, **seulement si les deux ont réussi**, publication en Release par le job `publication`
  (`v<majeur>.<mineur>.<numéro de build>`), une vingtaine de minutes après le push : ce qui arrive sur le
  téléphone a tourné sur un vrai Android. `versionCode` = numéro de build GitHub, donc chaque APK s'installe
  par-dessus le précédent. Le mineur de `version.properties` suit les jalons.
- Les notes de version viennent de la première section de `docs/NOUVEAUTES.md`.
- Première version publiée : `v0.5.11` (21/11/2026). Pour contrôler un APK publié :
  `apksigner verify --print-certs` doit donner le certificat `CN=VeloGo` d'empreinte SHA-256
  `1e80bd8e20912aaae8f62310cff7fd004f58e605a265d6b193161fb29193768b` (signature v2 seule, normal avec minSdk 26).
- L'appli cherche ses mises à jour dans la liste des Releases (`UpdateChecker`) : étiquettes `v1.2.3` avec un
  fichier `.apk`, le plus grand numéro de build l'emporte ; la Release `cartes` est ignorée.
- **Mémoire des téléchargements** (`actions/cache`, clé = versions des outils et bibliothèques) : Gradle, ses
  extensions, les bibliothèques et l'image Android de Robolectric sont gardés d'une fabrication à l'autre. Sans
  elle, deux fabrications (n° 10 et n° 12, le 21/11/2026) ont échoué avant les tests sur « Plugin
  org.jetbrains.kotlin.jvm … was not found » : un dépôt de bibliothèques ne répondait pas à la machine de GitHub
  (Gradle ne dit pas lequel sans `--stacktrace`). Règle de sécurité : seul le job `apk` l'écrit, **avant** l'étape
  qui passe le mot de passe de la clé à Gradle, et sans le cache de compilation ; le job `essai` ne fait que la
  lire. Pour qu'elle soit complète, quand elle est à écrire, le job `apk` construit d'abord la version publiable
  **sans la signer** (les versions minifiées demandent des bibliothèques que les tests ne téléchargent pas,
  `compose-group-mapping` par exemple) : vérifié le 21/11/2026 sur une copie neuve avec un dossier Gradle vide,
  la version signée et la version d'essai se construisent ensuite avec `--offline`. Environ 1,3 Go compressés.
  Sur GitHub : la fabrication n° 14 l'a écrite, la n° 15 l'a relue en 14 secondes (Gradle n'a pas été
  retéléchargé, et son premier appel est passé de 45 s à 12 s).
  La mémoire de l'action `setup-gradle` est coupée (`cache-disabled`) : elle n'écrit que depuis `main`, et sa
  version par défaut est sous licence propriétaire (gratuite pour un dépôt public).
- `outils/ci_outils_de_construction.sh` : premier appel à Gradle, trois tentatives (30 s puis 90 s d'attente),
  la dernière avec la cause exacte de l'échec.

## Fabrication des cartes hors ligne

`.github/workflows/cartes.yml` (manuel, à chaque changement de `cartes/selection.txt` ou du profil, et le 4 de
chaque mois une fois la branche fusionnée dans `main`) :

1. `preparation` : liste des régions (`cartes/selection.txt` : `toutes` ou des identifiants), dernier build Protomaps.
2. `surcouche` : Planetiler lit l'extrait OpenStreetMap de la France (Geofabrik, OSM France en secours) avec
   `cartes/SurcoucheVelo.java` et produit `velo-france.pmtiles`.
3. `region` (une tâche par région) : `pmtiles extract` découpe fond, surcouche vélo et relief selon
   `cartes/contours/<région>.geojson` (région + 12 km de marge), puis publie dans la Release `cartes`.
4. `france` : vue d'ensemble (fond zooms 0 à 8, relief 0 à 7) et données d'itinéraire BRouter, redécoupées en
   carrés d'un degré (seules les dalles qui ont changé sur brouter.de sont retéléchargées).
5. `catalogue` : assemble `catalogue.json` (`cartes/outils/catalogue.py`), le publie, puis supprime les anciennes
   versions des fichiers.

Tout est gratuit : calcul sur les runners GitHub Actions, hébergement dans les Releases d'un dépôt public
(moins de 2 Gio par fichier). Aucun téléchargement en masse de serveurs de tuiles : on lit des fichiers prévus
pour cela (basemap Protomaps, Mapterhorn, extraits OSM).

## Jalons

1. Carte en ligne + enregistrement + écran guidon + historique *(fait)*
2. Cartes hors ligne (packs régionaux : fond Protomaps, surcouche vélo Planetiler, relief Mapterhorn) *(fait)*
3. Itinéraires BRouter embarqué + guidage *(fait)*
4. Météo Open-Meteo (modèles Météo-France AROME) *(fait)*
5. Stats, records, segments, finitions *(fait)*
6. Itinéraire en grand, affiche à partager, guidage avec flèche de direction, onglet « Trajets » *(fait ; déroulé
   en entier sur l'émulateur par la fabrication n° 18 du 21/11/2026, première version publiée : `v0.6.18`)*

## Sources de données et attributions

OpenStreetMap (ODbL) · OpenFreeMap / OpenMapTiles · CyclOSM (serveurs OSM France : en ligne uniquement,
User-Agent identifiant l'appli, pas de téléchargement en masse) · IGN Géoplateforme (Plan IGN, photos aériennes,
adresses, altitude ; Licence Ouverte 2.0) · Open-Meteo (CC BY 4.0) · Protomaps (BSD, données OSM) · Mapterhorn
(MNT LiDAR HD et RGE ALTI® de l'IGN, Licence Ouverte 2.0) · Noto Sans (OFL) · BRouter (MIT).
Les cartes IGN TOP 25 (SCAN 25) sont interdites aux particuliers : non utilisées.
