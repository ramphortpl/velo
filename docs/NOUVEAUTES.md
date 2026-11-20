# Nouveautés de VeloGo

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
