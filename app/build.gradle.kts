import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Version = MAJEUR.MINEUR (version.properties) + numéro de build GitHub (croissant à chaque push),
// pour que chaque APK publié s'installe par-dessus le précédent.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val buildNumber = providers.gradleProperty("velogo.buildNumber").orNull?.toIntOrNull() ?: 0
val appVersionName = "${versionProps["major"]}.${versionProps["minor"]}.$buildNumber"

// Le mot de passe de la clé n'existe que dans le secret GitHub VELOGO_KEYSTORE_PASSWORD.
val keystorePassword = providers.environmentVariable("VELOGO_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "fr.velogo.app"
    // Compose 1.13 exige le SDK 37.1 au minimum.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "fr.velogo.app"
        minSdk = 26
        targetSdk = 37
        versionCode = maxOf(buildNumber, 1)
        versionName = appVersionName
        // Dépôt GitHub où l'appli cherche ses mises à jour et ses packs de cartes.
        buildConfigField("String", "GITHUB_REPO", "\"${providers.gradleProperty("velogo.githubRepo").getOrElse("ramphortpl/velo")}\"")
        ndk {
            // Le Galaxy A05s (comme tous les téléphones récents) est en arm64.
            abiFilters += "arm64-v8a"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += "fr"
    }

    signingConfigs {
        if (keystorePassword != null) {
            create("release") {
                storeFile = rootProject.file("signing/velogo-release.p12")
                storeType = "pkcs12"
                storePassword = keystorePassword
                keyAlias = "velogo"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sans mot de passe (build local), l'APK release sort non signé : il n'est jamais publié.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.maxHeapSize = "2g"
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("MissingTranslation", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "ChromeOsAbiSupport")
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)

    implementation(libs.room3.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room3.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.core.ktx)
    implementation(libs.core.location.altitude)
    implementation(libs.core.splashscreen)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.maplibre)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
