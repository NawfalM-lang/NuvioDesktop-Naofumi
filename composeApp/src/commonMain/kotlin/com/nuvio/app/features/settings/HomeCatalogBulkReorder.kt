package com.nuvio.app.features.settings

// NUVIO_CATALOG_BULK_REORDER_V7
// Pure algorithms: preserve the relative order of selected items and never move pinned entries.
internal fun reorderHomeCatalogKeys(
    keys: List<String>,
    fromIndex: Int,
    toIndex: Int,
    selectedKeys: Set<String>,
    pinnedKeys: Set<String>,
): List<String> {
    if (fromIndex !in keys.indices || toIndex !in keys.indices || fromIndex == toIndex) return keys
    val source = keys[fromIndex]
    val target = keys[toIndex]
    if (source in pinnedKeys || target in pinnedKeys) return keys
    val moving = if (source in selectedKeys) keys.filter { it in selectedKeys && it !in pinnedKeys } else listOf(source)
    if (target in moving) return keys
    val rest = keys.filterNot { it in moving }.toMutableList()
    val targetIndex = rest.indexOf(target)
    if (targetIndex < 0) return keys
    rest.addAll(if (fromIndex < toIndex) targetIndex + 1 else targetIndex, moving)
    return rest
}

internal fun shiftSelectedHomeCatalogKeys(
    keys: List<String>,
    selectedKeys: Set<String>,
    direction: Int,
    pinnedKeys: Set<String>,
): List<String> {
    if ((direction != -1 && direction != 1) || keys.size < 2) return keys
    val ordered = keys.toMutableList()
    val range = if (direction < 0) 1 until ordered.size else (ordered.size - 2) downTo 0
    for (i in range) {
        val next = i + direction
        if (ordered[i] in selectedKeys && ordered[i] !in pinnedKeys &&
            ordered[next] !in selectedKeys && ordered[next] !in pinnedKeys
        ) {
            val temp = ordered[i]
            ordered[i] = ordered[next]
            ordered[next] = temp
        }
    }
    return ordered
}

internal fun moveSelectedHomeCatalogKeysToEdge(
    keys: List<String>,
    selectedKeys: Set<String>,
    toTop: Boolean,
    pinnedKeys: Set<String>,
): List<String> {
    val movableSelected = keys.filter { it in selectedKeys && it !in pinnedKeys }
    if (movableSelected.isEmpty()) return keys
    val rest = keys.filterNot { it in movableSelected }
    if (!toTop) return rest + movableSelected
    val pinnedPrefix = rest.takeWhile { it in pinnedKeys }
    return pinnedPrefix + movableSelected + rest.drop(pinnedPrefix.size)
}
