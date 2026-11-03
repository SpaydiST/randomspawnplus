plugins {
    id("fabric-loom") version "1.17.20"
    id("com.modrinth.minotaur") version "2.9.0"
}

base {
    archivesName = "RandomSpawnPlus-fabric-1.21.11"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

loom {
    officialMojangMappings()
}

repositories {
    maven("https://maven.terraformersmc.com/releases/")
}

sourceSets.main {
    java.srcDir("../common/src/main/java")
    java.srcDir("../modcommon/src/main/java")
    java.srcDir("../modcommon121/src/main/java")
    java.srcDir("../fabric-shared/src/main/java")
    java.srcDir("../fabric-client/src/main/java")
    java.srcDir("../fabric-client121/src/main/java")
    resources.srcDir("../shared-assets/src/main/resources")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("mc_1_21_11")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_1_21_11")}")
    modCompileOnly("com.terraformersmc:modmenu:${property("modmenu_1_21_11")}")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

modrinth {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = (findProperty("modrinthProjectId") as? String).orEmpty()
    versionNumber = "${project.version}-fabric-1.21.11"
    versionType = "release"
    gameVersions = listOf(property("mc_1_21_11") as String)
    loaders = listOf("fabric", "quilt")
    uploadFile = tasks.remapJar.get()
}