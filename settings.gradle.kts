pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Le kit de cartes de Mapbox n'est publié que sur son propre dépôt (sans identifiant depuis la version 11).
        // Rien d'autre n'y est cherché.
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            content {
                includeGroupByRegex("com\\.mapbox.*")
            }
        }
    }
}

rootProject.name = "VeloGo"
include(":app", ":core", ":brouter", ":routing")
