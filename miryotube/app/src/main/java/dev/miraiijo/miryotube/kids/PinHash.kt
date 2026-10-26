package dev.miraiijo.miryotube.kids

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parental code (4 digits) is never stored: only PBKDF2-HMAC-SHA256 of it, with a random
 * 16-byte salt, as « pbkdf2-sha256$<iterations>$<salt>$<hash> » (Base64). Copied from miryo's
 * (same format as miryo iptv's): fix it in the three. 20 000 iterations: tens of ms on the player's
 * weak ARM cores (always run off the main thread), while a copy of the prefs alone no longer shows
 * the code. Only 10 000 codes exist, so the real protection stays the persistent try limiter.
 * Comparison in constant time. Pure JVM.
 */
object PinHash {
    const val LENGTH = 4
    const val ITERATIONS = 20_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val SCHEME = "pbkdf2-sha256"
    private val random = SecureRandom()

    fun isValid(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }

    fun hash(pin: String, salt: ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes), iterations: Int = ITERATIONS): String {
        require(isValid(pin)) { "Code invalide" }
        val b64 = Base64.getEncoder()
        return listOf(SCHEME, iterations.toString(), b64.encodeToString(salt), b64.encodeToString(derive(pin, salt, iterations))).joinToString("$")
    }

    /** True when [pin] gives [stored]; false for anything malformed. */
    fun verify(pin: String, stored: String): Boolean {
        if (!isValid(pin)) return false
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != SCHEME) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1_000..1_000_000 } ?: return false
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull()?.takeIf { it.size >= 8 } ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull()?.takeIf { it.size == KEY_BITS / 8 } ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    /** Constant-time comparison of two plain codes (legacy value being migrated). */
    fun equalsPlain(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}
