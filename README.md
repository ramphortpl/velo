# VeloGo

Appli Android de vélo pour un usage personnel, en France : enregistrement GPS des sorties, itinéraires vélo,
cartes hors ligne, météo et statistiques. Gratuite, sans compte, sans publicité ; les données restent sur le
téléphone.

## Ce que fait l'appli

- **Rouler** : trace GPS en direct sur la carte, écran « guidon » en très gros chiffres (vitesse, distance, durée,
  moyenne, max, pente, dénivelé, altitude), pause automatique, enregistrement écran éteint. La musique n'est
  jamais coupée.
- **Cartes** : plusieurs fonds en ligne (plan, CyclOSM, Plan IGN, photos aériennes) et une carte vélo **hors ligne**
  par région (pistes, voies vertes, véloroutes, relief), en clair ou en sombre.
- **Itinéraires** : calculés sur le téléphone, même sans réseau : le plus rapide, le plus sûr, le moins de dénivelé,
  pour vélo de route, VTC/gravel ou VTT. Guidage avec alerte et recalcul hors itinéraire, voix en option.
  Import et export GPX.
- **Météo** : prévisions Météo-France (via Open-Meteo), pluie au quart d'heure, vent de face ou de dos, météo le
  long de l'itinéraire à l'heure de passage, alerte pluie pendant la sortie.
- **Historique** : chaque sortie avec sa carte, ses chiffres et ses graphiques ; totaux par semaine, mois et
  année ; records ; meilleurs temps ; segments personnels chronométrés ; sauvegarde et import en GPX.

Le détail de chaque version est dans [docs/NOUVEAUTES.md](docs/NOUVEAUTES.md).

## Installer ou mettre à jour

1. Sur le téléphone, ouvre la page **Releases** du dépôt.
2. Télécharge le fichier `VeloGo-x.y.z.apk` de la version la plus récente.
3. Ouvre-le et accepte l'installation (la première fois, Android demande d'autoriser le navigateur à installer
   des applis).

Chaque version s'installe par-dessus la précédente sans rien effacer : elles sont toutes signées avec la même clé.
L'appli signale d'elle-même quand une version plus récente est disponible (Réglages).

## Mise en route du dépôt (une seule fois)

La fabrication de l'APK est automatique à chaque modification, mais sa **signature** a besoin d'un secret :

1. Sur GitHub : **Settings > Secrets and variables > Actions > New repository secret**.
2. Nom : `VELOGO_KEYSTORE_PASSWORD`. Valeur : le mot de passe de la clé de signature (il n'est écrit nulle part
   dans le dépôt).
3. Relance le workflow **APK VeloGo** (onglet Actions) : l'APK signé apparaît dans les Releases.

Tant que ce secret manque, les tests tournent mais aucun APK n'est publié.

Les cartes hors ligne sont fabriquées par le workflow **Cartes hors ligne** et publiées dans la Release `cartes`.
Leur mise à jour mensuelle automatique ne fonctionne qu'une fois la branche de travail fusionnée dans `main`.

## Premiers pas dans l'appli

1. Autorise la position, puis touche **Démarrer** : l'écran « guidon » s'affiche, la carte reste à un geste.
2. **Réglages > Cartes hors ligne** : télécharge ta région en Wi-Fi. Elle apporte la carte vélo, le relief
   (altitude et pente précises) et les données d'itinéraire.
3. Sur la carte, le bouton **Itinéraire** : cherche une adresse ou fais un appui long sur la carte.
4. Sur un Samsung, suis **Réglages > Fiabilité de l'enregistrement** pour que l'appli ne soit pas mise en veille
   écran éteint.

## Développement

Voir [CLAUDE.md](CLAUDE.md) pour l'architecture, les décisions et les commandes.

## Licences et données

Code de l'appli : usage personnel. Données cartographiques © les contributeurs d'OpenStreetMap (ODbL) ; fonds,
adresses et altitudes de l'IGN (Géoplateforme, Licence Ouverte 2.0) ; météo Open-Meteo (CC BY 4.0), modèles
Météo-France ; fond hors ligne Protomaps ; relief Mapterhorn ; itinéraires BRouter (MIT). Le détail des
attributions est dans l'appli (Réglages > Sources et licences).
