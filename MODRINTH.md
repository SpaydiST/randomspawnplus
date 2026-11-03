# Publishing to Modrinth

The project publishes a separate file for every supported Minecraft version and
loader. This is what makes Modrinth show the correct filters in its version
list instead of presenting every download as a generic Java archive.

| Artifact | Minecraft version | Modrinth loaders |
| --- | --- | --- |
| `fabric-1.21.11` | 1.21.11 | Fabric, Quilt |
| `fabric-26.3` | 26.3 | Fabric, Quilt |
| `neoforge-1.21.11` | 1.21.11 | NeoForge |
| `neoforge-26.3` | 26.3 | NeoForge |
| `paper-1.21.11` | 1.21.11 | Paper |
| `paper` | 26.3 | Paper |

From the repository root, `.\gradlew.bat assemble` builds all six distributable artifacts into their respective module `build/libs` directories, including both Paper targets.

Set `modrinthProjectId` in `gradle.properties` to the Modrinth project ID and
set the `MODRINTH_TOKEN` environment variable. Then publish all files with:

```powershell
.\gradlew.bat publishModrinth
```

The Fabric artifact is Quilt-compatible through Quilt Loader's Fabric-mod
compatibility layer. It uses `fabric.mod.json`, Fabric API and the same ModMenu
configuration integration, so do not upload a duplicate Quilt JAR: upload the
same Fabric JAR once, classified with both `fabric` and `quilt` loaders.

Do not upload the plain `RandomSpawnPlus-<version>.jar` from `paper/build/libs`
through the Modrinth web form when publishing a multi-loader release. Its
`plugin.yml` can be automatically recognized, but one Bukkit plugin
file cannot declare the separate Fabric and NeoForge implementations. Run
`publishModrinth` instead: it uploads the six distributable files above and
explicitly assigns every supported loader and Minecraft version. The Paper file
is assigned only to Paper. Folia is unsupported because the plugin uses the
global Bukkit scheduler and synchronous chunk loading from the server tick.

Project-page information such as gallery images, categories and the project
summary is configured in the Modrinth web dashboard. The Gradle task only
uploads files and their version/loader metadata; it does not create or alter a
public project by itself.
