// Moteur de calcul d'itinéraire BRouter (licence MIT), embarqué tel quel : il n'est pas publié sur Maven.
// Voir brouter/README.md pour la provenance et l'unique modification.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(11)
    // Code tiers : ses avertissements ne nous apprennent rien.
    options.compilerArgs.add("-nowarn")
    options.isWarnings = false
}
