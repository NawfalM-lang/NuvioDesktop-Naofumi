package com.nuvio.app.features.player.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/** Fast English-reference subtitle synchronization for Windows. */
internal object DesktopSubtitleHybridSync {
    // NUVIO_HYBRID_SYNC_V41_DIAG. NEVER log signed URLs or private headers.
    private fun logStage(label: String) {
        runCatching {
            Files.writeString(
                Path.of(System.getProperty("java.io.tmpdir"), "nuvio-hybrid-sync.log"),
                "$label\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }
    suspend fun create(
        videoUrl: String,
        subtitleUrl: String,
        videoHeaders: Map<String, String>,
        referenceSubtitleUrl: String?, // NUVIO_FAST_SUBSYNC_V5
    ): String = withContext(Dispatchers.IO) {
        logStage("V4.1 entered Windows sync helper; customHeaders=${videoHeaders.size}")
        // The initial version accepts signed/direct URLs; ffsubsync does not
        // currently accept arbitrary Nuvio HTTP request headers for video streams.
        // English add-on reference requires NO video access, even on secured
        // streams with extra headers. Embedded fallback still needs direct video.
        if (videoHeaders.isNotEmpty() && referenceSubtitleUrl == null) {
            logStage("V5 no English addon reference; media has custom headers")
            throw IllegalStateException("Stream needs custom headers. Enable an English subtitle addon for fast synchronization")
        }
        val work = Files.createTempDirectory("nuvio-hybrid-sync-")
        val jobFile = work.resolve("job.json")
        val workerFile = work.resolve("audio_sync_worker.py")
        val logFile = work.resolve("output.log")
        val output = work.resolve("synced.srt")
        logStage("V4.1 staging synchronization worker")
        val resource = DesktopSubtitleHybridSync::class.java.classLoader
            .getResourceAsStream("nuvio/audio_sync_worker.py")
            ?: throw IllegalStateException("Hybrid sync resource not packaged; rebuild the desktop app")
        resource.use { source -> Files.newOutputStream(workerFile).use { destination -> source.copyTo(destination) } }
        val safeJob = JsonObject(mapOf(
            "video_url" to JsonPrimitive(videoUrl),
            "subtitle_url" to JsonPrimitive(subtitleUrl),
            "english_reference_url" to JsonPrimitive(referenceSubtitleUrl.orEmpty()),
            "workspace" to JsonPrimitive(work.toString()),
        ))
        Files.writeString(jobFile, safeJob.toString(), StandardCharsets.UTF_8)
        jobFile.toFile().deleteOnExit()
        workerFile.toFile().deleteOnExit()
        logFile.toFile().deleteOnExit()
        work.toFile().deleteOnExit()
        output.toFile().deleteOnExit()
        try {
            val command = DesktopSubtitleSyncRuntime.command(workerFile, jobFile)
            logStage(if (command.first() == "py") "Launching development Python" else "Launching bundled subtitle runtime")
            val process = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start()
            } catch (error: java.io.IOException) {
                throw IllegalStateException(
                    if (command.first() == "py") "Development subtitle sync needs Python 3 and ffsubsync, or buildWindowsSubtitleSync."
                    else "Bundled subtitle runtime could not start. Reinstall this Nuvio build.",
                    error,
                )
            }
            val finished = process.waitFor(55, TimeUnit.SECONDS)
            if (!finished) {
                logStage("V5 timed out after 55 seconds")
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                throw IllegalStateException("Fast subtitle sync timed out. Supply an English addon subtitle reference")
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) < 20L) {
                val diagnostic = runCatching { Files.readString(logFile).takeLast(600) }.getOrDefault("")
                val failure = diagnostic.lineSequence()
                    .lastOrNull { it.startsWith("SYNC_ERROR:") }
                    ?.removePrefix("SYNC_ERROR:")?.trim()
                // Do not put full stderr in UI: ffmpeg logs can contain signed stream URLs.
                logStage("V4.1 Python failed: " + (failure ?: "unknown; verify ffmpeg/ffprobe/Python")
                    .substringBefore("http").take(200))
                throw IllegalStateException(failure ?: "Hybrid sync failed; check Python/ffmpeg installation or stream access")
            }
            logStage("V4.1 output ready")
            return@withContext output.toUri().toString()
        } finally {
            // The job file contains signed URLs. Remove it immediately.
            Files.deleteIfExists(jobFile)
        }
    }
}
