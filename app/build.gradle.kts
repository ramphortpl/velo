import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.room3)
}

// Version = MAJEUR.MINEUR (version.properties) + numéro de build GitHub (croissant à chaque push),
// pour que chaque APK publié s'installe par-dessus le précédent.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val buildNumber = providers.gradleProperty("velogo.buildNumber").orNull?.toIntOrNull() ?: 0
val appVersionName = "${versionProps["major"]}.${versionProps["minor"]}.$buildNumber"

// Le mot de passe de la clé n'existe que dans le secret GitHub VELOGO_KEYSTORE_PASSWORD. Il ne contient ni espace
// ni retour à la ligne : ceux qu'un copier-coller aurait glissés autour sont ignorés.
val keystorePassword = providers.environmentVariable("VELOGO_KEYSTORE_PASSWORD").orNull
    ?.filterNot { it.isWhitespace() }
    ?.takeIf { it.isNotEmpty() }

// Jeton public de Mapbox (« pk.… ») : il n'existe que dans le secret GitHub MAPBOX_ACCESS_TOKEN et entre dans l'appli
// à la fabrication. Sans lui (fabrication locale, autre dépôt), les fonds Mapbox ne sont pas proposés. Seul un jeton
// public est accepté : un jeton secret (« sk.… ») collé par erreur ne doit jamais partir dans une appli.
val mapboxToken = providers.environmentVariable("MAPBOX_ACCESS_TOKEN").orNull
    ?.filterNot { it.isWhitespace() }
    ?.takeIf { token -> token.startsWith("pk.") && token.all { it.isLetterOrDigit() || it in "._-" } }
    ?: ""

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
        // Vide quand la fabrication n'a pas de jeton : l'appli ne propose alors pas les fonds Mapbox.
        buildConfigField("String", "MAPBOX_TOKEN", "\"$mapboxToken\"")
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
        // Variante d'essai pour l'émulateur de la CI : exactement le code minifié de la release, mais signée avec
        // la clé de test et avec les bibliothèques natives x86_64 (l'émulateur n'est pas un téléphone arm64).
        create("smoke") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".essai"
            versionNameSuffix = "-essai"
            matchingFallbacks += "release"
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    // Deux façons de distribuer la même appli, signées de la même clé (on passe de l'une à l'autre sans désinstaller).
    flavorDimensions += "distribution"
    productFlavors {
        // Releases GitHub : l'appli cherche elle-même ses mises à jour et peut demander directement à Android de
        // ne pas la mettre en veille (voir src/github/AndroidManifest.xml).
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "DIRECT_BATTERY_EXEMPTION", "true")
        }
        // Google Play : le magasin interdit qu'une appli se mette à jour autrement que par lui, et n'admet la
        // demande directe d'exemption de batterie que pour quelques familles d'applis. Toutes les architectures
        // sont fournies : le magasin n'envoie à chaque téléphone que la sienne.
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "DIRECT_BATTERY_EXEMPTION", "false")
            ndk {
                abiFilters += listOf("armeabi-v7a", "x86_64")
            }
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
        disable += setOf("MissingTranslation", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "ChromeOsAbiSupport", "ObsoleteSdkInt")
        // Règle apportée par une bibliothèque dont dépend le kit de Mapbox (Timber) : elle voudrait qu'on écrive le
        // journal avec elle. L'appli n'écrit que quelques lignes de diagnostic, avec le journal d'Android.
        disable += "LogNotTimber"
    }
}

// L'APK des Releases GitHub est téléchargé tel quel : ses bibliothèques natives y sont compressées (le moteur de
// Mapbox pèse à lui seul 23 Mo, contre 9 une fois compressé), et Android les déplie à l'installation. Dans le
// fichier de Google Play elles restent telles quelles : le magasin compresse lui-même ce qu'il envoie.
androidComponents {
    onVariants(selector().withFlavor("distribution" to "github")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

/**
 * Recopie la politique de confidentialité du dépôt (docs/CONFIDENTIALITE.md) dans les ressources de l'appli : la
 * page publiée en ligne et celle qu'affiche l'appli (Réglages > Confidentialité) sont ainsi le même texte.
 */
abstract class CopyPrivacyPolicy : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile
        target.mkdirs()
        source.get().asFile.copyTo(target.resolve("confidentialite.md"), overwrite = true)
    }
}

val copyPrivacyPolicy = tasks.register<CopyPrivacyPolicy>("copyPrivacyPolicy") {
    source.set(rootProject.layout.projectDirectory.file("docs/CONFIDENTIALITE.md"))
}

androidComponents {
    beforeVariants { variant ->
        // La version Play n'existe qu'en version publiable et en version d'essai pour l'émulateur.
        if (variant.flavorName == "play" && variant.buildType == "debug") variant.enable = false
    }
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyPrivacyPolicy, CopyPrivacyPolicy::outputDir)
    }
}

// Les schémas de la base sont versionnés : ils servent aux migrations d'un jalon au suivant,
// pour que les sorties déjà enregistrées survivent aux mises à jour.
room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":routing"))

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
    // Second moteur de carte, pour les seuls fonds « Mapbox » : ses conditions n'admettent ses cartes que dans
    // son propre kit. Il ne démarre que si l'un de ces fonds est choisi (voir MapboxSupport).
    implementation(libs.mapbox.maps)
    implementation(libs.mapbox.telemetry)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    // Pilote SQLite d'Android pour les tests de la base sur l'ordinateur (voir RideAnalysisTest).
    testImplementation(libs.sqlite.framework)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
