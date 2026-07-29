package com.aifieldcam.app.ui.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbMemoizerTest {

    @Test
    fun secondLoadHitsCacheWithoutReloading() {
        val memo = ThumbMemoizer<String>(maxEntries = 8)
        val first = memo.getOrLoad("a.jpg") { "decoded-a" }
        val second = memo.getOrLoad("a.jpg") { error("must not reload") }
        assertEquals("decoded-a", first)
        assertEquals("decoded-a", second)
        assertEquals(1, memo.loadCount)
    }

    @Test
    fun evictsOldestWhenOverCapacity() {
        val memo = ThumbMemoizer<Int>(maxEntries = 2)
        memo.getOrLoad("1") { 1 }
        memo.getOrLoad("2") { 2 }
        memo.getOrLoad("3") { 3 }
        assertEquals(2, memo.size())
        assertNull(memo.get("1"))
        assertEquals(2, memo.get("2"))
        assertEquals(3, memo.get("3"))
        assertEquals(3, memo.loadCount)
    }

    @Test
    fun nullLoaderResultIsNotCached() {
        val memo = ThumbMemoizer<String>(maxEntries = 4)
        assertNull(memo.getOrLoad("missing") { null })
        assertNull(memo.getOrLoad("missing") { null })
        assertTrue(memo.loadCount >= 2)
        assertEquals(0, memo.size())
    }
}
