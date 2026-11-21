# Nouveautés de VeloGo

## Jalon 5 — Statistiques, records et segments

- **Statistiques** (Historique > Statistiques) : distance, nombre de sorties, durée, dénivelé, vitesse moyenne et
  plus longue sortie **par semaine, par mois ou par année**, avec un graphique en barres. Touche une barre pour
  voir une autre période ; la comparaison avec la période précédente est donnée en clair. En dessous, les totaux
  depuis ta première sortie.
- **Records** : plus longue sortie, plus gros dénivelé, plus longtemps en selle, meilleure moyenne (sorties de
  10 km et plus), vitesse la plus haute, point le plus haut. Un appui ouvre la sortie concernée.
- **Meilleurs temps** sur 5, 10, 20, 30, 50, 80 et 100 km, cherchés à l'intérieur de chaque sortie (le meilleur
  10 km peut être au milieu d'une sortie de 60 km).
- **Segments personnels** : dans une sortie, « Créer un segment » permet de choisir un bout de route sur le profil
  d'altitude (une côte, une ligne droite, ton tour habituel). VeloGo retrouve ensuite ce segment dans toutes tes
  sorties, passées et à venir, chronomètre chaque passage et les classe : record, 2e temps, 3e temps…
  Le segment commence et finit exactement là où tu places ses deux bouts, et sa longueur, son dénivelé et sa
  pente sont ceux annoncés avant de le créer.
- **Dans le détail d'une sortie** : ses passages sur tes segments avec leur rang, et ses records éventuels.
- **En tête de l'historique** : le résumé de la semaine en cours.
- **Mes données** (Réglages) : **sauvegarde de toutes les sorties** dans une archive ZIP de fichiers GPX, et
  **import de sorties** depuis une telle sauvegarde ou depuis un fichier GPX d'une autre appli (sans doublons).
- Tout est calculé sur le téléphone, une fois par sortie, en tâche de fond. Aucun compte, aucun réseau social.
- Météo : la feuille s'ouvre maintenant en grand, et la pastille ne recouvre plus les boutons de la carte quand
  le panneau d'itinéraire est ouvert.

### À tester sur le téléphone

1. Historique : le résumé de la semaine en haut, puis « Statistiques » ; passe de Semaine à Mois et Année.
2. Ouvre une sortie, puis « Créer un segment » sous ses graphiques (ou dans le menu ⋮) : règle le début et la
   fin, puis « Créer le segment ».
   La sortie d'origine doit apparaître comme premier passage ; refais le même trajet pour voir le classement.
3. Réglages > Mes données > « Sauvegarder mes sorties » : range l'archive dans tes fichiers, ouvre-la pour voir
   un GPX par sortie.

### Limites connues

- Un segment est reconnu quand on le parcourt en entier, dans le même sens, à 30 m près de son tracé.
- Les temps sont des temps réels, arrêts compris (un feu rouge compte).
- La sauvegarde contient les sorties (traces, noms), pas les segments ni les itinéraires enregistrés.
- L'écran reste en mode portrait.

## Jalon 4 — Météo

- **La météo sur la carte** : le temps qu'il fait, la température et le vent dans une pastille en haut à droite.
  Un appui ouvre le détail : température ressentie, vent et rafales, **pluie dans les deux heures quart d'heure
  par quart d'heure**, puis les prévisions **heure par heure** sur un jour et demi.
- **Sur l'écran guidon** : une ligne sous les chiffres donne la température et le vent **de face, de dos ou de
  côté** selon ton sens de marche.
- **Alerte pluie** : pendant une sortie, quand la pluie est à moins de 30 minutes, le téléphone vibre (deux coups
  brefs, un long) et affiche une notification silencieuse ; l'annonce est dite à voix haute si le guidage vocal
  est activé. Ta musique n'est jamais coupée.
- **Météo le long de l'itinéraire** : dans le panneau d'itinéraire, une ligne résume le trajet (« 11 à 14° · vent
  de face 15 km/h · pluie vers 15:30 (km 24) »). Le détail donne le temps prévu endroit par endroit **à l'heure où
  tu y passeras** (heures estimées d'après ton allure et le relief), et tu peux choisir une autre heure de départ
  (« 15 h », « Demain 8 h »…) pour voir si la pluie s'évite. En guidage, la feuille météo montre la suite du trajet.
- **Sans réseau**, les dernières prévisions reçues restent affichées (avec leur heure) tant qu'elles couvrent
  l'heure qu'il est ; elles sont gardées sur le téléphone d'un lancement à l'autre.
- **Prévisions de Météo-France** (modèle AROME à 1,5 km, complété par ARPEGE) fournies par Open-Meteo : gratuit,
  sans compte ni clé. Seule une position arrondie au kilomètre est envoyée.
- Réglages > Météo : affichage de la météo et alerte pluie désactivables.
- Itinéraires : « Démarrer le guidage » reste toujours visible en bas du panneau ; enregistrer l'itinéraire et
  l'exporter en GPX sont maintenant deux boutons en haut du panneau. La première consigne vocale est dite même
  si la voix n'avait pas fini de démarrer.

### À tester sur le téléphone

1. Ouvre l'appli dehors ou près d'une fenêtre : la pastille météo apparaît en haut à droite de la carte.
2. Touche-la : vérifie que la température et le vent correspondent à ce que tu ressens.
3. Prépare un itinéraire : lis la ligne « Météo sur le trajet », ouvre-la et essaie un départ décalé.
4. Pendant une sortie, passe sur « Données » : la ligne météo doit dire si le vent est de face ou de dos.
5. Un jour de pluie annoncée : vérifie que l'alerte arrive bien avant l'averse (vibration + notification).

### Limites connues

- La météo demande du réseau au moins de temps en temps (une requête de quelques kilo-octets toutes les dix
  minutes au plus). En zone blanche, tu gardes les dernières prévisions reçues.
- La pluie au quart d'heure près vient du modèle AROME, pas d'un radar : une averse isolée peut tomber à côté.
- La vibration d'alerte suit le mode du téléphone : rien en mode « Silencieux ».

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
