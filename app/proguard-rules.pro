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
