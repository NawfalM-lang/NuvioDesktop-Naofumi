package com.nuvio.app.features.player.desktop

import java.nio.file.Files
import java.nio.file.Path

/** Packaged apps use their private runtime; Gradle development can still use Python. */
internal object DesktopSubtitleSyncRuntime {
    fun command(
        worker: Path,
        job: Path,
        resourcesDirectory: String? = System.getProperty("compose.application.resources.dir"),
        overrideDirectory: String? = System.getProperty("nuvio.subtitleSync.runtimeDir"),
        workingDirectory: Path = Path.of(System.getProperty("user.dir")),
    ): List<String> {
        val explicit = overrideDirectory?.takeIf(String::isNotBlank)?.let(Path::of)
        val packagedRoot = resourcesDirectory?.takeIf(String::isNotBlank)?.let(Path::of)
        val candidates = when {
            explicit != null -> listOf(explicit)
            packagedRoot != null -> listOf(
                // Compose merges appResourcesRootDir/windows into the resources root.
                packagedRoot.resolve("subtitle-sync"),
                // Keep compatibility with manually staged, unflattened resource trees.
                packagedRoot.resolve("windows/subtitle-sync"),
            )
            else -> emptyList()
        }
        if (candidates.isNotEmpty()) {
            val executable = candidates.asSequence()
                .map { it.resolve("nuvio-subtitle-sync.exe") }
                .firstOrNull(Files::isRegularFile)
            check(executable != null) {
                "Bundled subtitle runtime is missing. Reinstall this Nuvio build."
            }
            return listOf(executable.toAbsolutePath().toString(), job.toString())
        }
        // Accommodate both Gradle's composeApp working directory and the repository root.
        for (base in listOf(workingDirectory, workingDirectory.resolve("composeApp"))) {
            val executable = base.resolve("build/subtitle-sync/app-resources/windows/subtitle-sync/nuvio-subtitle-sync.exe")
            if (Files.isRegularFile(executable)) {
                return listOf(executable.toAbsolutePath().toString(), job.toString())
            }
        }
        return listOf("py", "-3", worker.toString(), job.toString())
    }
}
