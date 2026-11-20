# VeloGo — règles R8.
# Les bibliothèques (Compose, Room, MapLibre, OkHttp, kotlinx.serialization) fournissent leurs propres règles.

# Garder les numéros de ligne pour pouvoir lire une trace d'erreur.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
