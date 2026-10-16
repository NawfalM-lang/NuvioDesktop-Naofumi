package com.nuvio.app.features.player.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopSubtitleSyncRuntimeTest {
    @Test
    fun packagedCommandUsesPrivateExecutableAndHandlesSpaces() {
        val root = Files.createTempDirectory("nuvio resources ")
        try {
            val executable = root.resolve("subtitle-sync/nuvio-subtitle-sync.exe")
            Files.createDirectories(executable.parent)
            Files.createFile(executable)
            assertEquals(
                listOf(executable.toAbsolutePath().toString(), "job with spaces.json"),
                DesktopSubtitleSyncRuntime.command(Path.of("worker.py"), Path.of("job with spaces.json"), root.toString(), null),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun acceptsLegacyUnflattenedResources() {
        val root = Files.createTempDirectory("legacy resources ")
        try {
            val executable = root.resolve("windows/subtitle-sync/nuvio-subtitle-sync.exe")
            Files.createDirectories(executable.parent)
            Files.createFile(executable)
            assertEquals(
                executable.toAbsolutePath().toString(),
                DesktopSubtitleSyncRuntime.command(Path.of("worker.py"), Path.of("job.json"), root.toString(), null).first(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun prefersActualInstalledLayoutOverLegacyLayout() {
        val root = Files.createTempDirectory("both resource layouts ")
        try {
            val executable = root.resolve("subtitle-sync/nuvio-subtitle-sync.exe")
            val legacy = root.resolve("windows/subtitle-sync/nuvio-subtitle-sync.exe")
            for (path in listOf(executable, legacy)) {
                Files.createDirectories(path.parent)
                Files.createFile(path)
            }
            assertEquals(
                executable.toAbsolutePath().toString(),
                DesktopSubtitleSyncRuntime.command(Path.of("worker.py"), Path.of("job.json"), root.toString(), null).first(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun explicitOverrideIsAnExactDirectoryAndTakesPriority() {
        val root = Files.createTempDirectory("override resources ")
        try {
            val executable = root.resolve("private runtime/nuvio-subtitle-sync.exe")
            val packaged = root.resolve("subtitle-sync/nuvio-subtitle-sync.exe")
            for (path in listOf(executable, packaged)) {
                Files.createDirectories(path.parent)
                Files.createFile(path)
            }
            assertEquals(
                executable.toAbsolutePath().toString(),
                DesktopSubtitleSyncRuntime.command(
                    Path.of("worker.py"), Path.of("job.json"), root.toString(), executable.parent.toString(),
                ).first(),
            )
            assertFailsWith<IllegalStateException> {
                DesktopSubtitleSyncRuntime.command(
                    Path.of("worker.py"), Path.of("job.json"), root.toString(), root.resolve("missing override").toString(),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun incompleteInstalledAppDoesNotFallBackToSystemPython() {
        val root = Files.createTempDirectory("missing-subtitle-runtime")
        try {
            assertFailsWith<IllegalStateException> {
                DesktopSubtitleSyncRuntime.command(Path.of("worker.py"), Path.of("job.json"), root.toString(), null)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun developmentRetainsExistingPythonFallback() {
        val root = Files.createTempDirectory("nuvio-dev")
        try {
            assertEquals(
                listOf("py", "-3", "worker.py", "job.json"),
                DesktopSubtitleSyncRuntime.command(Path.of("worker.py"), Path.of("job.json"), null, null, root),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
