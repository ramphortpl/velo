# VeloGo — règles R8.
# Les bibliothèques (Compose, Room, MapLibre, OkHttp, kotlinx.serialization) fournissent leurs propres règles.

# Garder les numéros de ligne pour pouvoir lire une trace d'erreur.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Clés de navigation : elles sont sérialisées pour restaurer l'écran en cours quand Android recrée l'appli.
-keep class fr.velogo.app.ui.*Key { *; }
-keep class fr.velogo.app.ui.*Key$* { *; }

# BRouter (calcul d'itinéraire embarqué) crée certaines de ses classes par leur nom, d'après le contenu des profils.
# Le moteur est petit : on le garde entier plutôt que de risquer un itinéraire qui échoue dans la seule version minifiée.
-keep class btools.** { *; }
