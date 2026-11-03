plugins {
    java
    id("com.modrinth.minotaur") version "2.9.0"
}

base {
    archivesName = "RandomSpawnPlus"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

sourceSets.main {
    java.srcDir("../common/src/main/java")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paper_api")}")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("firstJoinAtCoordinates", true)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
    filesMatching("config.yml") {
        expand("firstJoinAtCoordinates" to true)
    }
}

modrinth {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = (findProperty("modrinthProjectId") as? String).orEmpty()
    versionNumber = "${project.version}-paper-${property("mc_26_3")}"
    versionType = "release"
    gameVersions = listOf(property("mc_26_3") as String)
    loaders = listOf("paper")
    uploadFile = tasks.jar.get()
}
