# Nouveautés de VeloGo

## Jalon 3 — Itinéraires et guidage

- **Itinéraires vélo calculés sur le téléphone**, sans réseau, dans les régions téléchargées (leurs données
  d'itinéraire viennent maintenant avec la carte : pense à mettre à jour tes régions).
- **Trois propositions** : le plus rapide, le plus sûr (à l'écart de la circulation), le moins de dénivelé, avec
  pour chacune la distance, la durée, le dénivelé, le profil d'altitude et la nature des voies (pistes cyclables,
  petites routes, routes, grands axes, chemins, part non revêtue).
- **Trois types de vélo** : route, VTC · gravel, VTT.
- **Départ, étapes, arrivée** par recherche d'adresse ou de lieu (IGN, en ligne) ou par appui long sur la carte.
- **Guidage** : prochaine manœuvre en gros sur la carte comme sur l'écran guidon, distance et temps restants,
  alerte et recalcul automatique si tu t'écartes de l'itinéraire.
- **Voix en option** (bouton haut-parleur) : pendant une consigne, ta musique baisse un instant puis remonte ;
  elle n'est jamais coupée. Vibration à chaque consigne, désactivable.
- **Préparer un itinéraire pendant une sortie** : l'enregistrement continue sans interruption.
- **Itinéraires enregistrés**, **import d'une trace GPX** à suivre, **export GPX** d'un itinéraire.

### À tester sur le téléphone

1. Réglages > Cartes hors ligne : mets à jour ta région (les données d'itinéraire s'ajoutent).
2. Sur la carte, touche le bouton « Itinéraire », choisis une arrivée (recherche ou appui long), puis « Calculer ».
3. Compare les propositions, puis « Démarrer le guidage » et accepte d'enregistrer la sortie.
4. Roule : vérifie les consignes aux carrefours, puis quitte volontairement l'itinéraire pour voir le recalcul.
5. Avec de la musique et la voix activée : la musique doit baisser pendant la phrase, sans se couper.

### Limites connues

- Les consignes n'indiquent pas le nom des rues (les données d'itinéraire n'en contiennent pas).
- Une trace GPX importée est suivie à la ligne, avec alerte d'écart mais sans consignes de virage.
- Sans enregistrement en cours, le guidage s'arrête quand l'écran s'éteint ou que tu changes d'appli.
- La recherche d'adresse demande du réseau ; hors ligne, place tes points par appui long sur la carte.

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
