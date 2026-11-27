# Nouveautés de VeloGo

## Version 0.7 — Un nouveau visage, et une version pour le Play Store

### Nouveau design

- **Les couleurs du logo partout** : le vert foncé pour le texte fort et les boutons, le vert vif pour ce qui est
  choisi ou en marche (« Démarrer », l'onglet actif, les interrupteurs), le rose pour ta trace et pour « Terminer ».
  Le bleu ne reste que là où il sert à lire : l'itinéraire à suivre sur la carte, et la pluie.
- **Du verre sur la carte** : la barre d'onglets, les boutons, les pastilles (GPS, météo) et le rappel des données
  sont des pilules translucides, avec un reflet sur le bord et une ombre douce. On devine la carte au travers.
- **Une barre d'onglets flottante**, détachée du bas de l'écran ; une pastille verte glisse d'un onglet à l'autre.
- **Les boutons de la carte réunis** dans une seule pilule (fond de carte, orientation, recentrage), sous le bouton
  d'itinéraire, en vert.
- **Écran guidon en tuiles** : chaque donnée a sa tuile aux coins arrondis. En « plein soleil », elles sont blanches
  et cernées de noir.
- **Des arrondis à la manière d'iOS** sur les cartes des listes : la courbe se raccorde aux côtés sans à-coup.
- **De grands titres** en haut des onglets (« Mes sorties », « Mes trajets », « Réglages »), qui se rangent en petit
  dès que la liste défile ; des interrupteurs verts ; un sélecteur dont la pastille glisse (Carte / Données, type
  de vélo, thème…).
- Le panneau d'itinéraire ne touche plus les bords de l'écran : c'est un panneau flottant, comme le reste.

### Toujours aussi fluide

Un vrai « Liquid Glass » floute en direct ce qu'il y a derrière lui. Sur la carte, ce n'est pas possible sans
saccades : elle est dessinée à part, l'interface ne peut ni la lire ni la flouter, et le téléphone n'a pas la
puissance de flouter 90 images par seconde. Le verre de VeloGo est donc **dessiné** (fond translucide, reflet,
ombre) : quelques rectangles arrondis que le téléphone trace sans effort.

- Faire glisser la carte ne redessine rien de l'interface, exactement comme avant.
- Les animations (pastille des onglets, sélecteurs, interrupteurs, rebond des boutons) ne redessinent que
  l'élément qui bouge.
- Quand un autre onglet recouvre la carte, son habillage n'est plus dessiné du tout : c'est un peu de travail en
  moins qu'avant à chaque image d'une liste qu'on fait défiler.

### Prête pour le Play Store

- Chaque version publiée contient maintenant, à côté de l'APK, un fichier **`VeloGo-x.y.z-play.aab`** : la même
  appli au format qu'exige Google Play, signée de la même clé. Elle ne cherche pas ses mises à jour sur GitHub
  (c'est le magasin qui s'en charge) et règle l'économie de batterie par la fiche de l'appli.
- **Avant de demander ta position**, l'appli dit ce qu'elle en fait et ce qui sort du téléphone.
- **Réglages > Confidentialité** : la politique de confidentialité, lisible sans réseau.
- L'appli s'adapte aux **tablettes et aux téléphones pliables** : écran guidon en paysage, listes et boutons qui
  gardent une largeur lisible.
- **Le guide pas à pas** est dans `docs/PLAY_STORE.md`, avec les réponses toutes prêtes aux formulaires de Google ;
  les textes et les images de la fiche sont dans `docs/play/`.

### Une appli pour tout le monde

VeloGo n'est plus présentée comme une appli personnelle : elle est faite pour être publiée.

- Les conditions de chaque service utilisé (cartes, adresses, altitude, météo, hébergement) ont été relues pour une
  appli ouverte à tous : toutes le permettent, à trois points près à surveiller si l'appli a du succès
  (`docs/PLAY_STORE.md`, « Une appli publique »).
- **Les mentions des sources sont complètes** : « © OpenMapTiles » manquait sur le fond « Plan ». Un appui sur la
  mention de la carte ouvre la page des droits de ceux qui la fournissent, et la mention de la météo mène au site
  d'Open-Meteo, comme leurs licences le demandent.
- **« Sur la route »** (Réglages > Sources et licences) : les cartes et les itinéraires peuvent être incomplets ou
  dépassés ; sur place, c'est la signalisation qui fait foi.

### Et aussi

- **Recherche d'adresse plus patiente** : une demande qui échoue (réseau qui hésite, service lent) est retentée
  toute seule ; si elle échoue encore, un bouton « Réessayer » évite de retaper l'adresse.
- Sur l'écran de recherche, l'heure et les icônes du haut de l'écran sont de nouveau lisibles (elles restaient
  blanches sur fond clair).
- **Cartes hors ligne sans Wi-Fi** : avec « Wi-Fi uniquement », une carte demandée en données mobiles restait
  indéfiniment sur « Le téléchargement va démarrer… ». L'appli dit maintenant ce qu'elle attend (le Wi-Fi, ou le
  réseau), et si tu désactives « Wi-Fi uniquement », ce qui attendait part tout de suite avec ton forfait.

### Ce qu'il reste à faire de ton côté pour publier

Rien n'est obligatoire tant que tu ne veux pas publier, et rien n'a été créé à ta place.

1. Un compte développeur Google Play : **25 $ une seule fois**, avec vérification de ton identité. Google affiche
   publiquement ton nom légal, ton pays et une adresse e-mail.
2. Un **test fermé avec 12 testeurs pendant 14 jours**, exigé des comptes personnels avant l'ouverture à tous.
3. Une adresse publique pour la politique de confidentialité : le plus simple est de fusionner la branche de
   travail dans `main` (dis-le-moi).
4. Une courte vidéo montrant l'enregistrement d'une sortie, demandée par Google.

### Limites connues

- Le verre ne floute pas la carte : les panneaux qui portent beaucoup de texte (itinéraire) sont presque opaques
  pour rester lisibles.
- Le nouveau design a été vérifié en images sur ordinateur et sur l'émulateur Android 15, pas sur ton téléphone :
  la lisibilité au soleil et la fluidité réelle sur le Galaxy A05s restent à confirmer par toi.
- Rien de ce qui se passe dans la Play Console n'a pu être essayé (il faut un compte), en particulier l'envoi de la
  clé de signature (étape 3 du guide). Ce qui a pu l'être : à chaque fabrication, l'outil de Google qui sert au
  Play Store tire un APK d'un fichier AAB d'essai, et cet APK tourne sur l'émulateur.
- Les tailles de tablette n'ont été regardées qu'en images, pas sur une vraie tablette.

## Version 0.6, mise à jour — Des boucles, et des trajets qui disent ce qu'ils contiennent

- **Pourquoi un trajet annoncé 40 km n'en faisait que 31.** Quand l'arrivée d'un itinéraire reste posée quelque
  part (Bouffémont, par exemple) et que le retour à la maison est ajouté comme simple étape, l'itinéraire fait la
  boucle, repasse par la maison, puis **repart vers l'arrivée** par les routes du début. Sur la carte, ce dernier
  morceau est caché sous le premier passage : on ne voit qu'une boucle, alors que la distance annoncée compte les
  deux. La sortie, elle, s'arrête à la maison : il manque les kilomètres de ce dernier morceau.
- **Un bouton « Boucle »** dans la préparation d'un itinéraire (et « Revenir au départ » quand tu choisis
  l'arrivée) : l'itinéraire part de ton point de départ et y revient. Tu n'as plus qu'à dire par où passer : chaque
  étape ajoutée (recherche, ou appui long sur la carte) est parcourue dans l'ordre où tu les places. « Autre sens »
  fait la même boucle dans l'autre sens.
- **Les étapes sont numérotées**, dans la liste comme sur la carte (1, 2, 3…) : on voit dans quel ordre
  l'itinéraire les enchaîne. Une flèche à côté de chaque étape la fait passer avant la précédente.
- **L'appli prévient quand un itinéraire repasse par son départ puis continue** : « Repasse par le départ après
  29,3 km, puis continue 10,9 km. » Le bouton « Arrêter au départ » règle la chose d'un appui : un itinéraire en
  préparation devient une boucle ; un trajet enregistré est raccourci (après confirmation) et remplace l'ancien
  sous le même nom.
- Fermer un trajet enregistré le range : le bouton « Itinéraire » rouvre ensuite un panneau vierge, prêt pour un
  nouvel itinéraire.

### Pour ton trajet « Entraînement 1 »

1. Onglet « Trajets », ouvre « Entraînement 1 » : le panneau doit annoncer qu'il repasse par le départ, et de
   combien il continue ensuite.
2. Touche « Arrêter au départ », puis « Raccourcir » : le trajet fera une trentaine de kilomètres et s'arrêtera
   chez toi. La distance annoncée sera celle que tu rouleras.
3. Pour le prochain : « Itinéraire », « Boucle », puis un appui long sur la carte pour chaque endroit par où
   passer, dans l'ordre (Chauvry, Bouffémont, Andilly…), et « Calculer l'itinéraire ».

### Limites connues

- L'avertissement ne concerne que les itinéraires qui repassent à moins de 200 m de leur point de départ sans y
  finir. Un itinéraire peut encore emprunter deux fois la même route ailleurs (un aller-retour vers une étape, par
  exemple) : la carte ne montre toujours qu'un seul trait.
- Un trajet enregistré se raccourcit sans être recalculé : sa durée est répartie au prorata de la distance, et la
  répartition par nature de voie n'est plus affichée pour lui.
- Une sortie compte ce qui a été roulé : si tu t'écartes de l'itinéraire ou que tu l'arrêtes avant la fin, sa
  distance diffère de celle annoncée.

## Version 0.6, mise à jour — Ta position même quand le GPS cherche ses satellites

- **Ta position tout de suite, même en intérieur.** Jusqu'ici VeloGo n'écoutait que le GPS « satellites » : tant
  qu'il n'avait rien trouvé (en intérieur, ou pendant de longues minutes au démarrage sur certains téléphones), la
  carte restait sur la France entière et la pastille sur « GPS… ». Désormais, en attendant, la carte montre la
  **position approchée** que le téléphone déduit du Wi-Fi et du réseau mobile : un point entouré d'un cercle qui
  dit son incertitude. Dès que le GPS répond, il reprend la main et la position devient précise.
- **L'enregistrement reste au GPS.** Une sortie ne s'enregistre, et un guidage ne te suit, qu'avec la position
  précise des satellites : la position approchée ne fausse donc jamais une distance. Elle sert à te situer, à la
  météo, et à calculer un itinéraire « depuis ma position » (pratique pour préparer sa sortie depuis le canapé).
- **Une pastille qui dit ce qui se passe**, en haut à droite : « GPS ±4 m » en vert quand tout va bien, en orange
  quand le signal est moyen, en rouge quand il est trop flou pour enregistrer ; « Recherche GPS » tant qu'il
  cherche (orange si la carte montre déjà une position approchée, rouge si elle n'a rien) ; « GPS coupé » si le
  téléphone l'a désactivé.
- **Un appui sur la pastille ouvre « Signal GPS »** : ce qui se passe, dit simplement, le nombre de satellites
  captés et utilisés, depuis quand le GPS cherche, et ce qu'on peut y faire (activer la localisation, autoriser la
  position exacte, « Relancer le GPS »). Si la recherche dure plus d'une minute et demie, « Réinitialiser le GPS »
  lui fait oublier ce qu'il croit savoir des satellites pour repartir de zéro, ce qui débloque parfois un téléphone.
- **Coup de pouce automatique** : quand le GPS cherche depuis vingt secondes, VeloGo demande au téléphone de lui
  redonner l'heure exacte et la position prévue des satellites (il faut du réseau). C'est ce qui manque le plus
  souvent à un GPS qui met des minutes à démarrer.
- **GPS coupé alors que la localisation est « active »** : certains téléphones, surtout d'avant Android 10, ont
  des modes de localisation économes (« Économie de batterie », « Réseau uniquement ») qui se passent du GPS. Rien
  ne s'enregistrait alors, sans explication. VeloGo le dit maintenant, sur la pastille et au moment de démarrer
  une sortie, et propose d'ouvrir le bon réglage.
- **Position « approximative » seulement** (un choix qu'Android 12 et les suivants proposent au moment
  d'autoriser) : la carte montre quand même à peu près où tu es, et un bouton permet d'accorder la position exacte.
- Après deux refus, Android ne redemande plus l'autorisation de position : le bouton « Autoriser la position »
  explique maintenant comment l'accorder dans les réglages, au lieu de sembler ne rien faire.
- Plus sûr sur les téléphones dont la puce GPS date mal ses positions : elles risquaient d'être écartées de
  l'enregistrement comme « périmées ».

### À tester sur le téléphone où le GPS ne répondait pas

1. Installe cette version et ouvre l'appli en intérieur, Wi-Fi allumé : en quelques secondes, la carte doit se
   centrer sur toi (un point dans un cercle) et la pastille afficher « Recherche GPS » en orange.
2. Touche la pastille : la feuille « Signal GPS » dit combien de satellites sont captés.
3. Sors, ciel dégagé : en une minute environ, la pastille doit passer à « GPS ±… m » en vert, et « Démarrer »
   enregistre alors normalement.
4. Si rien ne vient après deux minutes dehors : « Relancer le GPS », puis si besoin « Réinitialiser le GPS ». Note
   ce qu'affiche la feuille (titre, satellites captés et utilisés) : c'est ce qui permettra de comprendre ce
   téléphone.

### Limites connues

- La position approchée dépend du téléphone : il faut que sa localisation par Wi-Fi et réseau soit en service
  (sur la plupart des téléphones, le réglage « Précision de la localisation Google »). Sans Wi-Fi ni réseau
  mobile autour de toi, il n'y en a pas.
- En pleine nature, loin de tout Wi-Fi, elle peut être floue d'un kilomètre ou plus : la carte se cadre alors plus
  large, et se resserre dès que le GPS répond.
- En intérieur, le GPS peut ne jamais trouver ses satellites, quel que soit le téléphone : c'est normal, et
  aucune appli n'y peut rien.
- Cette mise à jour a été vérifiée sur Android 8, 11 et 15 simulés et sur un émulateur Android 15, pas sur le
  téléphone où le problème a été vu.

## Version 0.6 — Carte en grand, affiche à partager, guidage avec flèche, onglet « Trajets »

- **Itinéraire choisi : la carte en grand.** Dès que les itinéraires sont calculés, le panneau du bas se replie
  de lui-même : il ne garde que les propositions et le bouton « Démarrer le guidage », et la carte se cadre sur le
  trajet, sur presque tout l'écran. « Détails » (ou la poignée du panneau, à tirer vers le haut) déplie le profil,
  la nature des voies et la météo ; « Voir la carte » le replie. Si tu déplaces ou zoomes la carte pendant qu'il
  est déplié, il se replie tout seul.
- **Choisir un itinéraire en le touchant** : un appui sur un tracé gris de la carte le sélectionne. Le bouton
  « Voir tout l'itinéraire » (les quatre coins, à droite) recadre la carte sur le trajet entier.
- **Une affiche à partager** : le bouton de partage du panneau montre l'aperçu d'une affiche de la sortie : la
  carte avec le tracé et son sens, la distance, le dénivelé, la durée estimée et le profil. Tu peux changer son
  titre et ajouter une ligne (« Dimanche 9 h, devant la mairie »), puis « Partager l'affiche » l'envoie par
  WhatsApp, SMS, e-mail… avec une légende qui reprend les chiffres. Le fichier GPX reste disponible juste en
  dessous, pour ceux qui veulent le charger dans leur GPS.
- **Guidage comme sur un GPS de voiture** : ta position devient une **flèche** qui montre ton sens de marche.
  Pendant le guidage, la carte tourne avec toi (ce qui est devant toi est en haut), s'incline et se rapproche, et
  la flèche se place vers le bas de l'écran pour laisser voir la route à venir. Si tu déplaces la carte, un gros
  bouton « Recentrer » y ramène ; « Voir tout l'itinéraire » montre le trajet entier.
- Le téléphone n'a pas de boussole : la flèche suit ton déplacement. À l'arrêt elle garde ta dernière direction,
  et au départ d'un guidage elle montre dans quel sens part l'itinéraire.
- **Un onglet « Trajets »** dans la barre du bas : tous tes itinéraires enregistrés, avec le dessin du tracé, la
  distance, la durée et le dénivelé. Un appui rouvre le trajet sur la carte, prêt à être suivi (même sans
  réseau) ; le menu « ⋮ » de chaque ligne permet de le partager en affiche, de le renommer ou de le supprimer.
  Pour enregistrer un itinéraire : « Détails », puis « Enregistrer cet itinéraire ».
- **Nouveau logo** : l'icône de l'appli, l'écran de démarrage, l'affiche à partager, les notifications et l'écran
  « Sources et licences » portent le nouveau logo VeloGo (le vélo dessiné par les lettres « oGo »).
- Correction : après le calcul d'un itinéraire, ta position restait affichée dans le haut de l'écran pendant le
  guidage. Elle est maintenant toujours là où on l'attend.
- Correction : un trajet enregistré puis rouvert annonce exactement la distance affichée dans la liste (elle
  pouvait différer de 100 m à l'arrondi).
- Correction : hors itinéraire, la phrase du bandeau de guidage n'est plus coupée (« Je recalcule si tu
  continues »).
- Correction : sur l'écran météo, une longue ligne de vent ne coupe plus « km/h » en deux.

### À tester sur le téléphone

1. Calcule un itinéraire : la carte doit montrer le trajet en grand. Zoome dessus, touche un autre tracé, ouvre
   « Détails », reviens avec « Voir la carte ».
2. Touche le bouton de partage du panneau, change le titre, puis « Partager l'affiche » vers WhatsApp : tes amis
   doivent recevoir l'image et sa légende.
3. Démarre le guidage et roule : la flèche pointe devant toi, la carte tourne dans les virages. Déplace la carte
   au doigt, puis touche « Recentrer ».
4. Enregistre un itinéraire (« Détails », puis « Enregistrer cet itinéraire »), ouvre l'onglet « Trajets » et
   touche-le : il doit revenir sur la carte, prêt à démarrer.

### Limites connues

- Sans boussole, le téléphone ne sait pas vers où il est tourné tant que tu n'as pas roulé quelques mètres.
- L'affiche a besoin du réseau pour son fond de carte (sauf si le fond choisi est la carte « Vélo hors ligne ») :
  sans lui, elle montre le tracé seul, sur un fond uni.

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
