package dev.miraiijo.lumentube.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIndexTest {
    @Test fun everyEntryHasAPage() {
        SettingsIndex.ENTRIES.forEach { assertTrue(it.label, it.page in SettingsIndex.PAGES) }
    }

    @Test fun findsByWordWithoutAccents() {
        assertEquals("Recevoir les versions bêta", SettingsIndex.search("beta").first().label)
        assertEquals("Passer les sponsors (SponsorBlock)", SettingsIndex.search("pub").first().label)
        assertEquals("PHONE", SettingsIndex.search("cast").first().page)
    }

    @Test fun nameMatchesComeFirst() {
        assertEquals("Volume stable", SettingsIndex.search("volume").first().label)
    }

    @Test fun hiddenPagesAreLeftOut() {
        assertTrue(SettingsIndex.search("enfant", SettingsIndex.PAGES.keys - "KIDS").isEmpty())
    }

    @Test fun oneLetterFindsNothing() {
        assertTrue(SettingsIndex.search("a").isEmpty())
    }
}
