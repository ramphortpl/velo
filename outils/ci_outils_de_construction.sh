#!/bin/bash
# Premier appel à Gradle d'une fabrication : il fait venir Gradle, ses extensions (Android, Kotlin…) et vérifie
# que le projet se configure.
#
# Quand la mémoire des téléchargements est vide (première fabrication, ou versions des bibliothèques modifiées),
# tout vient des dépôts de bibliothèques, qui refusent parfois de répondre à une machine de GitHub : Gradle dit
# alors « Plugin … was not found », sans la cause. On insiste donc, et à la dernière tentative on affiche la
# cause exacte (quel dépôt, quelle réponse) pour pouvoir y remédier.

set -u

tentative() {
  ./gradlew help --console=plain -q "$@"
}

for ATTENTE in 0 30 90; do
  if [ "$ATTENTE" -gt 0 ]; then
    echo "::warning::Téléchargement des outils de construction en échec : nouvelle tentative dans $ATTENTE secondes."
    sleep "$ATTENTE"
  fi
  if [ "$ATTENTE" -lt 90 ]; then
    tentative && exit 0
  else
    if tentative --stacktrace > outils-de-construction.log 2>&1; then
      exit 0
    fi
    echo "Ce que dit Gradle :"
    sed -n '/^\* What went wrong/,/^\* Try/p' outils-de-construction.log | grep -v '^\* Try' | head -n 25
    echo "Causes :"
    grep -E 'Caused by: ' outils-de-construction.log | awk '!vu[$0]++' | head -n 15
  fi
done

echo "::error::Gradle n'a pas pu se préparer (détail dans le journal de cette étape). S'il s'agit d'un dépôt de bibliothèques qui ne répond pas à cette machine, le code n'est pas en cause : relance le workflow (bouton « Re-run jobs »)."
exit 1
