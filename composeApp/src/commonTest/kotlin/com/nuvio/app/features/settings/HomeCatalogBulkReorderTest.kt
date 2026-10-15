package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogBulkReorderTest {
    private val keys = listOf("PIN", "A", "B", "C", "D", "E")
    private val pinned = setOf("PIN")

    @Test fun singleDragMovesOnlyOne() {
        assertEquals(listOf("PIN", "A", "C", "D", "B", "E"),
            reorderHomeCatalogKeys(keys, 2, 4, emptySet(), pinned))
    }

    @Test fun selectedItemsDragAsAStableBlock() {
        assertEquals(listOf("PIN", "A", "C", "E", "B", "D"),
            reorderHomeCatalogKeys(keys, 2, 5, setOf("B", "D"), pinned))
    }

    @Test fun cannotMovePinned() {
        assertEquals(keys, reorderHomeCatalogKeys(keys, 0, 3, emptySet(), pinned))
        assertEquals(keys, reorderHomeCatalogKeys(keys, 2, 0, emptySet(), pinned))
    }

    @Test fun selectedDownAndUpPreserveOrder() {
        assertEquals(listOf("PIN", "A", "C", "B", "E", "D"),
            shiftSelectedHomeCatalogKeys(keys, setOf("B", "D"), 1, pinned))
        assertEquals(listOf("PIN", "B", "A", "D", "C", "E"),
            shiftSelectedHomeCatalogKeys(keys, setOf("B", "D"), -1, pinned))
    }

    @Test fun moveSelectedToTopOrBottomKeepsPinnedFirst() {
        assertEquals(listOf("PIN", "B", "D", "A", "C", "E"),
            moveSelectedHomeCatalogKeysToEdge(keys, setOf("B", "D", "PIN"), true, pinned))
        assertEquals(listOf("PIN", "A", "C", "E", "B", "D"),
            moveSelectedHomeCatalogKeysToEdge(keys, setOf("B", "D", "PIN"), false, pinned))
    }
}
