package com.nuvio.app.features.player.desktop

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/** Keep the coroutine cancellable while a private worker/FFmpeg process runs. */
internal suspend fun awaitSubtitleSyncProcess(process: Process, timeoutMillis: Long = 90_000) {
    require(timeoutMillis > 0)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    try {
        while (process.isAlive) {
            currentCoroutineContext().ensureActive()
            check(System.nanoTime() < deadline) {
                "Subtitle sync timed out; try another stream or a downloaded copy; original subtitle kept"
            }
            delay(50)
        }
    } finally {
        if (process.isAlive) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
}
