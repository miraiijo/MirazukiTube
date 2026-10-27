package dev.miraiijo.miryotube.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsFilterTest {
    private val min = 60_000L

    @Test
    fun noFilterKeepsEverything() {
        val f = SubsFilter()
        assertFalse(f.active)
        assertTrue(f.keeps(null, 0, 100, live = true, folderChannels = null))
    }

    @Test
    fun newOnlyHidesWatchedVideos() {
        val f = SubsFilter(newOnly = true)
        assertFalse(f.keeps("a", 5 * min, 100, live = false, folderChannels = null))
        assertTrue(f.keeps("a", 5 * min, 60, live = false, folderChannels = null))
        assertTrue(f.keeps("a", 5 * min, 0, live = false, folderChannels = null))
    }

    @Test
    fun durations() {
        assertTrue(SubsFilter.durationKeeps(SubsFilter.DURATION_SHORT, 9 * min, live = false))
        assertFalse(SubsFilter.durationKeeps(SubsFilter.DURATION_SHORT, 12 * min, live = false))
        assertFalse(SubsFilter.durationKeeps(SubsFilter.DURATION_SHORT, 0, live = false))
        assertTrue(SubsFilter.durationKeeps(SubsFilter.DURATION_MEDIUM, 15 * min, live = false))
        assertTrue(SubsFilter.durationKeeps(SubsFilter.DURATION_LONG, 48 * min, live = false))
        assertFalse(SubsFilter.durationKeeps(SubsFilter.DURATION_LONG, 48 * min, live = true))
        assertTrue(SubsFilter.durationKeeps(SubsFilter.DURATION_ALL, 0, live = true))
    }

    @Test
    fun folderAndChannel() {
        val folder = SubsFilter(folder = "Cuisine")
        assertTrue(folder.keeps("a", min, 0, live = false, folderChannels = setOf("a", "b")))
        assertFalse(folder.keeps("c", min, 0, live = false, folderChannels = setOf("a", "b")))
        assertFalse(folder.keeps(null, min, 0, live = false, folderChannels = setOf("a")))
        val one = SubsFilter(channel = "b")
        assertTrue(one.keeps("b", min, 0, live = false, folderChannels = null))
        assertFalse(one.keeps("a", min, 0, live = false, folderChannels = null))
    }

    @Test
    fun newCountSinceLastVisit() {
        assertEquals(2, SubsFilter.newCount(listOf(10L, 20L, 30L, null), lastVisit = 15L))
        assertEquals(0, SubsFilter.newCount(emptyList(), lastVisit = 0L))
    }
}
