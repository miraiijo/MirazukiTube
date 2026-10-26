package dev.miraiijo.miryotube.link

import dev.miraiijo.miryotube.MainActivity
import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The miryo-link contract (link-contract.properties, same file in the three apps) against this app's
 * constants and manifest: a name changed on one side only breaks the link silently on the box.
 */
class LinkContractTest {
    private val contract = Properties().apply {
        javaClass.classLoader!!.getResourceAsStream("link-contract.properties").use { load(it) }
    }
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun c(key: String): String = contract.getProperty(key) ?: error("$key absent du contrat")

    @Test
    fun constantsMatchTheContract() {
        assertEquals(c("install.miryo"), MiryoLink.MIRYO)
        assertEquals(c("install.miryotube"), MiryoLink.MIRYOTUBE)
        assertEquals(c("install.miryoiptv"), MiryoLink.MIRYOIPTV)
        assertEquals(c("authority.profiles"), MiryoLink.PROFILES_AUTHORITY)
        assertEquals(c("authority.tube"), MiryoLink.TUBE_AUTHORITY)
        assertEquals(c("authority.iptv"), MiryoLink.IPTV_AUTHORITY)
        assertEquals(c("scheme.iptv"), MiryoLink.IPTV_SCHEME)
        assertEquals(c("extra.profile"), MiryoLink.EXTRA_PROFILE)
        assertEquals(c("extra.account"), MainActivity.EXTRA_ACCOUNT_ID)
        assertEquals(c("extra.skipPicker"), MainActivity.EXTRA_SKIP_PICKER)
    }

    @Test
    fun manifestMatchesTheContract() {
        assertTrue("fournisseur des rangées YouTube", manifest.contains("android:authorities=\"${c("authority.tube")}\""))
        // Package visibility of miryo (Android 11+): without it its profiles look absent.
        assertTrue("visibilité de miryo", manifest.contains("android:authorities=\"${c("authority.profiles")}\""))
    }
}
