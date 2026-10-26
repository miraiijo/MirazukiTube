package dev.miraiijo.miryotube.kids

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {
    @Test
    fun theRightCodeIsAccepted() {
        val stored = PinHash.hash("1234", iterations = 1_000)
        assertTrue(PinHash.verify("1234", stored))
    }

    @Test
    fun aWrongCodeIsRefused() {
        val stored = PinHash.hash("1234", iterations = 1_000)
        assertFalse(PinHash.verify("1235", stored))
        assertFalse(PinHash.verify("123", stored))
        assertFalse(PinHash.verify("abcd", stored))
    }

    @Test
    fun theCodeItselfIsNeverStored() {
        val stored = PinHash.hash("1234", iterations = 1_000)
        assertFalse(stored.contains("1234"))
        // A random salt: the same code never gives the same value twice.
        assertNotEquals(stored, PinHash.hash("1234", iterations = 1_000))
    }

    @Test
    fun malformedValuesAreRefused() {
        assertFalse(PinHash.verify("1234", ""))
        assertFalse(PinHash.verify("1234", "1234"))
        assertFalse(PinHash.verify("1234", "pbkdf2-sha256\$10\$AAAA\$AAAA"))
        assertFalse(PinHash.verify("1234", "md5\$1000\$AAAAAAAAAAA=\$AAAA"))
    }

    @Test
    fun onlyFourDigitsAreValid() {
        assertTrue(PinHash.isValid("0000"))
        assertFalse(PinHash.isValid("00000"))
        assertFalse(PinHash.isValid("12a4"))
        assertFalse(PinHash.isValid(""))
    }
}
