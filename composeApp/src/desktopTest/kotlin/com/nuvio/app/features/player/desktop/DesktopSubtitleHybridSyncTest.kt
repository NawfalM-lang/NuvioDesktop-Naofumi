package com.nuvio.app.features.player.desktop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopSubtitleHybridSyncTest {
    @Test
    fun externalReferenceCannotBypassUnsupportedVideoHeaders(): Unit = runBlocking {
        for (reference in listOf(null, "https://provider/english?token=secret")) {
            val error = assertFailsWith<IllegalStateException> {
                DesktopSubtitleHybridSync.create(
                    "https://provider/video?token=secret",
                    "https://provider/arabic?token=secret",
                    mapOf("Authorization" to "secret"),
                    reference,
                )
            }
            assertTrue(error.message.orEmpty().contains("Cannot check embedded English"))
            assertTrue(error.message.orEmpty().contains("original subtitle kept"))
            assertTrue(!error.message.orEmpty().contains("secret"))
        }
    }
}
