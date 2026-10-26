package dev.miraiijo.lumentube.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MirazukiLookTest {
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 26, hour, minute)

    @Test
    fun dayAndNightIgnoreTheHour() {
        val day = MirazukiLook.Look("day", 7 * 60, 20 * 60)
        val night = MirazukiLook.Look("night", 7 * 60, 20 * 60)
        assertTrue(day.isLight(at(23)))
        assertFalse(night.isLight(at(12)))
    }

    @Test
    fun autoFollowsMiryosDayBounds() {
        // Sunrise 8:12, sunset 18:05 in miryo's city.
        val auto = MirazukiLook.Look("auto", 8 * 60 + 12, 18 * 60 + 5)
        assertFalse(auto.isLight(at(8, 11)))
        assertTrue(auto.isLight(at(8, 12)))
        assertTrue(auto.isLight(at(18, 4)))
        assertFalse(auto.isLight(at(18, 5)))
    }
}
