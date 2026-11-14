package com.example.otaapplication.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PhoneReleasesTest {
    private fun release(tag: String, apk: String? = "OTA-Business-${tag.substringAfter("-v")}.apk", draft: Boolean = false, pre: Boolean = false, digest: String = "") = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":"Notes for $tag","html_url":"https://x/$tag","assets":[
          ${if (apk != null) """{"name":"$apk","browser_download_url":"https://dl/$apk","size":123,"digest":"$digest"},""" else ""}
          {"name":"SHA256SUMS.txt","browser_download_url":"https://dl/sums","size":10}
        ]}
    """

    private val feed = "[" + listOf(
        release("desktop-v1.9.0", apk = null),
        release("android-v1.2.0", digest = "sha256:ABCDEF"),
        release("android-v1.10.0", draft = true),
        release("android-v1.3.0", pre = true),
        release("android-v1.1.5"),
        release("android-v1.4.0", apk = null)
    ).joinToString(",") + "]"

    @Test
    fun versionCodesMatchTheGradleFormula() {
        assertEquals(10100L, PhoneVersion.parse("1.1.0")!!.code)
        assertEquals(20305L, PhoneVersion.parse("v2.3.5")!!.code)
        assertEquals(10200L, PhoneVersion.parse("1.2")!!.code)
        assertNull(PhoneVersion.parse("1.100.0"))
        assertNull(PhoneVersion.parse("1.2.x"))
        assertNull(PhoneVersion.parse("1"))
    }

    @Test
    fun picksTheNewestPublishedPhoneReleaseWithAnApk() {
        val r = PhoneReleases.newer(feed, currentCode = 10100, prefix = PhoneReleases.LEGACY_PREFIX)
        assertNotNull(r)
        assertEquals("android-v1.2.0", r!!.tag)
        assertEquals("OTA-Business-1.2.0.apk", r.apk.name)
        assertEquals("abcdef", r.apk.sha256)
        assertEquals("https://dl/sums", r.checksumsUrl)
        assertEquals("Notes for android-v1.2.0", r.notes)
    }

    @Test
    fun neverOffersTheSameOrAnOlderVersion() {
        assertNull(PhoneReleases.newer(feed, currentCode = 10200, prefix = PhoneReleases.LEGACY_PREFIX))
        assertNull(PhoneReleases.newer(feed, currentCode = 20000, prefix = PhoneReleases.LEGACY_PREFIX))
        // Drafts, pre-releases, desktop tags and releases without an APK are ignored.
        assertEquals(listOf("android-v1.2.0", "android-v1.1.5"), PhoneReleases.parse(feed, PhoneReleases.LEGACY_PREFIX).map { it.tag })
    }

    @Test
    fun newAppOnlyFollowsItsOwnTags() {
        assertNull(PhoneReleases.newer(feed, currentCode = 0, prefix = PhoneReleases.PREFIX))
        assertEquals(setOf("a".repeat(64)), PhoneReleases.pins("AA:" + "a".repeat(62) + ", short,"))
    }

    @Test
    fun checksumsMustAgreeAndMatch() {
        val apk = ApkAsset("a.apk", "u", 3, "aa")
        assertEquals("aa", PhoneReleases.expectedSha(apk, "AA"))
        assertNull(PhoneReleases.expectedSha(apk, "bb"))
        assertNull(PhoneReleases.expectedSha(apk.copy(sha256 = null), null))
        assertEquals(mapOf("OTA-Business-1.2.0.apk" to "a".repeat(64)), PhoneReleases.parseChecksums("${"A".repeat(64)}  OTA-Business-1.2.0.apk\n"))

        val f = File.createTempFile("apk", ".apk").apply { writeText("abc"); deleteOnExit() }
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertNull(PhoneReleases.verify(f, 3, sha))
        assertNotNull(PhoneReleases.verify(f, 4, sha))
        assertNotNull(PhoneReleases.verify(f, 3, "0".repeat(64)))
        assertNotNull(PhoneReleases.verify(f, 3, null))
    }

    @Test
    fun releaseNotesShowWithoutMarkdownOrTheCertificateLine() {
        val notes = releaseNotesText(
            "## What's new\n- Offers **Update now** or **Later**.\n* Install `OTA-Business-1.1.0.apk` by hand.\n\n\nSigning certificate SHA-256: `23d8`\n"
        )
        assertEquals("What's new\n•  Offers Update now or Later.\n•  Install OTA-Business-1.1.0.apk by hand.", notes.text)
        assertEquals(4, notes.spanStyles.size)
    }
}
