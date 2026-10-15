package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleRequestExtraTest {
    @Test
    fun filenameAndSizeAreEncodedForAddonRequests() {
        assertEquals(
            "filename=Movie.2025.WEB-DL%20%5BGROUP%5D.mkv&videoSize=1048576",
            subtitleExtraPathSegment("Movie.2025.WEB-DL [GROUP].mkv", 1048576),
        )
    }

    @Test
    fun missingHintsLeaveLegacySubtitleRequestUnchanged() {
        assertNull(subtitleExtraPathSegment(null, null))
    }

    @Test
    fun unsafeCharactersInFilenameAreEscaped() {
        assertEquals("filename=Movie%20A%26B.mkv", subtitleExtraPathSegment("Movie A&B.mkv", null))
    }
}
