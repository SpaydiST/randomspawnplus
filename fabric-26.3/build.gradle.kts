plugins {
    id("net.fabricmc.fabric-loom") version "1.17.20"
    id("com.modrinth.minotaur") version "2.9.0"
}

base {
    archivesName = "RandomSpawnPlus-fabric-26.3"
}

repositories {
    maven("https://maven.terraformersmc.com/releases/")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

sourceSets.main {
    java.srcDir("../common/src/main/java")
    java.srcDir("../modcommon/src/main/java")
    java.srcDir("../modcommon26/src/main/java")
    java.srcDir("../fabric-shared/src/main/java")
    java.srcDir("../fabric-client/src/main/java")
    java.srcDir("../fabric-client26/src/main/java")
    resources.srcDir("../shared-assets/src/main/resources")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("mc_26_3")}")
    implementation("net.fabricmc:fabric-loader:${property("fabric_loader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_26_3")}")
    compileOnly("com.terraformersmc:modmenu:${property("modmenu_26_3")}")
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
    versionNumber = "${project.version}-fabric-26.3"
    versionType = "release"
    gameVersions = listOf(property("mc_26_3") as String)
    loaders = listOf("fabric", "quilt")
    uploadFile = tasks.jar.get()
}
