package com.example.otaapplication.update

import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/**
 * A phone app version like 1.2.3. Its Android versionCode is major*10000 + minor*100 + patch, the
 * same formula app/build.gradle.kts uses, so a release tag alone says whether it is newer.
 */
data class PhoneVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<PhoneVersion> {
    val code: Long get() = major * 10_000L + minor * 100L + patch
    override fun compareTo(other: PhoneVersion) = code.compareTo(other.code)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(text: String?): PhoneVersion? {
            val parts = text?.trim()?.removePrefix("v")?.split('.') ?: return null
            if (parts.size !in 2..3) return null
            val n = parts.map { it.toIntOrNull() ?: return null }
            val v = PhoneVersion(n[0], n[1], n.getOrElse(2) { 0 })
            return v.takeIf { v.major in 1..9999 && v.minor in 0..99 && v.patch in 0..99 }
        }
    }
}

data class ApkAsset(val name: String, val url: String, val size: Long, val sha256: String?)

data class PhoneRelease(
    val version: PhoneVersion,
    val tag: String,
    val notes: String,
    val page: String,
    val apk: ApkAsset,
    val checksumsUrl: String?
)

/** Phone releases published on GitHub (tags `android-v1.1.0`, …); the repository is public. */
object PhoneReleases {
    const val FEED = "https://api.github.com/repos/Shubh-Pandiya/OTA/releases?per_page=30"
    const val TAG_PREFIX = "android-v"
    const val CHECKSUMS = "SHA256SUMS.txt"

    /** Published (not draft, not pre-release) phone releases that carry an APK. */
    fun parse(json: String): List<PhoneRelease> {
        val list = JSONArray(json)
        return (0 until list.length()).mapNotNull { i ->
            val r = list.getJSONObject(i)
            val tag = r.optString("tag_name")
            if (!tag.startsWith(TAG_PREFIX) || r.optBoolean("draft") || r.optBoolean("prerelease")) return@mapNotNull null
            val version = PhoneVersion.parse(tag.removePrefix(TAG_PREFIX)) ?: return@mapNotNull null
            val array = r.optJSONArray("assets") ?: JSONArray()
            val assets = (0 until array.length()).map { array.getJSONObject(it) }
            val apk = assets.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }?.let { a ->
                ApkAsset(
                    a.optString("name"), a.optString("browser_download_url"), a.optLong("size", -1),
                    a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                )
            } ?: return@mapNotNull null
            val sums = assets.firstOrNull { it.optString("name") == CHECKSUMS }?.optString("browser_download_url")
            PhoneRelease(version, tag, r.optString("body").trim(), r.optString("html_url"), apk, sums)
        }
    }

    /** The newest release above [currentCode]; never an older or equal one, so there are no downgrades. */
    fun newer(json: String, currentCode: Long): PhoneRelease? =
        parse(json).filter { it.version.code > currentCode }.maxByOrNull { it.version }

    /** `sha256sum` output: "<hex>  <name>" per line. */
    fun parseChecksums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val m = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").find(line.trim()) ?: return@mapNotNull null
        m.groupValues[2].trim() to m.groupValues[1].lowercase()
    }.toMap()

    /**
     * The checksum to verify [apk] against: SHA256SUMS.txt and GitHub's own digest must agree when
     * both exist. Null when the two disagree.
     */
    fun expectedSha(apk: ApkAsset, published: String?): String? {
        val known = listOfNotNull(published?.lowercase(), apk.sha256).distinct()
        return known.singleOrNull()
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Null when [file] has the expected size and checksum, else why not. */
    fun verify(file: File, size: Long, sha256: String?): String? {
        if (size > 0 && file.length() != size) return "Downloaded ${file.length()} bytes, expected $size"
        if (sha256 == null) return "The release has no checksum to check the download against"
        if (!sha256(file).equals(sha256, ignoreCase = true)) return "The download does not match the published checksum"
        return null
    }
}
