package com.nuvio.app.features.streams

// NUVIO_STREAM_LIST_V9: release-metadata-only presentation ranking.

/**
 * Cosmetic source-list ranking. Never changes stream objects or playback IDs.
 * Arabic scoring is deliberately a *release-name hint*, not proof of an embedded track.
 * In particular, addon externalSubtitles are not embedded subtitles and are not counted.
 */
internal fun <T> List<T>.sortedForArabicSubtitlePreference(stream: (T) -> StreamItem): List<T> =
    sortedWith(
        compareByDescending<T> { stream(it).displayResolutionPixels() }
            .thenByDescending { stream(it).arabicSubtitleReleaseHint() },
    )

private val resolution2160 = Regex("""(?i)(?:^|[^a-z0-9])(?:2160p?|4k|uhd)(?=$|[^a-z0-9])""")
private val resolution1440 = Regex("""(?i)(?:^|[^a-z0-9])(?:1440p?|2k)(?=$|[^a-z0-9])""")
private val resolution1080 = Regex("""(?i)(?:^|[^a-z0-9])(?:1080p?|fhd)(?=$|[^a-z0-9])""")
private val resolution720 = Regex("""(?i)(?:^|[^a-z0-9])(?:720p?|hd)(?=$|[^a-z0-9])""")
private val resolution576 = Regex("""(?i)(?:^|[^a-z0-9])576p?(?=$|[^a-z0-9])""")
private val resolution480 = Regex("""(?i)(?:^|[^a-z0-9])(?:480p?|sd)(?=$|[^a-z0-9])""")

internal fun StreamItem.displayResolutionPixels(): Int {
    // Prefer structured parser data and supplied badge labels over descriptions.
    val structured = listOfNotNull(
        clientResolve?.stream?.raw?.parsed?.resolution,
    ).joinToString(" ")
    val fallback = listOfNotNull(
        behaviorHints.filename,
        clientResolve?.filename,
        clientResolve?.stream?.raw?.filename,
        debridCacheStatus?.cachedName,
        name, title, description,
    ).joinToString(" ")
    val badgeNames = badges.joinToString(" ") { it.name }
    for (sample in listOf(structured, badgeNames, fallback)) {
        if (resolution2160.containsMatchIn(sample)) return 2160
        if (resolution1440.containsMatchIn(sample)) return 1440
        if (resolution1080.containsMatchIn(sample)) return 1080
        if (resolution720.containsMatchIn(sample)) return 720
        if (resolution576.containsMatchIn(sample)) return 576
        if (resolution480.containsMatchIn(sample)) return 480
    }
    return 0
}

// Only contextually strong Arabic-subtitle indicators rank highest.
// Keep weaker tags separate, since "MULTiSUB" by itself does not guarantee Arabic.
private val explicitArabicSubtitles = Regex(
    """(?i)(?:\b(?:arabic|ara|ar)\b[\s._:·•\-/]*(?:subs?|subtitles?|cc)\b|\b(?:subs?|subtitles?|cc)\b[\s._:·•\-/]*(?:arabic|ara|ar)\b|(?:arabic|ara|ar)[\s._-]*subtitle)""",
)
private val standaloneArabic = Regex("""(?i)(?:^|[^a-z0-9])(?:arabic|ara)(?=$|[^a-z0-9])""")
private val multiSub = Regex("""(?i)(?:^|[^a-z0-9])(?:multi[\s._-]*subs?|multi[\s._-]*subtitles?)(?=$|[^a-z0-9])""")

internal fun StreamItem.arabicSubtitleReleaseHint(): Int {
    val text = listOfNotNull(
        name, title, description, behaviorHints.filename,
        debridCacheStatus?.cachedName,
        clientResolve?.torrentName, clientResolve?.filename,
        clientResolve?.stream?.raw?.torrentName,
        clientResolve?.stream?.raw?.filename,
    ).joinToString(" ")
    if (explicitArabicSubtitles.containsMatchIn(text) ||
        (text.contains("عربي") && (text.contains("ترجمة") || text.contains("سبتايتل")))) return 3
    if (standaloneArabic.containsMatchIn(text) || text.contains("العربية")) return 2
    if (multiSub.containsMatchIn(text)) return 1
    return 0
}
