plugins {
    java
    id("com.modrinth.minotaur") version "2.9.0"
}

base {
    archivesName = "RandomSpawnPlus-paper-1.21.11"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

sourceSets.main {
    java.srcDir("../common/src/main/java")
    java.srcDir("../paper/src/main/java")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paper_api_1_21_11")}")
}

tasks.processResources {
    from("../paper/src/main/resources/config.yml")
    inputs.property("version", project.version)
    inputs.property("firstJoinAtCoordinates", false)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
    filesMatching("config.yml") {
        expand("firstJoinAtCoordinates" to false)
    }
}

modrinth {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = (findProperty("modrinthProjectId") as? String).orEmpty()
    versionNumber = "${project.version}-paper-1.21.11"
    versionType = "release"
    gameVersions = listOf(property("mc_1_21_11") as String)
    loaders = listOf("paper")
    uploadFile = tasks.jar.get()
}
