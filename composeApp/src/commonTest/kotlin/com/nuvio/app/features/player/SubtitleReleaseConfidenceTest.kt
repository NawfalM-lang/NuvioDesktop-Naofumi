package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleReleaseConfidenceTest {
    private fun sub(label: String) = AddonSubtitle(
        id = label,
        url = "https://example.invalid/subtitle.srt",
        language = "ar",
        display = label,
        addonName = "test",
    )

    private val video = "From S01E01 Long Days Journey Into Night 2160p STAN WEB-DL DDP5 1 H 265-Kitsune.mkv"

    @Test
    fun exactReleaseGroupIsConfident() {
        val same = sub("FROM.S01E01.2160p.STAN.WEB-DL-Kitsune")
        assertTrue(same.isConfidentReleaseMatch(video))
        assertTrue(same.releaseMatchScore(video) >= 120)
    }

    @Test
    fun genericArabicSubtitleIsNotConfident() {
        val generic = sub("Arabic (OpenSubtitles)")
        assertFalse(generic.isConfidentReleaseMatch(video))
    }

    @Test
    fun missingFilenameCannotChangeRanking() {
        val exact = sub("FROM.S01E01.2160p.STAN.WEB-DL-Kitsune")
        assertEquals(0, exact.releaseMatchScore(null))
        assertFalse(exact.isConfidentReleaseMatch(null))
    }

    @Test
    fun sourceAndResolutionCanEstablishConfidence() {
        val close = sub("FROM S01E01 2160p STAN WEB-DL")
        assertTrue(close.isConfidentReleaseMatch(video))
    }
}
