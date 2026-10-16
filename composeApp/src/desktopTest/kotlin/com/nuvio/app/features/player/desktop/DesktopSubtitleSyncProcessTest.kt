package com.nuvio.app.features.player.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DesktopSubtitleSyncProcessTest {
    private fun javaExecutable(): String {
        val bin = Path.of(System.getProperty("java.home"), "bin")
        return bin.resolve(if (Files.isRegularFile(bin.resolve("java.exe"))) "java.exe" else "java").toString()
    }

    @Test
    fun normalExitCompletes(): Unit = runBlocking {
        val process = ProcessBuilder(javaExecutable(), "-version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        awaitSubtitleSyncProcess(process, 10_000)
        assertEquals(0, process.exitValue())
    }

    @Test
    fun timeoutStopsWorker(): Unit = runBlocking {
        val root = Files.createTempDirectory("subtitle process test ")
        try {
            val source = root.resolve("Sleeper.java")
            Files.writeString(source, "class Sleeper { public static void main(String[] args) throws Exception { Thread.sleep(30000); } }")
            val process = ProcessBuilder(javaExecutable(), source.toString()).start()
            assertFailsWith<IllegalStateException> { awaitSubtitleSyncProcess(process, 10) }
            assertFalse(process.isAlive)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancellationStopsWorker(): Unit = runBlocking {
        val root = Files.createTempDirectory("subtitle cancellation test ")
        try {
            val source = root.resolve("Sleeper.java")
            Files.writeString(source, "class Sleeper { public static void main(String[] args) throws Exception { Thread.sleep(30000); } }")
            val process = ProcessBuilder(javaExecutable(), source.toString()).start()
            val job = launch { awaitSubtitleSyncProcess(process) }
            delay(100)
            job.cancelAndJoin()
            assertFalse(process.isAlive)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
