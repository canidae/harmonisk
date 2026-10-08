pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.2.1"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
        id("com.google.devtools.ksp") version "2.3.9"
        id("com.google.dagger.hilt.android") version "2.59.2"
        kotlin("plugin.serialization") version "2.3.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Harmonisk"
include(":app")
