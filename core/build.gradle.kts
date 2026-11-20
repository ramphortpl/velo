import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Logique pure (aucune dépendance Android) : géodésie, moteur de sortie, GPX, stats.
// Tout ce qui se calcule est ici pour être testé sur JVM, vite et sans téléphone.
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
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
