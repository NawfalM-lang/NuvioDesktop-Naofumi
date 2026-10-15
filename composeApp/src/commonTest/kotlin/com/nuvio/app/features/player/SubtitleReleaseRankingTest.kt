package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleReleaseRankingTest {
    private fun subtitle(id: String, description: String) = AddonSubtitle(
        id = id,
        url = "https://example.invalid/$id.srt",
        language = "ar",
        display = description,
        addonName = "test",
    )

    @Test
    fun selectsMatchingReleaseGroupFirst() {
        val generic = subtitle("a", "Arabic - unknown release")
        val wrong = subtitle("b", "Arabic · From.S01E01.1080p.WEB-DL-OTHER")
        val matched = subtitle("c", "Arabic · From.S01E01.2160p.STAN.WEB-DL-Kitsune")
        val original = listOf(generic, wrong, matched)
        assertEquals(
            matched,
            original.rankedForRelease("From.S01E01.2160p.STAN.WEB-DL-Kitsune.mkv").first(),
        )
    }

    @Test
    fun leavesOrderUntouchedWithoutFileMetadata() {
        val first = subtitle("a", "Arabic - first")
        val second = subtitle("b", "Arabic - second")
        val original = listOf(first, second)
        assertEquals(original, original.rankedForRelease(null))
    }

    @Test
    fun prefersMatchingReleaseTagsWhenNoGroupAvailable() {
        val unknown = subtitle("a", "Arabic - general")
        val matched = subtitle("b", "Arabic · STAN 2160p WEB-DL")
        val original = listOf(unknown, matched)
        assertEquals(matched, original.rankedForRelease("From S01E01 2160p STAN WEB-DL.mkv").first())
    }
}
