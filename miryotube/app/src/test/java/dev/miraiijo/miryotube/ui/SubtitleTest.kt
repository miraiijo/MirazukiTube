package dev.miraiijo.miryotube.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTest {
    @Test fun translationsAreToldApart() {
        assertTrue(isTranslatedSubtitle("Français*"))
        assertFalse(isTranslatedSubtitle("Anglais (générés automatiquement)*"))
        assertFalse(isTranslatedSubtitle("Français"))
        assertEquals("Français · traduit automatiquement", subtitleLabel("Français*"))
        assertEquals("Anglais (générés automatiquement)", subtitleLabel("Anglais (générés automatiquement)*"))
    }

    @Test fun frenchForAVideoInAnotherLanguage() {
        // Its own French subtitles first, else the translation.
        assertEquals(1, frenchSubtitle(listOf("Anglais", "Français", "Français*")))
        assertEquals(2, frenchSubtitle(listOf("Anglais", "Anglais (générés automatiquement)*", "Français*")))
        // Spoken in French (its automatic captions are): nothing to translate.
        assertEquals(-1, frenchSubtitle(listOf("Français (générés automatiquement)*", "Anglais*")))
        assertEquals(-1, frenchSubtitle(listOf("Anglais")))
    }

    @Test fun miryoLanguage() {
        val tracks = listOf("Allemand", "Anglais (générés automatiquement)*", "Français*", "English")
        assertEquals(3, languageSubtitle(tracks, "en"))
        assertEquals(1, languageSubtitle(tracks.dropLast(1), "en"))
        assertEquals(2, languageSubtitle(tracks, "fr"))
        assertEquals(0, languageSubtitle(tracks, "de"))
        assertEquals(-1, languageSubtitle(tracks, "es"))
        assertEquals(-1, languageSubtitle(tracks, "zz"))
    }
}
