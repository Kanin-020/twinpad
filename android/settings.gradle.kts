pluginManagement {
    repositories {
        // Repositorios estándar oficiales (ENG §11)
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Twinpad"
include(":app", ":hid")
