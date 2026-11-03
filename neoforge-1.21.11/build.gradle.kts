plugins {
    id("net.neoforged.moddev") version "2.0.147"
    id("com.modrinth.minotaur") version "2.9.0"
}

base {
    archivesName = "RandomSpawnPlus-neoforge-1.21.11"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

neoForge {
    version = property("neoforge_1_21_11") as String
}

sourceSets.main {
    java.srcDir("../common/src/main/java")
    java.srcDir("../modcommon/src/main/java")
    java.srcDir("../modcommon121/src/main/java")
    java.srcDir("../neoforge-shared/src/main/java")
    java.srcDir("../neoforge-client/src/main/java")
    java.srcDir("../neoforge-client121/src/main/java")
    resources.srcDir("../shared-assets/src/main/resources")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}

modrinth {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = (findProperty("modrinthProjectId") as? String).orEmpty()
    versionNumber = "${project.version}-neoforge-1.21.11"
    versionType = "release"
    gameVersions = listOf(property("mc_1_21_11") as String)
    loaders = listOf("neoforge")
    uploadFile = tasks.jar.get()
}
