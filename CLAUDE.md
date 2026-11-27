# VeloGo — notes d'architecture

Appli Android native de vélo, **France uniquement**, 100 % gratuite, sans compte. D'abord faite pour le seul
usage de son auteur ; depuis le 26/11/2026, elle est **destinée au public** (Google Play et autres magasins,
`docs/PLAY_STORE.md`) : tout ce qu'elle utilise doit être permis à une appli publique, pas seulement à un particulier.
Interface en français, unités métriques. Téléphone cible : Samsung Galaxy A05s (Android 15, arm64,
**ni baromètre, ni boussole, ni gyroscope**, écran LCD 90 Hz peu lumineux).

Ce fichier résume l'architecture et les décisions. Il est tenu à jour à chaque jalon.

## Commandes

```bash
./gradlew test                 # tous les tests (core, routing, app en version « githubDebug »)
./gradlew :core:test           # calculs seuls : rapide, sans Android
./gradlew :routing:test        # itinéraires : vrais calculs BRouter sur un petit fichier de données réelles
./gradlew :app:lintGithubDebug :app:lintPlayRelease   # vérifications Android des deux versions (bloquant en CI)
./gradlew :app:assembleGithubDebug    # APK de test
./gradlew :app:assembleGithubRelease  # APK des Releases GitHub (signé seulement si VELOGO_KEYSTORE_PASSWORD est défini)
./gradlew :app:bundlePlayRelease      # fichier AAB pour Google Play (même clé, même condition)
./gradlew :app:recordRoborazziGithubDebug  # captures d'écran des écrans (PNG dans app/build/outputs/roborazzi/)
./gradlew :app:assembleGithubSmoke :app:packagePlaySmokeUniversalApk  # APK d'essai pour émulateur (code minifié, clé de test) ;
                               # celui de la version Play est tiré de son fichier AAB (app/build/outputs/apk_from_bundle/)
python3 outils/fiche_play.py   # longueurs des textes de la fiche Google Play (docs/play/fiche.md)
cd cartes/style && npm install && npm run generer   # régénère les styles de la carte (assets/en-ligne, assets/hors-ligne)
cd cartes/style && npm run controler                # les contrôle : spécification MapLibre, et ce qu'Android ne sait pas lire
```

SDK requis : plateforme `android-37.1`, build-tools 37, JDK 21.

Ne pas lancer `./gradlew :app:tasks --all` : il configure une tâche d'aperçu Compose d'AGP qui échoue sur les
variantes sans tests unitaires (toutes sauf `githubDebug`). Les tâches utiles sont celles ci-dessus.

## Modules

| Module | Rôle |
|---|---|
| `core` | Kotlin pur, aucune dépendance Android : géodésie, moteur de sortie, altitude/pente/dénivelé, GPX, mise en forme, lecture des fichiers PMTiles, des tuiles vectorielles et du relief, suivi d'itinéraire et noms de rue, météo, statistiques, segments. Tout calcul y vit pour être testé sur JVM. |
| `brouter` | Sources Java du moteur d'itinéraire BRouter 1.7.10 (MIT), copiées telles quelles : il n'existe pas sur Maven. Deux écarts avec l'original, décrits dans `brouter/README.md`. |
| `routing` | Kotlin pur : habillage de BRouter (`BRouterPlanner`), choix des profils selon le vélo et la préférence (`RoutingProfiles`), classement des voies. Profils et `lookups.dat` en ressources. |
| `app` | Android : Compose, service d'enregistrement, carte MapLibre, base Room, réglages, itinéraires et guidage. |

Paquets de `app` (`fr.velogo.app`) : `ui/` (écrans Compose ; `ui/theme` : couleurs, verre, formes ; `ui/components` :
cartes, sélecteur, interrupteur, boutons de verre, barres à grand titre), `recording/` (service de premier plan,
enregistreur), `data/` (Room, DataStore, export GPX, analyse des sorties, sauvegarde), `map/` (MapLibre), `location/` (GPS), `elevation/`
(recalage d'altitude IGN, relief hors ligne), `offline/` (cartes hors ligne : catalogue, téléchargements, serveur
de tuiles local), `nav/` (itinéraires : calcul, guidage, voix, itinéraires enregistrés), `search/` (recherche
d'adresses IGN), `weather/` (météo Open-Meteo : client, mémoire, alerte pluie), `update/` (mises à jour via les
Releases GitHub).

Hors de l'appli : `cartes/` (fabrication des cartes hors ligne : profil Planetiler, contours des régions ; `cartes/style` : styles
des deux cartes, aperçu, contrôle),
`outils/` (essai sur émulateur, logo et images de la fiche Google Play, image d'aperçu du dépôt,
contrôle des textes de la fiche), `docs/` (nouveautés, politique de confidentialité, guide et fiche Google Play),
`.github/workflows/` (`build.yml` pour l'APK et le fichier Google Play, `cartes.yml` pour les cartes, `cle-play.yml`
pour l'envoi de la clé à Google, manuel).

## Architecture de l'appli

- **Injection à la main** : `AppContainer` crée les objets partagés à la demande ; les écrans y accèdent par
  `LocalContainer` et `veloViewModel { ... }`. Pas de Hilt ni de Koin.
- **Enregistrement** : `RecordingService` (service de premier plan de type `location`) alimente `RideRecorder`,
  qui fait tourner `RideEngine` (module `core`) sur un seul fil d'exécution, publie un `StateFlow<RideSnapshot>`
  pour l'écran et sauvegarde la trace toutes les 8 s ou 10 points. Les commandes (démarrer, pause, reprendre,
  terminer) passent par une file et sont traitées une par une. Une sortie restée « en cours » en base est
  reprise au redémarrage du service ou proposée à la reprise à l'ouverture de l'appli.
- **Position** : `GpsSource` (le `LocationManager` d'Android, sans bibliothèque Google), partagée ; tout s'éteint
  quand plus personne n'écoute. Deux flux : `fixes` = le GPS brut, 1 Hz, **seule source de l'enregistrement et du
  guidage** ; `positions` = les mêmes, plus une **position approchée** (`GpsFix.approximate` : fournisseur
  « fused » d'Android 12 et suivants, sinon « network ») tant que le GPS n'a pas trouvé ses satellites : c'est ce
  que montre la carte. `core/geo/PositionArbiter` décide quand s'en servir et quand aider le GPS ; `GpsSource.status`
  (`GpsStatus` : localisation ou GPS coupés, satellites captés et utilisés, recherche en cours) alimente la
  pastille du haut de l'écran et la feuille « Signal GPS » (`ui/ride/GpsSignal.kt` : `gpsChip`, `gpsSheet`,
  fonctions pures). Tout l'état d'une écoute (`GpsSource.Session`) vit sur le fil `velogo-gps`.
  Altitude convertie au niveau de la mer (`AltitudeConverterCompat`). Chaque position du GPS porte le **sens de
  la marche** (`GpsFix.courseDeg`, calculé par `core/geo/CourseEstimator`) : le cap du GPS quand on roule, sinon
  celui déduit du déplacement, et à l'arrêt le dernier connu ; une position approchée n'en a pas.
- **Carte** : `VeloMap` (MapView dans `AndroidView`, mode SurfaceView) pilotée par `VeloMapState`, qui garde ce
  qui doit être dessiné et le redessine après chaque changement de fond. La trace en direct est découpée en
  morceaux figés + une « queue » de 300 points pour rester fluide après des heures. La position est un point
  tant que le sens de la marche est inconnu, puis une flèche (`map_puck_dot`, `map_puck_arrow`). Pendant un
  guidage, `setNavigation(true)` met la carte en **vue de guidage** : suivi dans le sens de la marche
  (`CameraMode.TRACKING_GPS`), inclinée (50°), rapprochée, position descendue vers le bas de l'écran
  (`setNavigationInsets`). `fit` cadre une emprise vue de dessus ; `onUserMove` et `onRouteTapped` préviennent
  l'écran quand on déplace la carte ou qu'on touche un itinéraire proposé. Une position floue (plus de 150 m,
  donc une position approchée) cadre le suivi plus large, pour que son cercle d'incertitude tienne dans l'écran
  (`followZoom`) ; la carte se resserre à la première position fine. Itinéraires et traces sont glissés **sous
  les noms** du fond (`firstLabelLayerId`) ; repères, étapes et flèche de manœuvre restent par-dessus. Pendant un
  guidage : `setRouteProgress` grise ce qui est parcouru, `setManeuverArrow` dessine la prochaine manœuvre.
- **Styles de carte** : le fond « Plan » (en ligne) et le fond « Vélo hors ligne » sont deux styles MapLibre
  fabriqués par `cartes/style/generer.mjs` à partir d'une même palette (`THEMES`, clair et sombre) et embarqués
  dans l'appli (`assets/en-ligne/style-*.json`, `assets/hors-ligne/style-*.json`). En ligne : tuiles vectorielles
  d'OpenFreeMap (schéma OpenMapTiles, zooms 0 à 14), relief de Mapterhorn, polices d'OpenFreeMap, aucune planche
  d'icônes. Hors ligne : couches du fond Protomaps recolorées (`saveurProtomaps`, `retoucher`) + surcouche vélo.
  `apercu.mjs` les rend en image dans un navigateur sans écran, `controler.mjs` les valide.
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
  est dans `core/nav` : `RouteFollower` (progression, sortie d'itinéraire, `snap` : la position ramenée sur le
  tracé), `GuidanceAnnouncer` et `GuidancePhrases` (quand parler, et quoi dire), `StreetNamer` (le nom des rues,
  lu dans les tuiles de la carte hors ligne par `core/tiles/VectorTile` : `RouteService` l'applique à chaque
  itinéraire calculé, `Instruction.street`). `VoiceGuide` est le seul endroit qui touche au son.
  L'écran « Rouler » affiche `PlannerPanel` en bas de la carte et `GuidanceBanner` sous la barre du haut.
  Une fois les itinéraires proposés, le panneau est **replié par défaut** (propositions compactes + bouton de
  départ) et la carte se cadre sur le trajet dans la place restante ; « Détails » ou la poignée le déplient.
  **Boucle** (`PlannerState.loop`) : l'arrivée est le point de départ, les étapes disent par où passer, dans
  l'ordre de la liste (numérotées sur la carte, réordonnables). `core/nav/RouteShape` reconnaît un itinéraire qui
  repasse par son départ puis continue ; le panneau l'annonce (`StartReturnNotice`) et propose de l'y arrêter
  (`NavigationController.stopAtStart`, `Route.truncatedAt`).
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
- **Deux distributions** (`productFlavors`, dimension `distribution`) : `github` (APK des Releases) et `play` (AAB
  pour Google Play), même identifiant `fr.velogo.app`, même clé, même code. Deux drapeaux `BuildConfig` font la
  différence : `SELF_UPDATE` (recherche de mise à jour dans les Releases : `AppContainer.start`,
  `SettingsContent`) et `DIRECT_BATTERY_EXEMPTION` (fenêtre d'Android « ne pas mettre en veille » contre fiche de
  l'appli : `batteryExemptionIntent`, `batteryHintText`). L'autorisation `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  n'est déclarée que dans `src/github/AndroidManifest.xml`. Variantes : `githubDebug` (la seule à porter les
  tests), `githubRelease`, `githubSmoke`, `playRelease`, `playSmoke` ; `playDebug` est désactivée. Les écrans qui
  diffèrent prennent le drapeau en paramètre, pour être rendus dans les deux versions par les tests.
- **Confidentialité** : `docs/CONFIDENTIALITE.md` est la seule source ; la tâche Gradle `copyPrivacyPolicy` la
  recopie dans les ressources de l'appli (`confidentialite.md`), `parsePolicy` la lit (titres, paragraphes, listes :
  n'y mettre rien d'autre) et `PrivacyScreen` l'affiche (Réglages > Confidentialité). Avant de demander la position,
  l'appli dit ce qu'elle en fait (`LOCATION_DISCLOSURE` : carte sur la carte tant que l'autorisation manque,
  fenêtre quand on démarre une sortie sans elle).
- **Thème et verre** (`ui/theme`) : les trois couleurs du logo (`Brand`), un fond d'écran gris à peine vert et des
  cartes blanches (`surface` = fond des écrans, `surfaceContainerLow` = cartes et feuilles,
  `surfaceContainerHighest` = ce qui est en creux sur une carte ou un panneau). `Glass.kt` : `GlassStyle` et les
  quatre verres (`regular`, `panel`, `accent`, `deep`, fournis par `LocalGlass`), `Modifier.glass` (fond, reflet,
  filet, ombre faite d'anneaux), `GlassBox`, `bounce`, `bleed`. `Shape.kt` : coins continus
  (`continuousRoundRect`, `ContinuousShape`). Composants : `VeloCard` (carte à coins continus, sans rognage),
  `Segmented` (sélecteur à pastille glissante), `VeloSwitch`, `GlassIconButton`, `GlassControlStack`, `GlassPill`,
  `AccentButton`, `LargeTitleBar`. La barre d'onglets (`FloatingTabBar`, dans `AppRoot`) est une pilule de verre
  flottante ; sous elle, `BottomFade` estompe les listes.
- **Textes** : interface uniquement en français, textes écrits directement dans les composables (seuls ceux
  du service et des notifications sont dans `strings.xml`).
- **Écrans testables** : chaque écran a un composable « contenu » sans accès aux données (`RideChrome`,
  `HistoryContent`, `RideDetailBody`, `SettingsContent`, `OfflineMapsContent`, `PlannerPanel`, `PlaceSearchContent`,
  `SavedRoutesList`, `SavedRoutesContent`, `ShareRouteContent`, `WeatherContent`, `StatsContent`,
  `SegmentsContent`, `SegmentDetailBody`, `SegmentEditor`, `GpsSheetContent`, `PrivacyContent`) rendu en image par
  `ScreenshotTest`. L'habillage de la carte y est rendu sur un vrai morceau de carte (`fond_carte.jpg` et
  `fond_carte_sombre.jpg` : le style « Plan » rendu à Corte par `cartes/style/apercu.mjs`, `TAILLE=360x800`) avec la barre d'onglets, pour juger les surfaces translucides ; trois écrans le sont aux
  tailles d'une tablette. `RoutePosterTest` dessine l'affiche
  pour de bon et range ses images au même endroit (`affiche*.png`).
  Attention : une animation sans fin (barre de progression indéterminée) bloque la capture.
  `LogoTest` charge l'icône adaptative, l'icône de notification et le logo comme le fait Android, vérifie que
  le logo tient dans la zone toujours visible et range leurs images au même endroit (`icone_*.png`).
  `SavedRoutesContentTest` pilote l'onglet « Trajets » au doigt (`createComposeRule`). Limite constatée : une
  fenêtre (`AlertDialog`) qui contient un champ de saisie n'est jamais « au repos » pour cet outil sous
  Robolectric, même vide de tout code de l'appli ; ce genre de fenêtre se vérifie donc sur l'émulateur.

## Vérification sans téléphone

Il n'y a pas d'émulateur dans l'environnement de développement. Quatre niveaux de vérification :

1. `core` : tests unitaires des calculs (sorties simulées, références GeographicLib, tuiles PMTiles comparées à
   l'outil officiel `pmtiles`). `VectorTileTest` et `StreetNamerTest` lisent de vraies tuiles de Corte
   (`core/src/test/resources/tuiles` : quatre tuiles du zoom 15 réduites à leur couche `roads`, 28 Ko).
2. `app` : `ScreenshotTest` (Robolectric + Roborazzi) rend les écrans en PNG au format du Galaxy A05s ; tests du
   serveur de tuiles local, du catalogue et de `ReliefSource` (une vraie tuile de relief, altitudes comparées à
   celles du service de l'IGN) ; météo testée contre un faux serveur (intercepteur OkHttp, aucun réseau) ;
   segments, meilleurs temps et sauvegarde testés de bout en bout sur une vraie base Room en mémoire
   (`RideAnalysisTest`, `RideArchiveTest`), trajets enregistrés aussi (`SavedRouteRepositoryTest`) ;
   `DesignTest` vérifie les briques du thème (parts de l'ombre du verre, tracé des coins continus comparé à un
   rectangle arrondi classique, contrastes des couleurs du logo) ; `PrivacyPolicyTest`, que l'appli embarque bien
   le texte du dépôt et que son afficheur le lit sans reste de mise en forme ;
   `DatabaseMigrationTest` fabrique une base à l'ancien format d'après
   son schéma exporté, y range une sortie et l'ouvre avec l'appli d'aujourd'hui (à compléter à chaque nouvelle
   version de la base). Sur l'ordinateur, ces tests ouvrent la base avec le SQLite d'Android
   simulé par Robolectric (`AndroidSQLiteDriver`) : celui que l'appli embarque (`BundledSQLiteDriver`) n'a pas de
   bibliothèque native hors Android. `UpdateCheckerTest` lit des listes de Releases de la forme exacte de celles
   de GitHub (relevée sur la première version publiée). `GpsSourceTest` déroule la source de positions contre le
   `LocationManager` simulé de Robolectric sur **trois versions d'Android** (`@Config(sdk = [26, 30, 35])` : 8.0,
   la plus ancienne acceptée ; 11, la dernière sans fournisseur « fused » ; 15, celle du téléphone de référence) :
   les appels à Android diffèrent d'une version à l'autre, et c'est le seul moyen de les dérouler tous. Il
   vérifie aussi que le modèle de géoïde répond sur les trois (449,4 m à Corte pour 500 m « GPS »). Le temps y
   avance à la main (`shadowOf(gps.looper).idleFor`), les coroutines avec `UnconfinedTestDispatcher`.
3. `routing` : itinéraires calculés pour de vrai autour de Corte avec `src/test/resources/dalles` (80 Ko de
   données BRouter réelles) : profils, étapes, évitement des grands axes, consignes, données manquantes, boucle
   (départ et arrivée confondus), itinéraire qui repasse par son départ puis continue, et sa coupe ; noms de rue
   posés sur un vrai itinéraire gare → citadelle avec les tuiles réelles rangées dans `core` (« Avenue du
   9 Septembre » après le giratoire, « Route de Baliri », « Place d'Armes »).
   Pour reproduire un itinéraire d'une autre région (un écart signalé par l'utilisateur, par exemple) : les dalles
   `dalle-<carré>-<empreinte>.rd5` se téléchargent depuis la Release `cartes` (noms dans `catalogue.json`), se
   renomment `dalle-<carré>.rd5`, et un test temporaire du module `routing` les donne à `BRouterPlanner`. C'est
   ainsi que l'écart « 40 km annoncés, 31 roulés » a été expliqué (22/11/2026).
4. CI : le job « Essai sur émulateur » installe l'APK `smoke` sur un émulateur Android 15 et déroule
   `outils/essai_emulateur.py` : sortie simulée à Corte (positions envoyées par le fournisseur de test d'Android,
   plus fiable que le GPS simulé de l'émulateur), d'abord **GPS muet** (le fournisseur « fused », simulé lui aussi,
   annonce une position à 40 m près : la feuille « Signal GPS » doit le dire, « Relancer le GPS » ne rien casser),
   puis distance affichée, pause automatique, historique, fonds de carte,
   téléchargement de la carte de Corse, itinéraire vers une adresse (panneau replié puis déplié, enregistrement de
   l'itinéraire, affiche à partager), guidage (flèche au départ, vue de guidage en roulant, « Recentrer » après
   avoir déplacé la carte), onglet « Trajets » (le trajet enregistré s'y retrouve, se renomme, se rouvre sur la
   carte), puis en mode avion : carte, altitude, **boucle** (bouton « Boucle », étape placée par un appui long,
   calcul), itinéraire vers un point de la carte, guidage avec
   enregistrement ; reprise après arrêt forcé ; l'icône de l'appli sur sa fiche des réglages d'Android (le
   logo y est reconnu à ses trois couleurs) ; la **première ouverture** (autorisation de position retirée :
   l'explication « Où es-tu ? » doit s'afficher, citer ce qui sort du téléphone et ouvrir la politique de
   confidentialité) ; enfin la **version Play**, installée par-dessus l'autre (même clé de test, même
   identifiant) : elle doit démarrer, garder les sorties, **calculer un itinéraire** (`play_route` : depuis le
   point d'où le calcul sans réseau a déjà réussi), annoncer « Les mises à jour arrivent par Google Play. »
   et ne plus déclarer `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Cet APK-là n'est pas construit directement : la
   tâche `:app:packagePlaySmokeUniversalApk` fabrique le fichier AAB de la variante d'essai, puis l'outil de
   Google (bundletool, embarqué par AGP) en tire un APK « universel », comme le Play Store tire les siens du
   fichier publié. L'itinéraire est là pour ça : dans un AAB, les profils et `lookups.dat` de BRouter sont
   rangés à part du code (`base/root/`), et leur absence ne se verrait ni au démarrage ni sur la carte.
   Le démarrage de la première sortie est filmé (`enregistrement_d_une_sortie.mp4`, avec la notification
   d'enregistrement) : Google Play demande une vidéo du service de premier plan. Simple confort : si l'émulateur
   ne sait pas filmer, l'essai continue.
   Après la première sortie : création d'un segment (la sortie doit s'y retrouver), statistiques, liste des segments.
   Deux pièges rencontrés en ajoutant la première ouverture (fabrication n° 25) : retirer une autorisation à une
   appli ouverte la fait redémarrer **là où elle en était** (ici, sur le détail de la dernière sortie), pas sur la
   carte : il faut l'arrêter complètement d'abord (`am force-stop`) ; et `adb exec-out screencap` renvoie parfois
   une image tronquée (43 Ko au lieu de 10 Mo) : la lecture de l'écran pour l'icône est retentée une fois.
   Le service d'adresses de l'IGN ne répond pas toujours aux machines de la CI : la fabrication n° 28 a échoué
   là-dessus (« Recherche impossible : il faut du réseau »), sans que l'appli y soit pour rien. Depuis, l'appli
   retente d'elle-même une recherche qui échoue (`searchWithRetry`) et propose « Réessayer » ; le scénario s'en
   sert, deux fois au plus, avant de conclure à l'échec.
   Les fenêtres d'erreur d'Android sont écartées à chaque lecture de l'écran (`system_dialog`) : l'écran d'accueil
   de l'émulateur met parfois si longtemps à démarrer qu'Android affiche « Pixel Launcher isn't responding »
   devant l'appli, et tout le début de l'essai échouait sans que l'appli y soit pour rien (fabrication n° 21).
   Si c'est VeloGo qui ne répond plus, c'est un échec.
   Le réseau de l'émulateur n'est pas garanti. Fabrication n° 30 : son Wi-Fi ne résolvait aucun nom (Android ne
   le « valide » pas et passe par le réseau mobile, « 3G » dans la barre d'état) ; la carte de Corse, réservée
   au Wi-Fi, n'est jamais partie et tout ce qui en dépend a été sauté. Fabrication n° 31 : même Wi-Fi muet, et
   pas de réseau mobile du tout : fonds de carte vides, catalogue des cartes introuvable. Rien de cela ne vient
   de l'appli (même image de machine, même émulateur que la n° 29, qui avait tout réussi deux heures plus tôt).
   Trois parades : avant de commencer, le scénario attend un réseau qu'Android a validé, ou à défaut un nom qui
   se résout (`online`), le relance par le mode avion, et s'il ne vient pas l'écrit dans `essai/sans_reseau.txt` ;
   le workflow recommence alors **une fois** sur un émulateur neuf (étape `essai2`, puis « Verdict de l'essai » :
   un échec de l'appli, lui, n'est jamais rejoué) ; enfin, pour la carte, le scénario coupe lui-même le Wi-Fi
   (l'appli doit annoncer « En attente du Wi-Fi »), le rallume, et s'il n'est pas revenu au bout de 75 secondes,
   désactive « Wi-Fi uniquement » dans l'appli, qui doit relancer le téléchargement par le réseau mobile
   (`allow_mobile_data`).
   La saisie au clavier y est vérifiée (`type_text` relit le champ, complète ou efface) : envoyées pendant que le
   clavier démarre, les touches d'`adb shell input text` se perdent (fabrication n° 16 : « gare d » au lieu de
   « gare de corte », et tout l'essai de l'itinéraire en ligne sauté).
   La pastille ne dit « GPS ± » que tant que le GPS parle : quand le scénario attend un élément lié au GPS, il
   continue d'envoyer la position (`Gps.wait_for`).
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
- **Position approchée d'appoint, jamais enregistrée** : constat du 21/11/2026 sur un Xiaomi, localisation
  active : pastille « GPS… », carte restée sur la France entière, alors que la météo avait une position (la
  dernière connue du téléphone). L'appli n'écoutait que le GPS brut, muet en intérieur ou pendant un démarrage à
  froid. Règles de `PositionArbiter` : au début d'une écoute, quatre secondes d'avance au GPS (prêt, il répond
  tout de suite et la position approchée ne s'affiche jamais) ; GPS déjà reçu puis muet : relais après trente
  secondes, et seulement par une position à 200 m près ou mieux (une position déduite d'une antenne à un
  kilomètre ferait sauter la carte à chaque passage sous les arbres) ; position grossière acceptée seulement si
  le GPS n'a jamais répondu ou se tait depuis cinq minutes. Le fournisseur d'appoint n'est écouté que pendant
  que le GPS se tait. Le calcul d'un itinéraire « depuis ma position » s'en contente si elle est à 200 m près.
- **Demande de positions par l'appel historique d'Android** (`requestLocationUpdates(fournisseur, intervalle, 0,
  écouteur, looper)`) : le même sur toutes les versions (pour le GPS il vaut « précision maximale », pour les
  autres fournisseurs « précision moyenne »), sans l'appel par réflexion de méthodes cachées que fait
  `LocationManagerCompat` avant Android 12. Les écouteurs sont des `LocationListenerCompat` : avant Android 11,
  un écouteur sans `onProviderDisabled` plante quand on coupe la localisation.
- **Aide au GPS** : après vingt secondes de recherche, au plus toutes les dix minutes, `sendExtraCommand` demande
  au téléphone de redonner au GPS l'heure et la position prévue des satellites (`force_time_injection`,
  `force_psds_injection`, et `force_xtra_injection` son ancien nom ; celui qui n'est pas connu est ignoré).
  Autorisation ordinaire `ACCESS_LOCATION_EXTRA_COMMANDS`. `delete_aiding_data` (tout oublier) n'est envoyé que
  par le bouton « Réinitialiser le GPS », proposé après 90 s de recherche : il coûte une recherche à froid.
- **Instant des positions** : `Fix.monoMs` est celui qu'annonce Android (`elapsedRealtimeNanos`) s'il est
  plausible (de dix secondes avant la réception à une seconde après), sinon l'instant de réception. Certaines
  puces GPS l'annoncent faux, et `RecordingService` écarte les positions vieilles de plus de cinq secondes : sur
  un tel téléphone, rien ne se serait enregistré.
- **« GPS ± » seulement tant que le GPS parle** : après six secondes sans position (comme
  `RideEngine.SIGNAL_TIMEOUT_MS`), la pastille repasse à « Recherche GPS », sortie en cours ou non. Rouge au-delà
  de 35 m (`RideConfig.maxAccuracyM` : le moteur n'enregistre plus), orange au-delà de 15 m.
- **GPS coupé, localisation « active »** (modes économes d'avant Android 10) : `GpsSource.isGpsEnabled`. Au
  démarrage d'une sortie on prévient sans bloquer (« Démarrer quand même ») : une détection fausse sur un
  téléphone exotique ne doit pas rendre l'appli inutilisable.
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
- **Noms de rue lus dans la carte, pas dans l'itinéraire** : les données BRouter n'en contiennent pas, mais le
  fond Protomaps du téléphone, si (couche `roads`, attributs `name` et `ref`, zoom 15). Or un itinéraire ne se
  calcule que là où une région est téléchargée : la carte est donc presque toujours là. Pour chaque consigne,
  `StreetNamer` sonde le tracé tous les 13 m sur les 90 m qui suivent la manœuvre (150 m après un giratoire, dont
  la consigne est à l'entrée de l'anneau) : voie nommée la plus proche à moins de 14 m, dans la direction du tracé
  à 35° près ; le nom retenu doit rallier au moins un tiers des sondes (une sonde isolée tombe sur la rue qu'on
  croise). Quais de gare, escaliers, voies ferrées et bacs sont écartés. Sans nom net, la consigne reste sans
  nom. Bandeau : « À gauche · Rue de la Gare » (un point médian plutôt qu'une préposition, qui changerait d'une
  voie à l'autre) ; au giratoire « 2e sortie · … » ; deux consignes enchaînées restent sans nom. Voix : « Dans
  150 mètres, tourne à gauche, rue de la Gare » ; « D 81 » se dit « départementale 81 » (`speakable`). Les
  trajets enregistrés gardent les noms (champ `r` des consignes) ; ceux d'avant la 0.8 les retrouvent à
  l'ouverture (`showSaved`).
- **Position ramenée sur l'itinéraire** (`RouteFollower.snap`, `NavigationController.snapToRoute`) : tant qu'on
  est à moins de 25 m du tracé (ou d'une fois et demie la précision du GPS, sans dépasser les 40 m du « hors
  itinéraire »), la carte montre la position **sur** le tracé, avec la direction du tracé. Au-delà, ou une fois
  hors itinéraire, elle la montre telle quelle : l'écart doit se voir. La recherche se fait autour du segment où
  en est le suivi, comme `update` : sur un aller-retour, la flèche regarde du bon côté. Rien n'est changé à ce
  qui est enregistré ni au suivi lui-même.
- **Partie parcourue en morceaux, pas en dégradé** : `line-gradient` aurait suffi (un seul tracé, une seule
  propriété à changer), mais MapLibre Native le dessine dans une texture de 256 points pour toute la ligne : sur
  un itinéraire de 100 km, la limite entre gris et bleu serait floue sur 400 m. Le tracé parcouru est donc un
  second trait gris posé sur l'itinéraire, envoyé à la carte par morceaux de 250 segments (les morceaux complets
  une fois pour toutes, le dernier à chaque position), comme la trace en direct.
- **Styles : ce que l'aperçu accepte et pas le téléphone.** L'aperçu tourne sur MapLibre GL JS, plus permissif
  que MapLibre Native : un pointillé (`line-dasharray`) qui dépend de l'objet dessiné s'y affiche, alors
  qu'Android ne le prend pas en charge (ticket maplibre-native n° 744). `controler.mjs` lit le tableau
  « sdk-support » de la spécification et refuse ces cas ; `MapStylesTest` vérifie le reste sans téléphone
  (sources, polices réellement disponibles, aucune adresse réseau dans le style hors ligne).
- **Itinéraires balisés sous les routes, en couleur pleine** : en surlignage translucide par-dessus, plusieurs
  itinéraires passant par la même rue s'empilaient en un trait épais et sombre (vu à Annecy). Une bande pâle et
  opaque glissée sous les routes dépasse de chaque côté et ne s'additionne pas.
- **Pas de planche d'icônes en ligne** : les points utiles (eau, atelier, abri…) sont des pastilles de couleur,
  comme sur la carte hors ligne ; une requête de moins, et rien qui dépende du jeu d'icônes d'OpenFreeMap.
- **Itinéraires enregistrés à six décimales** (`RouteCodec`, `Polyline.FINE`, dix centimètres) : à cinq décimales
  (un mètre), chaque point bougeait un peu et l'itinéraire relu n'avait plus tout à fait sa longueur (de −1 à
  +10 m mesurés sur des itinéraires de 1 à 6 km autour de Corte) ; vu sur l'émulateur, un trajet de « 2,4 km »
  dans la liste se rouvrait à « 2,5 km ». Le champ `decimales` du texte enregistré vaut 5 quand il est absent :
  les itinéraires des versions précédentes se relisent comme avant. Les vignettes restent à cinq décimales.
- **Un trajet enregistré se suit tel quel** : rouvert, il garde ses consignes de virage mais n'est jamais
  recalculé si on s'en écarte (comme une trace GPX importée) : recalculer vers l'arrivée d'une boucle la
  réduirait à rien.
- **Un itinéraire peut cacher des kilomètres sous son propre tracé** : constat du 22/11/2026, trajet
  « Entraînement 1 » annoncé 40,1 km (2 h 03) pour une sortie de 31,2 km. Les deux chiffres étaient justes :
  l'arrivée était restée à Bouffémont (premier point posé) et le retour à la maison avait été ajouté comme
  étape. L'itinéraire faisait la boucle (29 km), repassait par la maison, puis repartait vers Bouffémont par les
  routes du début (11 km), invisibles sur la carte sous le premier passage. Refait avec les dalles
  d'Île-de-France : boucle seule 28 à 31 km, boucle + retour vers Bouffémont 36 à 42 km selon le vélo.
  D'où trois réponses : le bouton **Boucle** (l'arrivée est le départ ; en boucle, un appui long ajoute une
  étape, il faut au moins une étape) ; les **étapes numérotées** sur la carte (images dessinées par l'appli,
  `stepNumberImage` : tous les fonds de carte n'apportent pas de police) et réordonnables ; l'**avertissement**
  « Repasse par le départ après X, puis continue Y ».
- **Retour au départ** (`RouteShape.returnToStart`) : l'itinéraire s'éloigne de plus de 500 m, repasse à moins
  de 200 m de son premier point et continue au moins 1 km. 200 m, parce que « ma position » et une étape
  « maison » posée au doigt ne s'accrochent pas forcément à la même rue. Jamais pour une boucle (un « huit » a
  le droit de repasser par son départ : vu sur le calcul réel gare de Corte → Restonica → sud → gare) ; s'il y
  a plusieurs passages, le dernier compte. « Arrêter au départ » : un itinéraire en préparation devient une
  boucle avec les étapes atteintes avant ce retour, et se recalcule ; un trajet enregistré est coupé tel quel
  (`Route.truncatedAt` : durée au prorata de la distance, dénivelé au prorata du profil gardé, répartition par
  nature de voie abandonnée plutôt qu'inventée) et remplace l'ancien après confirmation
  (`SavedRouteRepository.replace`, `PlannerState.savedId`).
- **Fermer un trajet enregistré le range** (`closePlanner`) : sans cela le bouton « Itinéraire » le rouvrait
  indéfiniment et il n'y avait plus moyen de préparer autre chose depuis la carte.
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
- **Un téléchargement « en attente » ne dit pas pourquoi** : tant que la condition demandée n'est pas remplie
  (Wi-Fi, pas d'itinérance), le `DownloadManager` des Android récents annonce `STATUS_PENDING`, pas
  `PAUSED_QUEUED_FOR_WIFI` ; l'écran restait sur « Le téléchargement va démarrer… » sans fin (vu sur l'émulateur
  de la fabrication n° 30, qui n'avait que le réseau mobile). `PackDownloader` regarde donc lui-même le réseau
  (`pendingReason` : pas de réseau, réseau compté comme un forfait avec « Wi-Fi uniquement », itinérance) et le
  dit. La règle d'un téléchargement déjà demandé ne se change pas : quand « Wi-Fi uniquement » change,
  `applyWifiOnly` redemande ceux qui n'ont encore rien reçu (l'ancien est retiré d'abord, car Android efface son
  fichier, qui porte le même nom) ; ceux qui ont commencé gardent leur règle, pour ne pas perdre ce qui est arrivé.
- **Appli publique : ce que permettent les services** (conditions relevées sur leurs pages le 26/11/2026, quand
  l'auteur a décidé de publier). OpenFreeMap : aucune limite, usage commercial admis, mention « © OpenMapTiles »
  obligatoire (« OpenFreeMap » facultatif). IGN Géoplateforme : les CGU visent les applis « à des fins commerciales
  ou non », sans clé ; limites par adresse IP et par seconde (géocodage 50, autocomplétion 10, altimétrie 5, tuiles
  sans limite). Open-Meteo : gratuit pour une appli **sans publicité ni abonnement** ; 10 000 appels par jour,
  comptés « by IP addresses and application », et leur dépôt demande d'écrire si « your application » les dépasse
  (une demande pour plusieurs lieux compte plusieurs appels). OSM France (CyclOSM) : applis gratuites, sans but
  lucratif, User-Agent complet, « trafic modéré » non chiffré, coupure possible sans préavis, mention avec un
  lien vers osm.org/copyright. GitHub : pas de limite de bande passante publiée pour les Releases, mais une clause
  contre l'usage « nettement excessif ». Protomaps et Mapterhorn : redistribution permise. Conséquences dans
  l'appli : `MapLayer.attributionUrl` (un appui sur la mention ouvre la page des droits), lien sous la météo,
  `ROAD_NOTICE` dans « Sources et licences ». Le tableau pour l'auteur est dans `docs/PLAY_STORE.md`. **Si
  l'appli devenait payante ou financée par la publicité, Open-Meteo et CyclOSM ne seraient plus permis tels quels.**
- **Mapbox : non retenu, décision laissée à l'auteur (27/11/2026).** Il a demandé une carte « exactement comme
  Strava, les mêmes fournisseurs, donc OpenStreetMap et Mapbox ». Relevé sur les pages de Strava et de Mapbox le
  jour même :
  - Strava : « maps … are provided by Mapbox, with the data … sourced from OpenStreetMap », et ses itinéraires
    sont les siens (OpenStreetMap + ses données de fréquentation), pas ceux de Mapbox. VeloGo a déjà les mêmes
    données et calcule aussi ses itinéraires lui-même ; MapLibre est le moteur libre issu de celui de Mapbox ;
  - compte Mapbox obligatoire, jeton public `pk.…` dans l'appli ; sans moyen de paiement, essai limité à 100
    utilisateurs actifs par mois (20 pour le guidage) ; avec carte bancaire, carte gratuite jusqu'à 25 000
    utilisateurs actifs par mois puis 4 $ les 1 000 ; Navigation SDK gratuit jusqu'à 100 utilisateurs puis 0,30 $
    par utilisateur et 0,08 $ par trajet au-delà de 1 000 ;
  - Product Terms du 20/10/2026 : le SDK mobile de Mapbox est le **seul** accès permis depuis une appli mobile
    (§ 2.9.1 : pas de tuiles ni de styles Mapbox dans MapLibre ; § 2.11 : un style fait dans Studio ne s'affiche
    que sur une carte Mapbox), il envoie « location and usage data » à Mapbox et on ne doit pas l'en empêcher
    (désinscription à offrir à l'utilisateur) ; hors ligne limité aux tuiles hébergées par Mapbox, 750 paquets
    au plus ; logo Mapbox, « © Mapbox », « © OpenStreetMap » et « Improve this map » obligatoires ; interdit de
    multiplier les comptes pour rester gratuit ; SDK de moins de douze mois exigé.
  Conséquence : le vrai Mapbox voudrait le compte et la carte bancaire de l'auteur, remplacerait MapLibre,
  retirerait les cartes hors ligne du fond principal, ferait refaire la politique de confidentialité, et
  deviendrait payant avec le succès : contraire à « aucune API payante » et à « préviens-moi avant toute clé ».
  Fait à la place (0.8) : une carte redessinée sur les mêmes données et un guidage complété. **Ne pas ajouter
  Mapbox sans que l'auteur le redemande en connaissance de cause** ; le relevé (pages et PDF) n'est pas versionné :
  le refaire avant de s'y fier.
- **Relief en ligne par Mapterhorn** (`tiles.mapterhorn.com`, tuiles terrarium WebP de 512 points, de 45 à 180 Ko
  pièce ; le style s'arrête au zoom 11, où une tuile couvre quatorze kilomètres, pour ménager les forfaits) : leur FAQ ouvre le serveur à tous les usages (« for commercial and non-commercial purposes »),
  sans limite de débit annoncée, cache permis ; mention des sources demandée (pour la France : l'IGN), d'où
  « Mapterhorn » sur la carte et dans « Sources et licences ». C'est un tiers de plus qui voit la zone de carte
  affichée : la politique de confidentialité le dit. Projet bénévole : s'il fermait, le plan perdrait ses ombres.
- **Google Maps : écarté par l'auteur le 27/11/2026.** Il avait demandé « que la map utilisée soit celle de
  Google Maps » ; devant ce que cela impose (ci-dessous), entre quatre possibilités (fonds Google en plus, bouton
  vers l'appli Google Maps, tout remplacer, ne rien changer), il a choisi **de ne rien changer** : les cartes
  restent OpenStreetMap et IGN. Ne pas y revenir sans qu'il le redemande. Faits relevés sur les pages de Google
  le 26/11/2026 (conditions « EEA », celles d'un compte de facturation en France) :
  - afficher une carte Google par le Maps SDK for Android est gratuit et sans plafond tant qu'on n'utilise pas
    de « map ID » (SKU « Maps SDK » ; avec un map ID : 10 000 chargements gratuits par mois, puis 7 $ les 1 000) ;
    il faut tout de même un projet Google Cloud **avec facturation activée** (carte bancaire) et une clé ;
    Google Play services est requis sur le téléphone ;
  - **aucun usage hors ligne** : ni cache ni stockage des tuiles (« No Caching », « No Scraping ») ; le SDK n'a
    pas de couche vélo sur Android (elle n'existe qu'en JavaScript) et n'accepte que des tuiles en images en
    surcouche ;
  - **guidage** : les conditions EEA n'interdisent plus de « recréer » Google Maps, mais imposent des « Safety
    Requirements » à tout guidage en temps réel sur une carte Google avec un itinéraire qui ne vient pas de
    Google : tracé aligné à moins de 10 m des routes que dessine la carte Google, contrôle continu, consignes
    jamais contraires à ce que montre la carte, recalcul toutes les 15 s, mention « Routing provided by … ».
    Impossible à garantir avec des itinéraires OpenStreetMap (chemins que Google ne dessine pas, pas d'accès à
    sa géométrie) : **un guidage ne doit pas tourner sur un fond Google** ;
  - mentions : logo et copyright Google jamais masqués (marge de carte), et les conditions de l'appli doivent
    renvoyer aux « Google Maps End User Additional Terms » et à la politique de confidentialité de Google ;
    Google reçoit l'adresse IP, des données de l'appareil, un identifiant pseudonyme du SDK et des coordonnées :
    la politique de confidentialité et la fiche « Sécurité des données » seraient à refaire ;
  - Navigation SDK : réservé aux « commercial applications », remplace toute la carte, 1 000 destinations
    gratuites par mois puis 25 $ les 1 000, pas de hors ligne, itinéraire imposé par Google : écarté ;
  - Map Tiles API (tuiles Google dans MapLibre) : 100 000 tuiles gratuites par mois (2 500 à 10 000 écrans pour
    tous les utilisateurs réunis), puis 0,60 $ les 1 000 ; pas de satellite pour un compte EEA : payant dès
    que l'appli a du public, donc contraire à la règle « aucune API payante » ;
  - sans compte ni clé : passer la main à l'appli Google Maps (`google.navigation:q=…&mode=b`).
  Le relevé complet (148 citations, adresses et dates) n'est pas versionné : le refaire avant de s'y fier, les
  prix et les conditions de Google changent souvent.
- **Portrait sur téléphone, tout sens sur grand écran** : `screenOrientation="portrait"` reste déclaré, mais à
  partir d'Android 16 visé, Android n'en tient plus compte quand l'écran fait au moins 600 dp de large (tablettes,
  pliables), sans dérogation possible en visant Android 17. L'appli s'y adapte donc : l'écran guidon a sa
  disposition paysage, et ce qui est pensé pour un téléphone garde une largeur bornée, au milieu (`COMPACT_WIDTH`
  520 dp : barre d'onglets, commandes, bandeau de guidage, panneau d'itinéraire ; `READING_WIDTH` 720 dp : listes
  et fiches). L'activité gère elle-même les changements de taille (`configChanges`) : rien n'est perdu en tournant
  ou en dépliant l'écran. Vérifié en images seulement (`tablette_*.png`).
- **R8** : la release est minifiée. Les clés de navigation (`*Key`) sont gardées explicitement.
- **Une fenêtre plein écran a sa propre barre d'état** : la recherche d'adresse est un `Dialog`, donc une fenêtre à
  part ; ses icônes de barre d'état restaient blanches sur fond clair tant que le thème n'y était pas répété
  (`DialogWindowProvider`, `isAppearanceLightStatusBars`). Défaut ancien, vu sur les captures de l'émulateur.
- **Identité** (refaite en 0.7 à la demande de l'auteur : « plus moderne, arrondis style iOS, un peu de Liquid
  Glass, ultra fluide, mêmes couleurs que le logo ») : les trois couleurs du logo et rien d'autre pour l'interface.
  Vert foncé `#014A38` (texte fort, boutons pleins, bandeau de guidage), vert vif `#01CD95` (ce qui est choisi ou
  en marche : « Démarrer », onglet actif, interrupteurs, pastilles des sélecteurs ; le texte posé dessus est vert
  foncé, le blanc n'y serait pas lisible), rose `#E70180` (trace, « Terminer », pente qui monte). Trois couleurs
  de fonction restent hors logo parce qu'elles servent à lire : le **bleu** de l'itinéraire à suivre (la seule
  teinte qui tranche sur tous les fonds de carte) et de la pluie, l'**ambre** de ce qui demande attention (pause,
  signal moyen, hors itinéraire, soleil), le rouge des erreurs. Police Barlow Condensed (OFL) pour les chiffres et
  les titres, icônes Material Symbols (Apache 2.0). Thème « plein soleil » noir sur blanc pour l'écran guidon :
  tuiles blanches cernées de noir, verre opaque sans ombre (`SunlightGlass`).
- **Verre dessiné, pas flouté** : un vrai « Liquid Glass » floute en direct ce qui est derrière lui. La carte est
  dessinée par MapLibre dans une `SurfaceView`, que Compose ne peut ni lire ni flouter ; la passer en
  `TextureView` pour la flouter coûterait une copie de l'image à chaque trame, sur un Adreno 610 à 90 Hz. Le verre
  est donc fait de rectangles arrondis : fond translucide en dégradé, liseré clair (le reflet), filet sombre,
  ombre. Comme la carte vit dans sa propre surface, la faire glisser ne redessine rien de l'interface.
- **Ombre du verre en anneaux** : l'ombre d'élévation d'Android se voit à travers une surface translucide (elle
  est dessinée dessous) et Robolectric ne la rend pas. `Modifier.glass` trace dix anneaux qui partent tous du bord
  et vont de plus en plus loin (opacité cumulée en carré de la distance restante, `glassShadowShares`), décalés
  vers le bas, et rognés à l'extérieur de la forme. Ce sont des contours de rectangles arrondis, que le
  processeur graphique trace sans calcul de géométrie ; ce que montrent les images de test est ce que dessine le
  téléphone. Aucun `BlurMaskFilter`, aucun `RenderEffect`, aucun calque hors écran.
- **Coins continus pour dessiner, rectangles arrondis pour rogner** : les cartes des listes ont les coins des
  rectangles arrondis d'iOS (trois courbes de Bézier par coin, relevées point par point ; le rayon est borné au
  tiers du petit côté). Rogner selon un tracé quelconque coûte cher : `VeloCard` remplit son tracé sans rien
  rogner et fonce sous le doigt au lieu de montrer une onde. Tout ce qui a une ombre, rogne ou reçoit une onde
  (verre, boutons, vignettes) reste un rectangle arrondi ou une pilule.
- **Un fondu rogne ce qui dépasse** : pendant un fondu, Android dessine l'élément dans une image à sa taille, et
  l'ombre du verre, qui en déborde, disparaît puis revient d'un coup. La barre d'onglets entre donc en glissant,
  sans fondu, et les commandes de la sortie agrandissent leur zone animée (`Modifier.bleed`).
- **Habillage de la carte non dessiné quand il est recouvert** : l'écran « Rouler » reste composé sous les autres
  onglets ; son habillage serait retracé pour rien à chaque image d'une liste qui défile. `RideScreen` coupe son
  dessin (`drawWithContent`) 450 ms après le changement d'onglet, le temps du fondu.
- **Textes et descriptions de l'interface = contrat avec l'essai sur émulateur** : `outils/essai_emulateur.py`
  retrouve les éléments par leur texte exact ou leur description. Un bouton refait doit garder les deux (les
  composants de verre réunissent le texte de leur contenu comme le faisait `Button`).
- **Logo** : fourni par l'auteur le 21/11/2026, en deux images gardées telles quelles dans `docs/logo/`
  (`logo-source.png` : le mot « VeloGo » dont « oGo » dessine un vélo, un repère de carte et un trait rose, sur
  fond blanc ; `icone-source.png` : le même sur un carré arrondi « verre » au décor de carte). Trois couleurs :
  vert foncé `#014A38`, vert vif `#01CD95`, rose `#E70180` (celui de la trace sur la carte). `outils/logo.py`
  fabrique tout ce qui le montre : logo détouré (`logo_velogo`, toujours posé sur une plaque blanche dans
  l'appli, car son vert foncé ne se lit pas sur fond sombre), icône adaptative, écran de démarrage, icône de
  notification, images pour GitHub. Ne jamais retoucher ces fichiers à la main : changer les images d'origine ou
  l'outil, puis relancer.
- **Icône adaptative en deux couches** plutôt que l'image de l'icône telle quelle : un téléphone découpe l'icône
  à sa forme (carré très arrondi chez Samsung, rond sur un Pixel), et le carré arrondi dessiné dans l'image
  aurait laissé des coins blancs. Le fond est le décor de carte, étendu à toute la couche et débarrassé du logo
  qui y était peint ; le premier plan est le logo net, tenu dans le rond de 66 dp que toutes les formes laissent
  voir (62 dp de large sur les 72 visibles). La couche monochrome (icônes « à thème » d'Android 13+) est sa
  silhouette. L'écran de démarrage montre le même premier plan en grand (`splash_logo`) sur fond clair, même
  en thème sombre.
- **Icône de notification** : le vélo du logo (roues, « G », cadre) en silhouette blanche ; Android n'en garde
  que l'opacité, le logo entier n'y serait qu'une tache.

## Signature et livraison

- Clé : `signing/velogo-release.p12` (PKCS12, RSA 4096, chiffrée AES-256 par un mot de passe aléatoire de
  40 caractères). **Le mot de passe n'est que dans le secret GitHub `VELOGO_KEYSTORE_PASSWORD`** ; ne jamais
  l'écrire dans le dépôt.
- Le workflow vérifie le secret dès le début (`keytool -list` sur la clé du dépôt) : absent, il prévient et
  continue sans publier ; présent mais faux, il le dit tout de suite (avec sa longueur) et fait échouer le job
  après les tests. Les espaces et retours à la ligne collés autour du mot de passe sont ignorés, dans le
  workflow comme dans `app/build.gradle.kts`.
- `.github/workflows/build.yml` : à chaque push sur une branche de travail (`claude/**`), tests + lint des deux versions, APK signé et fichier AAB pour
  Google Play (job `apk`, qui vérifie que les deux portent la même signature et que l'APK accepte les pages
  mémoire de 16 Ko), essai sur émulateur des deux versions (job `essai`), puis, **seulement si les deux ont
  réussi**, publication en Release par le job `publication` : `VeloGo-x.y.z.apk` et `VeloGo-x.y.z-play.aab`
  (`v<majeur>.<mineur>.<numéro de build>`), une vingtaine de minutes après le push : ce qui arrive sur le
  téléphone a tourné sur un vrai Android. `versionCode` = numéro de build GitHub, donc chaque APK s'installe
  par-dessus le précédent. Le mineur de `version.properties` suit les jalons.
- Les notes de version viennent de la première section de `docs/NOUVEAUTES.md`.
- **`[sans publication]`** dans le message du dernier commit poussé : toute la fabrication est déroulée (tests,
  APK signé, essai sur émulateur) mais le job `publication` est sauté. Pour ce qui ne change pas l'appli (essai
  sur émulateur, outils, workflow) : une version identique à la précédente ne ferait que proposer une mise à
  jour pour rien. `[skip ci]`, lui, ne lance rien du tout (notes, README).
- Première version publiée : `v0.5.11` (21/11/2026). Pour contrôler un APK publié :
  `apksigner verify --print-certs` doit donner le certificat `CN=VeloGo` d'empreinte SHA-256
  `1e80bd8e20912aaae8f62310cff7fd004f58e605a265d6b193161fb29193768b` (signature v2 seule, normal avec minSdk 26).
- L'appli cherche ses mises à jour dans la liste des Releases (`UpdateChecker`) : étiquettes `v1.2.3` avec un
  fichier `.apk`, le plus grand numéro de build l'emporte ; la Release `cartes` est ignorée. La version Play ne
  cherche rien (`BuildConfig.SELF_UPDATE`) : le magasin interdit toute mise à jour qui ne passe pas par lui.
- **Google Play** : le guide est `docs/PLAY_STORE.md` (règles relevées sur les pages de Google le 26/11/2026,
  avec leurs liens), la fiche `docs/play/fiche.md`, les images `docs/play/` (fabriquées par `outils/logo.py`).
  Rien n'a été fait dans une Play Console : il faut le compte de l'auteur (25 $, pièce d'identité, test fermé à
  12 testeurs pendant 14 jours pour un compte personnel).
- **Même clé sur Google Play** : pour que l'appli du magasin et celle des Releases se remplacent sans
  désinstallation, la Play Console doit recevoir une copie de la clé du dépôt, chiffrée par l'outil PEPK de Google
  avec la clé publique qu'elle fournit. Le workflow manuel `cle-play.yml` le fait (confirmation à taper, empreinte
  de l'outil fixée, artefact gardé un jour). **Jamais exécuté** : il faut une vraie Play Console pour obtenir la
  clé publique, et l'outil n'a pas pu être lancé dans l'environnement de développement (ses options viennent de
  son fichier d'aide, lu sans l'exécuter). Ne jamais le lancer soi-même : c'est à l'auteur de décider.
- **Verrou de veille pendant une sortie** (`RecordingService`, « wake lock ») : gardé dans les deux versions.
  Google le compte dans les « wake locks excessifs » d'Android vitals (deux heures par jour chez plus de 5 % des
  utilisateurs) et sa documentation le dit superflu à côté d'un service de localisation ; mais l'enlever sans
  l'avoir vérifié sur le Samsung de l'auteur risquerait des enregistrements coupés, ce qui serait bien pire
  qu'une alerte dans la Play Console. À reconsidérer si l'alerte apparaît.
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
  Clé « v2 » depuis `GpsSourceTest` (images Android 8 et 11 de Robolectric, téléchargées par les tests). À
  défaut de la mémoire exacte, la plus récente « v2 » sert de point de départ (`restore-keys`). Changer le numéro quand les tests simulent une
  nouvelle version d'Android : la clé ne suit que les fichiers Gradle.
- `outils/ci_outils_de_construction.sh` : premier appel à Gradle, trois tentatives (30 s puis 90 s d'attente),
  la dernière avec la cause exacte de l'échec.

## Page d'accueil du dépôt

- **`main` contient tout le code depuis le 27/11/2026**, à la demande de l'auteur (« Oui, fusionne ») : la page
  d'accueil du dépôt est le vrai README, la politique de confidentialité a son adresse stable
  (`…/blob/main/docs/CONFIDENTIALITE.md`, celle que demande Google Play) et les cartes hors ligne sont refaites
  le 4 de chaque mois (un rendez-vous de GitHub ne tourne que sur la branche par défaut). Jusque-là, `main` ne
  portait qu'une copie du README, aux liens tournés vers la branche de travail.
- **`main` suit la branche de travail, il ne la précède jamais.** Le travail se fait sur la branche de la
  session (`claude/…`), où chaque envoi est fabriqué et essayé ; une fois la fabrication réussie, `main` est
  avancée au même commit (`git push origin <branche>:main`, avance rapide seulement, jamais forcée). C'est la
  suite de la décision de l'auteur : sans cela, la page d'accueil et la politique de confidentialité
  vieilliraient à la première modification. Si `main` a reçu autre chose entre-temps (une retouche faite sur
  GitHub), la fusionner d'abord dans la branche de travail.
- Aucun workflow ne part d'un envoi sur `main` (`branches: ["claude/**"]` dans `build.yml` et `cartes.yml`) :
  ces commits ont déjà été fabriqués, et les refaire publierait une seconde version identique sous un autre
  numéro. Sur `main` ne tournent que le rendez-vous mensuel des cartes et les lancements à la main.
- Le logo en tête du README est `docs/logo/icone.png` (coins transparents : lisible en thème clair comme en
  thème sombre de GitHub). `docs/logo/partage.png` (1280 × 640) est prévue pour l'aperçu de partage du dépôt,
  que seul le propriétaire peut déposer (Settings > General > Social preview).
- `docs/apercu.jpg` (neuf écrans) se refait avec `python3 outils/apercu_readme.py <captures> docs/apercu.jpg` à
  partir de l'artefact `essai-emulateur` d'une fabrication réussie : ce sont de vrais écrans, pas des maquettes.
  La capture de la météo manque dans bien des essais (quota d'Open-Meteo sur les machines de GitHub) : on donne
  alors un second dossier de captures, plus ancien, où elle existe. L'image actuelle vient de la fabrication
  n° 27 (`v0.7.27`, la première version publiée au nouveau design).
- Les captures de la fiche Google Play (`docs/play/ecrans/`, huit écrans en JPEG) se refont de la même façon :
  `python3 outils/captures_play.py <captures>`.

## Fabrication des cartes hors ligne

`.github/workflows/cartes.yml` (manuel, le 4 de chaque mois, et à chaque envoi sur une branche de travail qui touche
`cartes/selection.txt`, le profil, les contours, `cartes/outils/catalogue.py`, `dalles.py` ou le workflow lui-même :
le 27/11/2026, une simple retouche du texte des notes dans `catalogue.py` a ainsi refait toutes les cartes, sans
dommage, en trois quarts d'heure) :

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

7. Nouveau design aux couleurs du logo (verre dessiné, barre d'onglets flottante, coins continus) et version
   pour Google Play (fichier AAB, politique de confidentialité, explication avant la demande de position, grands
   écrans) *(fait le 26/11/2026)*

8. Carte redessinée (styles « maison » en ligne et hors ligne, relief ombré partout) et guidage complété (noms
   de rue, flèche de manœuvre, position sur le tracé, partie parcourue grisée) *(fait le 27/11/2026)*

Mise à jour du 27/11/2026 (`v0.7.34`, fabrication n° 34) : l'auteur publie l'appli, qui n'est plus présentée
comme personnelle. Conditions des services relues pour une appli publique (toutes le permettent), mentions des
sources complétées et rendues cliquables, avertissement « Sur la route ». Google Maps étudié puis écarté par
l'auteur. Branche de travail fusionnée dans `main`. L'APK et le fichier AAB de la 0.7.34 ont été contrôlés après
téléchargement (certificat habituel, pages de 16 Ko). Non vérifié : l'appui sur la mention de la carte et le
lien de la météo n'ont été vus qu'en images, pas touchés sur l'émulateur.

Mise à jour du 26/11/2026 au soir (`v0.7.32`, fabrication n° 32) : la « version Play » de l'essai sur émulateur
est désormais l'APK que l'outil de Google tire du fichier AAB, et elle y calcule un itinéraire (vérifié deux
fois : n° 32 et n° 33). Sur Google Play, aucune appli publiée ne porte l'identifiant `fr.velogo.app` ni le nom
« VeloGo » (relevé sur le magasin français ; les marques déposées n'ont pas été vérifiées). Correction de
l'attente du Wi-Fi des cartes hors ligne, trouvée grâce à un émulateur sans Wi-Fi. Ce qui a été vu sur
l'émulateur : « En attente du réseau » (Wi-Fi coupé, son réseau mobile ne donnait pas Internet), puis le
téléchargement qui part seul au retour du Wi-Fi. Ce qui ne l'a pas été : l'annonce « En attente du Wi-Fi » elle-même
(il faudrait un réseau mobile qui marche, comme à la n° 30) et le secours `allow_mobile_data` ; les deux ne sont
couverts que par `PackDownloaderTest` et par l'image `cartes_hors_ligne.png`. La fabrication n° 33
(`[sans publication]`, la première à s'en servir) a tout déroulé sans rien publier.
`bundletool` n'a pas pu être lancé à part dans l'environnement de développement (téléchargement de ses
bibliothèques refusé) : la tâche d'AGP `:app:packagePlaySmokeUniversalApk` fait le même travail, hors ligne.

Mise à jour du 26/11/2026 (`v0.7.27`, fabrication n° 27, première version du jalon 7) : le scénario complet a
tourné sur l'émulateur Android 15 avec le nouveau design, sans qu'aucun de ses repères (textes, descriptions) ait
dû changer, ainsi que la première ouverture et la version Play installée par-dessus l'autre. L'APK et le fichier
AAB publiés portent le certificat `CN=VeloGo` habituel. Restent à confirmer par l'auteur : la lisibilité et la
fluidité sur le Galaxy A05s, et tout ce qui se passe dans la Play Console (aucun compte n'existe encore). Le
dossier des captures de la fiche s'appelle `docs/play/ecrans/` parce que `captures/` est ignoré par Git.
La fabrication n° 28 (explication sur la position rendue compacte pour les petits écrans) n'a pas été publiée :
son essai a buté sur le service d'adresses de l'IGN, muet à ce moment-là. La n° 29 (`v0.7.29` : la même, plus la
seconde tentative de recherche d'adresse et le bouton « Réessayer ») a tout réussi et a été publiée ; contrôlé sur
les fichiers téléchargés : certificat habituel pour l'APK et l'AAB, pages de 16 Ko, trois architectures dans
l'AAB, pas de `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dans la version Play. Les images du dépôt (aperçu du README,
captures de la fiche Google Play) sont restées celles de la n° 27 : aucun des écrans qu'elles montrent n'a changé.

Mise à jour du 22/11/2026 (`v0.6.23`, fabrication n° 23) : position approchée en attendant le GPS, pastille à
états et feuille « Signal GPS ». Le scénario « GPS muet » a tourné sur l'émulateur Android 15 ; rien n'a pu être
vérifié sur le Xiaomi où le problème a été vu. Si le GPS n'y répond toujours pas dehors, demander ce qu'affiche
la feuille « Signal GPS » (titre, satellites captés et utilisés) : c'est le seul diagnostic disponible.

Mise à jour du 22/11/2026 (`v0.6.24`, fabrication n° 24) : boucles (« Boucle », « Revenir au départ »), étapes
numérotées, avertissement « repasse par le départ » et « Arrêter au départ ». La boucle a été calculée sur
l'émulateur en mode avion (étape « 1 » dessinée sur la carte). Le trajet « Entraînement 1 » de l'utilisateur
n'a pas pu être relu (il est sur son téléphone) : son explication repose sur ses deux photos et sur le même
itinéraire refait avec les dalles d'Île-de-France ; à confirmer par ce qu'affiche le panneau quand il le rouvre.

## Sources de données et attributions

OpenStreetMap (ODbL) · OpenFreeMap / OpenMapTiles (tuiles du fond « Plan », « © OpenMapTiles » sur la carte) · CyclOSM (serveurs OSM
France : en ligne uniquement, appli gratuite et sans but lucratif, User-Agent identifiant l'appli, trafic modéré,
pas de téléchargement en masse) · IGN Géoplateforme (Plan IGN, photos aériennes,
adresses, altitude ; Licence Ouverte 2.0) · Open-Meteo (CC BY 4.0) · Protomaps (BSD, données OSM) · Mapterhorn
(MNT LiDAR HD et RGE ALTI® de l'IGN, Licence Ouverte 2.0 ; relief des deux cartes) · Noto Sans (OFL) · BRouter (MIT).
Les cartes IGN TOP 25 (SCAN 25) sont interdites aux particuliers : non utilisées.
