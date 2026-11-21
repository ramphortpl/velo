import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Calcul d'itinéraires : habillage Kotlin du moteur BRouter embarqué (profils vélo, variantes, consignes).
// Aucune dépendance Android : tout se teste sur JVM avec un petit fichier de données réelles.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":brouter"))
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
