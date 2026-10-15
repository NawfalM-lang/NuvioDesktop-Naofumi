package com.nuvio.app.features.player

/**
 * Conservatively prefer subtitles whose release label matches the selected video.
 * No filename or no usable release metadata leaves the original order untouched.
 * This does not resynchronize incorrectly timed subtitle files.
 */
internal fun List<AddonSubtitle>.rankedForRelease(videoFilename: String?): List<AddonSubtitle> {
    val filename = videoFilename?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    val stem = filename.substringBeforeLast('.', filename).lowercase()
    val videoTokens = subtitleReleaseTokens(stem)
    val group = Regex("-([a-z0-9]{3,})$").find(stem)?.groupValues?.getOrNull(1)
    val tags = videoTokens.intersect(releaseRankingTags)
    if (group == null && tags.isEmpty()) return this

    return withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<AddonSubtitle>> { indexed ->
                subtitleReleaseMatchScore(indexed.value.display, group, tags)
            }.thenBy { it.index },
        ).map { it.value }
}

private fun subtitleReleaseTokens(value: String): Set<String> =
    Regex("[a-z0-9]+")
        .findAll(value.lowercase())
        .map { it.value }
        .toSet()

private fun subtitleReleaseMatchScore(
    subtitleDisplay: String,
    releaseGroup: String?,
    videoTags: Set<String>,
): Int {
    val subtitleTokens = subtitleReleaseTokens(subtitleDisplay)
    var score = if (releaseGroup != null && releaseGroup in subtitleTokens) 120 else 0
    videoTags.forEach { tag ->
        if (tag in subtitleTokens) {
            score += when (tag) {
                "stan", "nf", "amzn", "dsnp", "hmax", "atvp" -> 30
                "2160p", "1080p", "720p", "480p" -> 8
                "web", "dl", "webrip", "webdl", "bluray", "bdrip", "hdtv" -> 7
                else -> 3
            }
        }
    }
    return score
}

private val releaseRankingTags = setOf(
    "stan", "nf", "amzn", "dsnp", "hmax", "atvp",
    "web", "dl", "webrip", "webdl", "bluray", "bdrip", "hdtv",
    "2160p", "1080p", "720p", "480p",
    "h265", "x265", "hevc", "h264", "x264", "hdr", "dv", "ddp5", "atmos",
)

// NUVIO_SUBTITLE_AUTO_RELEASE_V3
/** Confidence signal for automatic replacement, not a timing/sync guarantee. */
internal fun AddonSubtitle.releaseMatchScore(videoFilename: String?): Int {
    val filename = videoFilename?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
    val stem = filename.substringBeforeLast('.', filename).lowercase()
    val videoTokens = Regex("[a-z0-9]+").findAll(stem).map { it.value }.toSet()
    val releaseGroup = Regex("-([a-z0-9]{3,})$").find(stem)?.groupValues?.getOrNull(1)
    val tags = videoTokens.intersect(releaseRankingTags)
    if (releaseGroup == null && tags.isEmpty()) return 0
    return subtitleReleaseMatchScore(display, releaseGroup, tags)
}

/**
 * Prefer an exact uploader/release-group match, or several independent stream
 * quality/source tags. Do not auto-switch if the evidence is too weak.
 */
internal fun AddonSubtitle.isConfidentReleaseMatch(videoFilename: String?): Boolean =
    releaseMatchScore(videoFilename) >= 50
