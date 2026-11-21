# BRouter embarqué

Moteur de calcul d'itinéraire de [BRouter](https://github.com/abrensch/brouter), version **1.7.10**
(commit `4d2639a`), sous licence MIT (voir `LICENSE`). BRouter n'est pas publié sur Maven : ses sources sont
donc copiées ici et compilées avec l'appli.

Contenu de `src/main/java/btools` : les modules `brouter-util`, `brouter-codec`, `brouter-expressions`,
`brouter-mapaccess` et `brouter-core` du dépôt d'origine, sans leurs tests.

## Ce qui diffère de l'original

1. `btools/mapaccess/NodesCache.java`, méthode `fileForSegment` : les données sont cherchées par carrés d'un degré
   (`dalle-E8_N42.rd5`) au lieu des dalles de 5 degrés (`E5_N40.rd5`). Les fichiers gardent le format d'origine
   (un seul bloc par fichier) ; ils sont fabriqués par `cartes/outils/dalles.py`.
2. `btools/router/VeloGoBridge.java` (fichier ajouté) : donne accès, depuis notre code, aux consignes de
   changement de direction et aux attributs des voies, que BRouter garde internes à son paquet.

## Mettre à jour BRouter

Recopier les cinq dossiers `src/main/java/btools` de la nouvelle version, réappliquer les deux points ci-dessus,
mettre à jour `routing/src/main/resources/brouter/` (profils et `lookups.dat`) puis lancer `./gradlew :routing:test`.
La version de `lookups.dat` doit correspondre à celle des données publiées sur brouter.de.
