# Publier VeloGo sur Google Play

Ce guide dit ce qui est déjà prêt dans le dépôt, ce que toi seul peux faire, et dans quel ordre. Les règles citées
ont été relevées sur les pages officielles de Google le 26 novembre 2026 ; les liens sont à la fin. Elles
changent souvent : en cas de doute, c'est la Play Console qui a raison.

## En bref

| | |
|---|---|
| Coût | **25 $ une seule fois** (compte développeur Google Play). Rien d'autre : l'appli est gratuite, sans achat ni publicité. |
| Ce qu'il te faut | Une pièce d'identité, un téléphone Android (version 10 ou plus) pour la vérification du compte, une adresse e-mail que tu acceptes de voir affichée. |
| Affiché publiquement | Ton **nom légal**, ton **pays** et l'adresse e-mail du compte développeur. Pas ton adresse postale (elle ne l'est que pour les applis payantes ou les professionnels). |
| Avant d'ouvrir à tous | Avec un compte personnel : un **test fermé avec 12 testeurs inscrits pendant 14 jours d'affilée**, puis une demande d'accès à la production (réponse sous une semaine en général). |
| Délai réaliste | Trois à quatre semaines entre la création du compte et l'appli visible par tous. |

## Ce qui est déjà prêt

- **Le fichier à déposer** : chaque version publiée dans les Releases GitHub contient, à côté de l'APK, un fichier
  `VeloGo-x.y.z-play.aab`. C'est la même appli au format qu'exige Google Play (« Android App Bundle »), signée de
  la même clé, pour toutes les architectures de téléphone.
- **Les différences avec la version GitHub**, imposées par les règles du magasin :
  - elle ne cherche pas ses mises à jour sur GitHub : c'est Google Play qui la met à jour, et lui seul en a le droit ;
  - elle ne demande pas directement à Android de ne pas la mettre en veille (autorisation
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, que le magasin n'admet que pour quelques familles d'applis) : elle
    ouvre à la place la fiche de l'appli dans les réglages du téléphone, où l'on choisit Batterie > « Non restreinte ».
- **Les exigences techniques** : Android 17 visé (le magasin exige au moins Android 16), bibliothèques compatibles
  avec les pages mémoire de 16 Ko, appli utilisable sur tablette et téléphone pliable (elle n'y est plus bloquée en
  portrait), aucune position demandée « en arrière-plan », aucun identifiant publicitaire.
- **Avant de demander la position**, l'appli dit ce qu'elle en fait et ce qui sort du téléphone, et donne accès à sa
  politique de confidentialité (Réglages > Confidentialité).
- **La politique de confidentialité** : `docs/CONFIDENTIALITE.md`, reprise mot pour mot dans l'appli.
- **La fiche du magasin** : textes dans `docs/play/fiche.md`, icône et image de présentation dans `docs/play/`.
- **Les réponses aux formulaires** : plus bas dans ce guide.

Tout cela est vérifié à chaque fabrication. L'outil de Google dont se sert le Play Store (« bundletool ») tire un
APK d'un fichier AAB d'essai, au même code que celui qui est publié ; cet APK est installé sur l'émulateur
par-dessus la version GitHub, et doit démarrer, garder les sorties, calculer un itinéraire et ne proposer aucune
mise à jour hors du magasin.

## Les étapes

### 1. Créer le compte développeur

Sur [play.google.com/console](https://play.google.com/console), avec ton compte Google : compte **personnel**,
25 $ par carte, puis vérification de ton identité (pièce d'identité), de ton adresse e-mail et de ton numéro de
téléphone, et du téléphone Android sur lequel tu installes l'appli « Play Console ».

Google demande aussi, dès la création, si tu es un **professionnel** (« trader ») au sens européen. Pour une appli
gratuite, sans publicité ni achat, publiée par un particulier en dehors de toute activité professionnelle, la
réponse honnête est non ; c'est à toi de la donner. Elle changerait le jour où l'appli rapporterait de l'argent :
Google afficherait alors ton adresse postale, et ton numéro de téléphone si tu te déclares professionnel.

### 2. Créer l'appli

« Créer une application » : nom `VeloGo – GPS et compteur vélo`, langue français (France), type **Application**,
**Gratuite**. L'identifiant de l'appli, `fr.velogo.app`, sera pris du premier fichier déposé : il ne pourra plus
changer.

### 3. Signature : garder la même clé partout (recommandé)

C'est la seule étape délicate, et elle ne se fait qu'une fois, **avant** le premier dépôt.

Par défaut, Google fabrique sa propre clé pour signer ce qu'il distribue. L'appli venue du Play Store n'aurait
alors pas la même signature que celle des Releases GitHub : pour passer de l'une à l'autre il faudrait
désinstaller, donc perdre ses trajets enregistrés et ses segments (les sorties, elles, se sauvegardent dans
Réglages > Sauvegarde).

Pour garder **une seule clé partout**, celle du dépôt :

1. Dans la Play Console, à la création de la première version (test interne), section « Intégrité de l'appli » /
   « Signature d'application » : choisis **d'utiliser ta propre clé**, puis « Exporter et importer une clé depuis
   un keystore Java ». La console te fait télécharger un fichier `encryption_public_key.pem`.
2. Sur GitHub, dépose ce fichier dans le dossier `signing/` sous le nom `play_encryption_public_key.pem`
   (« Add file » > « Upload files »).
3. Onglet « Actions », workflow **« Clé pour Google Play »**, « Run workflow », écris `EXPORTER`.
4. Télécharge l'artefact produit et dépose dans la console le fichier `cle-pour-google-play.zip` qu'il contient.

Ce workflow se sert de l'outil officiel de Google (PEPK) : la copie de la clé est chiffrée de façon que seul Google
puisse la relire, elle reste téléchargeable un jour, et le mot de passe ne quitte pas le secret GitHub.
**Il n'a pas pu être essayé** : il faut une vraie Play Console pour obtenir la clé publique. S'il échoue, la console
donne la même commande à lancer sur un ordinateur ; dis-le-moi et je corrige.

Si tu préfères laisser Google fabriquer la clé, saute cette étape : tout le reste marche pareil.

### 4. Déposer la politique de confidentialité

Google demande une adresse web publique et stable. Elle existe depuis que le code est sur la branche `main`
(27 novembre 2026) :

`https://github.com/ramphortpl/velo/blob/main/docs/CONFIDENTIALITE.md`

C'est cette adresse qu'il faut coller dans la Play Console. (GitHub Pages donnerait une page plus sobre, si tu
l'actives un jour sur le dépôt ; ce n'est pas nécessaire.)

Google exige une page publique, sans connexion, qui ne soit ni un PDF ni un document modifiable par les visiteurs.
Une page de dépôt GitHub répond à ces critères, mais Google ne cite aucun hébergeur : c'est une déduction, pas une
garantie.

### 5. Remplir « Contenu de l'appli »

Les réponses sont plus bas (« Réponses aux formulaires »). Il y a une chose que tu dois fabriquer toi-même : une
**courte vidéo** montrant l'enregistrement d'une sortie (voir « Service de premier plan »).

### 6. Fiche du magasin

Copie les textes de `docs/play/fiche.md`, dépose `icone-512.png`, `banniere-1024x500.png` et de 2 à 8 captures
d'écran du dossier `docs/play/ecrans/`.

### 7. Tester, puis publier

1. **Test interne** (aucune condition) : dépose `VeloGo-x.y.z-play.aab`, ajoute ton adresse comme testeur, installe
   depuis le lien que donne la console. Avec la même clé, l'appli s'installe par-dessus celle des Releases GitHub.
2. **Test fermé** : 12 personnes au moins, inscrites sans interruption pendant 14 jours.
3. **Demande d'accès à la production**, puis publication. Pays : la France (l'appli ne couvre qu'elle).

Pour chaque nouvelle version, il suffira de déposer le nouveau fichier `-play.aab` : son numéro est toujours
supérieur au précédent.

## Réponses aux formulaires

### Sécurité des données

Rien n'est envoyé à l'auteur de l'appli, mais le formulaire compte comme « collecté » tout ce qui quitte le
téléphone, et comme « partagé » ce qui part chez un tiers.

- L'appli collecte ou partage-t-elle des données ? **Oui.**
- Toutes les données sont-elles chiffrées pendant leur transfert ? **Oui** (tout passe en HTTPS).
- Création de compte : **l'appli ne permet pas de créer de compte.**

| Type de données | Collectée | Partagée | Finalité | Obligatoire ? |
|---|---|---|---|---|
| Position approximative | Oui | Oui | Fonctionnement de l'appli | Facultative |
| Position précise | Oui | Oui | Fonctionnement de l'appli | Facultative |
| Historique des recherches dans l'appli | Oui | Oui | Fonctionnement de l'appli | Facultative |

Pourquoi : la météo envoie la position arrondie à un kilomètre à Open-Meteo (Google appelle « précise » toute
position connue à moins de 3 km² près) ; la correction d'altitude envoie les points des sorties à l'IGN ; la
recherche d'adresse envoie le texte tapé et le centre de la carte à l'IGN. Chacune se coupe ou s'évite (réglages
Météo et Altitude, carte hors ligne), d'où « facultative ». Aucune autre donnée : ni nom, ni contacts, ni photos,
ni identifiant d'appareil, ni données de santé (les sorties restent sur le téléphone).

« Partagée » est la réponse prudente. Le formulaire dispense de la cocher quand l'utilisateur a été prévenu et a
donné son accord, ce que fait l'écran qui précède la demande de position ; savoir si cela suffit pour des services
publics comme ceux-là n'est écrit nulle part.

### Position précise (nouvelle déclaration, obligatoire à partir du 27 janvier 2027)

Usages à cocher : **suivi en direct** (enregistrement de la sortie) et **navigation** (guidage). L'appli n'a pas
l'autorisation de position en arrière-plan.

### Service de premier plan (type « Localisation »)

- Usage : « Autre », à décrire : *Enregistrement d'une sortie à vélo démarrée par l'utilisateur (trace GPS,
  distance, vitesse, dénivelé) et guidage sur un itinéraire, qui doivent continuer écran éteint. Une notification
  permanente permet de mettre en pause et d'arrêter. Le service s'arrête quand l'utilisateur termine la sortie.*
- Conséquence si la tâche est interrompue : *la trace de la sortie est incomplète et le guidage s'arrête.*
- **Vidéo** : filme ton écran (une trentaine de secondes) : ouvrir VeloGo, toucher « Démarrer », montrer la
  notification d'enregistrement, éteindre puis rallumer l'écran, maintenir « Terminer ». Dépose-la sur YouTube
  (« non répertoriée ») ou Google Drive et colle le lien. L'essai sur émulateur en enregistre une
  (`enregistrement_d_une_sortie.mp4`, dans l'artefact `essai-emulateur` de chaque fabrication) : tu peux t'en servir
  si elle te convient.

### Applications de santé

Formulaire obligatoire pour toutes les applis. Catégorie : **Activité et forme physique**. Aucune fonction
médicale. La description de la fiche précise déjà que VeloGo n'est pas un dispositif médical.

### Les autres

- Annonces : **aucune publicité.**
- Accès à l'appli : **tout est accessible sans identifiant.**
- Classification du contenu : questionnaire à remplir, rien de sensible (ni violence, ni échanges entre
  utilisateurs, ni achats).
- Public cible : **13 ans et plus** ; l'appli ne s'adresse pas spécialement aux enfants.
- Identifiant publicitaire : **non utilisé.**
- Fonctionnalités financières, applis gouvernementales, actualités : **non.**

## Points de vigilance

- **Enregistrement et batterie.** Pendant une sortie, l'appli empêche le téléphone de s'endormir (« wake lock »),
  par sécurité sur les Samsung. Google mesure cela : au-delà de deux heures par jour pour plus de 5 % des
  utilisateurs, la Play Console signale un « comportement à améliorer » et l'appli peut être moins mise en avant.
  Cela n'empêche pas de publier. Si l'alerte apparaît (Play Console > Android vitals), dis-le-moi : on pourra
  essayer sans, mais seulement après l'avoir vérifié sur ton téléphone, car un enregistrement coupé serait bien
  pire.
- **Grands écrans.** Google annonce qu'à partir d'avril 2027 une appli devra remplir correctement l'écran des
  tablettes et des pliables pour être publiée ou mise à jour. VeloGo s'y adapte (largeurs bornées, écran guidon en
  paysage), mais n'a été regardée que sur ordinateur à ces tailles, pas sur une vraie tablette.
- **Vérification des développeurs.** Google impose peu à peu que tout développeur soit identifié, y compris pour
  les applis installées hors du magasin (calendrier mondial à partir de janvier 2027, sans date pour la France).
  Le compte Play Console en tient lieu : une fois créé, il couvre aussi les APK des Releases GitHub.
- **Nom et identifiant.** Le 26 novembre 2026, aucune appli publiée sur Google Play ne portait l'identifiant
  `fr.velogo.app`, et la recherche « velogo » du magasin français ne montrait aucune appli de ce nom (la plus
  proche s'appelle « Velogos »). Les marques déposées, elles, n'ont pas été vérifiées : avant d'ouvrir l'appli à
  tous, un coup d'œil à la base gratuite de l'INPI (data.inpi.fr) évite une mauvaise surprise, car Google retire
  une appli dont le nom est contesté par le propriétaire d'une marque.
- **Type de compte.** Les applis médicales exigent un compte « organisation ». Les applis d'activité physique ne
  sont pas citées : rien n'indique qu'un compte personnel pose problème, rien ne le garantit non plus.
- **Ce qui n'a pas pu être vérifié ici** : le workflow « Clé pour Google Play », et tout ce qui se passe dans la
  Play Console elle-même (il faut un compte).

## Une appli publique : ce que demandent les services qu'elle utilise

Tant que VeloGo n'était que pour toi, la question ne se posait pas. Voici ce que disent, pour une appli ouverte à
tous, les conditions de chaque service (relevées sur leurs pages le 26 novembre 2026). Rien n'empêche de publier ;
trois points sont à surveiller si l'appli a du succès.

| Service | Appli publique | À surveiller |
|---|---|---|
| OpenFreeMap (fond « Plan ») | Autorisée, sans limite ni inscription, même pour un usage commercial | Rien. La mention « © OpenMapTiles » est obligatoire sur la carte : elle y est. |
| IGN (Plan IGN, photos, adresses, altitude) | Autorisée, « à des fins commerciales ou non », sans clé | Rien : les limites sont comptées par téléphone. |
| Protomaps et Mapterhorn (cartes hors ligne) | Redistribution permise | Rien. |
| Mapterhorn (relief du fond « Plan », depuis la version 0.8) | Leur serveur de tuiles est ouvert à tous, « for commercial and non-commercial purposes », sans limite annoncée (relevé le 27 novembre 2026) | Rien pour l'instant : c'est un projet bénévole hébergé gracieusement. S'il fermait son serveur, le plan s'afficherait sans ses ombres, rien de plus. |
| **Open-Meteo (météo)** | Gratuite pour une appli **sans publicité ni abonnement** | Au-delà de **10 000 demandes par jour pour toute l'appli** (quelques centaines de cyclistes qui roulent le même jour), ils demandent qu'on leur écrive (info@open-meteo.com). Avec de la publicité ou un abonnement, l'offre payante devient obligatoire (29 $ par mois en 2023, prix actuel non vérifié). |
| **CyclOSM (serveurs d'OpenStreetMap France)** | Tolérée pour une appli gratuite, sans but lucratif, au « trafic modéré » | Aucun chiffre n'est donné, et ils peuvent couper l'accès sans préavis. Si l'appli dépasse quelques centaines d'utilisateurs, leur écrire (openstreetmap.fr) ou retirer ce fond : la carte « Vélo hors ligne » montre les mêmes aménagements sans dépendre d'eux. |
| **GitHub (cartes hors ligne, APK)** | C'est l'usage prévu des « Releases », sans limite publiée | Une clause leur permet de freiner un dépôt dont le trafic serait « nettement excessif ». Avec des milliers de téléchargements de régions, il faudrait un autre hébergement. |

Deux conséquences si un jour l'appli rapportait de l'argent (publicité, abonnement, version payante) : la météo et
le fond CyclOSM ne seraient plus permis tels quels, et Google te demanderait de te déclarer professionnel.

## Sources

- Niveau d'API exigé : <https://support.google.com/googleplay/android-developer/answer/11926878>
- Pages mémoire de 16 Ko : <https://developer.android.com/guide/practices/page-sizes>
- Compte personnel, vérifications, tests exigés : <https://support.google.com/googleplay/android-developer/answer/14151465> · <https://support.google.com/googleplay/android-developer/answer/13628312>
- Informations affichées publiquement : <https://support.google.com/googleplay/android-developer/answer/14316361>
- Mise à jour hors du magasin (règle « Device and Network Abuse ») : <https://support.google.com/googleplay/android-developer/answer/16559646>
- Exemption d'économie de batterie : <https://developer.android.com/training/monitoring-device-state/doze-standby>
- « Wake locks » excessifs : <https://developer.android.com/topic/performance/vitals/excessive-wakelock>
- Service de premier plan : <https://support.google.com/googleplay/android-developer/answer/13392821>
- Politique de confidentialité : <https://support.google.com/googleplay/android-developer/answer/10144311>
- Sécurité des données : <https://support.google.com/googleplay/android-developer/answer/10787469>
- Applications de santé : <https://support.google.com/googleplay/android-developer/answer/14738291>
- Position précise : <https://support.google.com/googleplay/android-developer/answer/17033915>
- Signature par Google Play : <https://support.google.com/googleplay/android-developer/answer/9842756>
- Images et textes de la fiche : <https://support.google.com/googleplay/android-developer/answer/9866151>
- Grands écrans : <https://developer.android.com/about/versions/17/behavior-changes-17> · <https://developer.android.com/blog/posts/google-play-adaptive-quality-standards>
- Vérification des développeurs : <https://developer.android.com/blog/posts/android-developer-verification-2027>
