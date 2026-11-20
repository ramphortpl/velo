# VeloGo — notes d'architecture

Appli Android native de vélo, usage personnel, **France uniquement**, 100 % gratuite, sans compte.
Interface en français, unités métriques. Téléphone cible : Samsung Galaxy A05s (Android 15, arm64,
**ni baromètre, ni boussole, ni gyroscope**, écran LCD 90 Hz peu lumineux).

Ce fichier résume l'architecture et les décisions. Il est tenu à jour à chaque jalon.

## Commandes

```bash
./gradlew test                 # tous les tests (core + app)
./gradlew :core:test           # calculs seuls : rapide, sans Android
./gradlew lintDebug            # vérifications Android (bloquant en CI)
./gradlew :app:assembleDebug   # APK de test
./gradlew :app:assembleRelease # APK release (signé seulement si VELOGO_KEYSTORE_PASSWORD est défini)
./gradlew :app:recordRoborazziDebug  # captures d'écran des écrans (PNG dans app/build/outputs/roborazzi/)
./gradlew :app:assembleSmoke   # APK d'essai pour émulateur (code minifié de la release, clé de test, x86_64)
```

SDK requis : plateforme `android-37.1`, build-tools 37, JDK 21.

## Modules

| Module | Rôle |
|---|---|
| `core` | Kotlin pur, aucune dépendance Android : géodésie, moteur de sortie, altitude/pente/dénivelé, GPX, mise en forme. Tout calcul y vit pour être testé sur JVM. |
| `app` | Android : Compose, service d'enregistrement, carte MapLibre, base Room, réglages. |

Paquets de `app` (`fr.velogo.app`) : `ui/` (écrans Compose, thème), `recording/` (service de premier plan,
enregistreur), `data/` (Room, DataStore, export GPX), `map/` (MapLibre), `location/` (GPS), `elevation/`
(recalage d'altitude IGN), `update/` (mises à jour via les Releases GitHub).

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
- **Carte** : `VeloMap` (MapView dans `AndroidView`, mode SurfaceView) pilotée par `VeloMapState`, qui garde ce
  qui doit être dessiné et le redessine après chaque changement de fond. La trace en direct est découpée en
  morceaux figés + une « queue » de 300 points pour rester fluide après des heures.
- **Navigation** : l'écran « Rouler » (carte + enregistrement) reste composé en permanence sous les autres ;
  Historique et Réglages se posent par-dessus via Navigation 3 (`AppRoot`). La carte n'est jamais recréée en
  changeant d'onglet ; son rendu est coupé dès qu'elle est recouverte.
- **Textes** : interface uniquement en français, textes écrits directement dans les composables (seuls ceux
  du service et de la notification sont dans `strings.xml`).
- **Écrans testables** : chaque écran a un composable « contenu » sans accès aux données (`RideChrome`,
  `HistoryContent`, `RideDetailBody`, `SettingsContent`) rendu en image par `ScreenshotTest`.

## Vérification sans téléphone

Il n'y a pas d'émulateur dans l'environnement de développement. Trois niveaux de vérification :

1. `core` : tests unitaires des calculs (sorties simulées, références GeographicLib).
2. `app` : `ScreenshotTest` (Robolectric + Roborazzi) rend les écrans en PNG au format du Galaxy A05s.
3. CI : le job « Essai sur émulateur » installe l'APK `smoke` sur un émulateur Android 15, simule une sortie
   au GPS (`outils/essai_emulateur.py`), vérifie la distance affichée, la pause automatique, l'historique,
   les fonds de carte, la reprise après arrêt forcé, et publie des captures d'écran en artefact.

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
- **Recalage d'altitude** : à la fin d'une sortie, l'altitude est remplacée par celle du relief IGN
  (`ElevationCorrector`, API altimétrie de la Géoplateforme), en gommant viaducs et tunnels grâce à l'altitude GPS
  (`TerrainCorrection.fuse`). Sans réseau, la sortie garde son altitude GPS (source `GNSS`).
- **Portrait uniquement** pour l'instant (`screenOrientation="portrait"`) : la disposition paysage de l'écran
  guidon n'est pas au point ; à reprendre aux finitions si besoin.
- **R8** : la release est minifiée. Les clés de navigation (`*Key`) sont gardées explicitement.
- **Identité** : vert émeraude + bleu nuit, police Barlow Condensed (OFL) pour les chiffres, icônes Material
  Symbols (Apache 2.0). Thème « plein soleil » noir sur blanc pour l'écran guidon.

## Signature et livraison

- Clé : `signing/velogo-release.p12` (PKCS12, RSA 4096, chiffrée AES-256 par un mot de passe aléatoire de
  40 caractères). **Le mot de passe n'est que dans le secret GitHub `VELOGO_KEYSTORE_PASSWORD`** ; ne jamais
  l'écrire dans le dépôt.
- `.github/workflows/build.yml` : à chaque push, tests + lint, puis APK signé publié en Release
  (`v<majeur>.<mineur>.<numéro de build>`). `versionCode` = numéro de build GitHub, donc chaque APK s'installe
  par-dessus le précédent. Le mineur de `version.properties` suit les jalons.
- Les notes de version viennent de la première section de `docs/NOUVEAUTES.md`.

## Jalons

1. Carte en ligne + enregistrement + écran guidon + historique *(en cours)*
2. Cartes hors ligne (packs régionaux : fond Protomaps, surcouche vélo Planetiler, relief Mapterhorn)
3. Itinéraires BRouter embarqué + guidage
4. Météo Open-Meteo (modèles Météo-France AROME)
5. Stats, records, segments, finitions

## Sources de données et attributions

OpenStreetMap (ODbL) · OpenFreeMap / OpenMapTiles · CyclOSM (serveurs OSM France : en ligne uniquement,
User-Agent identifiant l'appli, pas de téléchargement en masse) · IGN Géoplateforme (Plan IGN, photos aériennes,
adresses, altitude ; Licence Ouverte 2.0) · Open-Meteo (CC BY 4.0) · Protomaps · Mapterhorn · BRouter (MIT).
Les cartes IGN TOP 25 (SCAN 25) sont interdites aux particuliers : non utilisées.
