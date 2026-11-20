# Nouveautés de VeloGo

## Jalon 2 — Cartes hors ligne

- **Cartes hors ligne par région** (Réglages > Cartes hors ligne) : chaque région se télécharge en Wi-Fi, reprend
  toute seule après une coupure et fonctionne ensuite en mode avion.
- **Nouveau fond « Vélo hors ligne »**, dessiné pour le vélo : pistes et voies vertes en vert, bandes cyclables en
  pointillé, véloroutes surlignées (nationales, régionales, locales, VTT), chemins selon leur revêtement, voies
  interdites aux vélos, cols avec leur altitude, points d'eau, ateliers et magasins de vélos. Clair ou sombre,
  avec le relief en ombrage.
- **Altitude, pente et dénivelé précis en direct**, même sans réseau : dans une région téléchargée, ils viennent du
  relief de l'IGN (au mètre près) et non plus du seul GPS.
- **Recalage de fin de sortie sans réseau** : sans connexion, l'altitude de la sortie est corrigée avec le relief
  hors ligne ; avec connexion, toujours avec le service de l'IGN.
- Les cartes sont régénérées chaque mois à partir d'OpenStreetMap ; l'appli propose alors la mise à jour.
- Repère de position plus gros sur la carte ; écran guidon plus sobre tant que le GPS n'a pas de position.

### À tester sur le téléphone

1. Réglages > Cartes hors ligne : télécharge ta région (en Wi-Fi), puis touche « Utiliser la carte hors ligne ».
2. Passe en mode avion, ferme puis rouvre VeloGo : la carte doit s'afficher et se déplacer normalement.
3. Démarre une sortie en mode avion : l'altitude s'affiche tout de suite et la pente réagit dans les côtes.
4. Termine la sortie : le détail indique « Altitude issue du relief hors ligne ».

### Limites connues

- Hors des régions téléchargées, la carte hors ligne ne montre que les grands axes (vue d'ensemble de la France).
- Le calcul d'itinéraire hors ligne arrive au jalon 3 (les données nécessaires sont déjà publiées).
- Affichage en portrait uniquement pour l'instant.

## Jalon 1 — Carte, enregistrement, historique

- **Carte en ligne**, limitée à la France : Plan (clair ou sombre, pistes cyclables surlignées), CyclOSM, Plan IGN,
  photos aériennes. Position en direct, suivi, nord en haut ou sens de la marche.
- **Enregistrement** : démarrer, pause, reprise, « Terminer » à maintenir appuyé, pause automatique à l'arrêt.
  Il continue écran éteint ou appli en arrière-plan, et reprend là où il en était si l'appli est interrompue.
- **Écran guidon** en très gros chiffres : vitesse, moyenne, max, distance, durée, altitude, pente, dénivelé +.
  Thème « plein soleil », écran toujours allumé et luminosité maximale en option.
- **Musique** : VeloGo ne prend jamais le son ; rien n'est coupé ni baissé.
- **Historique** : liste par mois, détail avec carte, chiffres, graphiques d'altitude et de vitesse, export GPX.
- **Altitude** recalée sur le relief de l'IGN à la fin de la sortie (le téléphone n'a pas de baromètre).
- **Mises à jour** signalées dans l'appli.

### À tester sur le téléphone

1. Ouvre VeloGo, autorise la position : la carte se centre sur toi.
2. Lance de la musique, puis « Démarrer » : la musique ne doit ni se couper ni baisser.
3. Roule (ou marche) quelques minutes, éteins l'écran, rallume : distance et durée ont continué.
4. Arrête-toi une dizaine de secondes : « À L'ARRÊT » s'affiche et le chrono se fige, puis repart.
5. Maintiens « Terminer » : la sortie apparaît avec sa carte et ses graphiques.
6. Dans Réglages > Fiabilité de l'enregistrement, vérifie que tout est vert.

### Limites connues

- Affichage en portrait uniquement pour l'instant.
- Sans réseau, la carte ne montre que les zones déjà consultées (les cartes hors ligne arrivent au jalon 2).
- En direct, altitude, pente et dénivelé viennent du GPS seul : ils sont lissés mais approximatifs
  (relief hors ligne au jalon 2).

## Mise en place
- Ossature de l'appli, clé de signature et build automatique.
