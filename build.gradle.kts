plugins {
    base
}

allprojects {
    group = property("group") as String
    version = property("version") as String
}

/** Builds each supported Fabric, NeoForge, and Paper artifact from the repository root. */
tasks.named("assemble") {
    dependsOn(
        ":fabric-1.21.11:assemble",
        ":fabric-26.3:assemble",
        ":neoforge-1.21.11:assemble",
        ":neoforge-26.3:assemble",
        ":paper-1.21.11:assemble",
        ":paper:assemble"
    )
}

/** Uploads one correctly-labelled file per supported game version and loader. */
tasks.register("publishModrinth") {
    group = "publishing"
    description = "Publishes Fabric/Quilt, NeoForge and Paper/Folia builds to Modrinth."
    dependsOn(
        ":fabric-1.21.11:modrinth",
        ":fabric-26.3:modrinth",
        ":neoforge-1.21.11:modrinth",
        ":neoforge-26.3:modrinth",
        ":paper-1.21.11:modrinth",
        ":paper:modrinth"
    )
}

// Общий репозиторий для подпроектов задаётся в settings.gradle.kts
// (dependencyResolutionManagement), поэтому здесь ничего добавлять не нужно.
