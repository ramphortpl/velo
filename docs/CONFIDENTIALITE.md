# Politique de confidentialité de VeloGo

Dernière mise à jour : 27 novembre 2026.

VeloGo est une application de vélo gratuite, sans compte et sans publicité. Son auteur ne reçoit, ne stocke et ne revend aucune donnée : il n'existe aucun serveur VeloGo, et rien ne lui rapporte ce que tu fais dans l'appli. Tes sorties restent sur ton téléphone.

Pour fonctionner, l'appli échange toutefois avec quelques services de cartes, d'adresses, d'altitude et de météo. Cette page dit exactement quoi, à qui et pourquoi. Les cartes de Mapbox ont leur propre partie, plus bas : elles ne sont affichées que si tu les choisis, après ton accord.

## Ce qui reste sur ton téléphone

- Tes sorties : trace GPS, vitesses, altitudes, heures.
- Tes itinéraires enregistrés, tes segments et tes réglages.
- Les cartes hors ligne que tu télécharges.

Rien de tout cela n'est envoyé à qui que ce soit, et l'appli ne fait aucune sauvegarde dans le cloud. Tu peux en faire une copie toi-même (Réglages, puis Sauvegarde) : c'est toi qui choisis où la ranger. Quand tu changes de téléphone, Android peut copier les données de l'appli directement d'un appareil à l'autre, si tu le lui demandes.

## Ta position

VeloGo utilise la position du téléphone (le GPS et, en attendant qu'il trouve ses satellites, la position approchée déduite du Wi-Fi et du réseau) pour te situer sur la carte, enregistrer tes sorties, te guider et afficher la météo du lieu.

- Elle n'est lue que lorsque l'appli est affichée, ou pendant une sortie que tu as démarrée : l'enregistrement continue alors écran éteint, avec une notification visible en permanence.
- L'appli ne demande pas l'accès à la position « en arrière-plan ».
- Tu peux refuser ou retirer l'autorisation à tout moment dans les réglages du téléphone. L'appli ne pourra alors plus te situer ni enregistrer.

## Ce qui sort du téléphone, et vers qui

Chaque demande ci-dessous part directement de ton téléphone vers le service indiqué. Comme pour toute connexion à Internet, ce service voit l'adresse IP de ton téléphone. Aucune demande ne contient ton nom, un identifiant de compte ou un identifiant publicitaire.

- Fonds de carte en ligne (OpenFreeMap pour le plan, Mapterhorn pour son relief, Géoplateforme de l'IGN, serveurs CyclOSM d'OpenStreetMap France) : l'appli demande les morceaux de carte de la zone affichée à l'écran. Avec la carte hors ligne, rien n'est demandé.
- Recherche d'adresse (Géoplateforme de l'IGN) : le texte que tu tapes et le centre de la carte affichée, pour proposer d'abord les lieux proches. Quand tu poses un point d'itinéraire sur la carte, la position de ce point est envoyée pour retrouver son adresse.
- Météo (Open-Meteo, modèles de Météo-France) : ta position arrondie à un kilomètre environ et, quand tu prépares ou suis un itinéraire, quelques points de cet itinéraire arrondis de la même façon. Tu peux la couper : Réglages, puis Météo.
- Altitude de tes sorties (Géoplateforme de l'IGN) : à la fin d'une sortie, les positions de sa trace sont envoyées au service d'altitude de l'IGN pour corriger le dénivelé. Tu peux le couper : Réglages, puis Altitude et dénivelé. L'appli se sert alors du relief des cartes hors ligne.
- Cartes hors ligne (GitHub) : la liste des régions, puis les fichiers des régions que tu choisis de télécharger.
- Mises à jour (GitHub) : seulement dans la version distribuée hors de Google Play, l'appli consulte la liste des versions publiées pour te signaler une mise à jour.

Ces services ont leurs propres règles de confidentialité, consultables sur leurs sites : openfreemap.org, mapterhorn.com, geoservices.ign.fr, openstreetmap.fr, open-meteo.com, github.com.

## Les cartes de Mapbox, si tu les choisis

Deux fonds de carte, « Mapbox » et « Mapbox Satellite », sont fournis par Mapbox, une société américaine, et dessinés par son propre logiciel, inclus dans l'appli. Ce logiciel reste à l'arrêt tant que tu n'as pas choisi un de ces fonds et accepté la fenêtre qui te le demande. Avec les autres fonds de carte, rien n'est envoyé à Mapbox.

Tant qu'un fond Mapbox est affiché, Mapbox reçoit :

- l'adresse IP du téléphone et les morceaux de carte demandés, donc la zone que tu regardes ;
- la clé de l'appli chez Mapbox, la même pour tous ses utilisateurs ;
- un signal de décompte : Mapbox compte chaque mois les appareils qui affichent ses cartes, à l'aide d'un identifiant tiré au hasard, propre à l'appli sur ce téléphone. Ce n'est ni ton nom, ni un compte, ni un identifiant publicitaire.

Si tu l'acceptes, et seulement dans ce cas, Mapbox reçoit aussi des statistiques d'utilisation : des positions avec leur altitude et leur précision, le modèle du téléphone, sa version d'Android, l'opérateur et le type de réseau, le niveau de batterie, l'orientation et la finesse de l'écran. Mapbox indique les rendre anonymes et s'en servir pour améliorer ses cartes. Ces statistiques sont refusées au départ. Tu changes ce choix quand tu veux avec le bouton d'information que Mapbox affiche en bas de sa carte.

Pour ne plus rien envoyer à Mapbox, choisis un autre fond de carte. Les règles de confidentialité de Mapbox sont sur son site : mapbox.com/legal/privacy.

## Ce que tu partages toi-même

Quand tu partages une affiche, un fichier GPX ou une sauvegarde, c'est toi qui choisis l'appli qui les reçoit (messagerie, e-mail…). VeloGo ne les envoie nulle part ailleurs.

## Autorisations demandées

- Position : carte, enregistrement des sorties, guidage.
- Notifications : la notification d'enregistrement et l'alerte pluie, toutes deux silencieuses.
- Service de premier plan : pour que l'enregistrement d'une sortie continue écran éteint.
- Vibration : consignes de guidage et alerte pluie.
- État du Wi-Fi : demandée par les logiciels qui dessinent les cartes. Android l'accorde sans poser de question.

## Conservation et suppression

Tes données restent sur ton téléphone jusqu'à ce que tu les supprimes. Une sortie, un itinéraire ou un segment se suppriment depuis l'appli ; désinstaller l'appli efface tout. L'auteur n'en a aucune copie : il n'a donc rien à supprimer ni à te transmettre.

## Enfants

VeloGo ne s'adresse pas spécialement aux enfants et ne collecte aucune donnée sur qui que ce soit.

## Santé

VeloGo mesure des distances, des vitesses et des dénivelés. Ce n'est pas un dispositif médical, et l'appli ne recueille aucune donnée de santé.

## Modifications

Si cette politique change, la nouvelle version est publiée à la même adresse et dans l'appli (Réglages, puis Confidentialité), avec sa date.

## Contact

Pour toute question : le dépôt du projet, github.com/ramphortpl/velo (onglet « Issues »), ou l'adresse e-mail indiquée sur la fiche de l'appli dans Google Play.
