pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.terraformersmc.com/releases/")
    }
}

rootProject.name = "RandomSpawnPlus"

include(
    "paper",
    "paper-1.21.11",
    "fabric-26.3",
    "fabric-1.21.11",
    "neoforge-26.3",
    "neoforge-1.21.11",
    "core-tests",
)
