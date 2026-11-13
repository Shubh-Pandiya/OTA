package com.ota.desktop.update

import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** A dotted version like 1.3.0, compared number by number (1.10.0 > 1.9.2). */
class AppVersion private constructor(val parts: List<Int>, val text: String) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val d = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    override fun equals(other: Any?) = other is AppVersion && compareTo(other) == 0
    override fun hashCode() = parts.dropLastWhile { it == 0 }.hashCode()
    override fun toString() = text

    companion object {
        fun parse(text: String?): AppVersion? {
            val t = text?.trim()?.removePrefix("v") ?: return null
            val parts = t.split('.').map { it.toIntOrNull() ?: return null }
            return if (parts.isEmpty() || parts.size > 4 || parts.any { it < 0 }) null else AppVersion(parts, t)
        }
    }
}

/** Which installer this computer takes from a release: the MSI on Windows, the DMG for this Mac's CPU. */
data class Platform(val ext: String, val arch: String? = null) {
    companion object {
        val WINDOWS = Platform(".msi")
        val MAC_ARM = Platform(".dmg", "arm64")
        val MAC_INTEL = Platform(".dmg", "x64")
        private val ALL_ARCHES = listOf("arm64", "aarch64", "x64", "x86_64", "intel")

        val current: Platform? by lazy {
            val os = System.getProperty("os.name").lowercase()
            val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it.startsWith("arm") }
            when {
                os.contains("win") -> WINDOWS
                os.contains("mac") -> if (arm) MAC_ARM else MAC_INTEL
                else -> null
            }
        }
    }

    /** DMGs are named per CPU (…-mac-arm64.dmg); one with no CPU in its name is taken only when nothing matches. */
    fun pick(names: List<String>): String? {
        val mine = names.filter { it.endsWith(ext, ignoreCase = true) }
        if (arch == null) return mine.firstOrNull()
        val tagged = mine.filter { n -> ALL_ARCHES.any { n.contains(it, ignoreCase = true) } }
        return tagged.firstOrNull { it.contains(arch, ignoreCase = true) } ?: (mine - tagged.toSet()).firstOrNull()
    }
}

/** One downloadable installer of a release, with what we know to check it against. */
data class Installer(val name: String, val url: String, val size: Long, val sha256: String?)

data class Release(val version: AppVersion, val tag: String, val notes: String, val page: String, val installer: Installer?, val checksumsUrl: String?)

/**
 * Desktop releases published on GitHub (tags `desktop-v1.3.0`, …). The repository is public, so the
 * list is read without any token. `-Dota.updateFeed=` / `OTA_UPDATE_FEED` point at another feed in
 * the same format (tests and the CI update run).
 */
class ReleaseFeed(
    private val url: String = System.getProperty("ota.updateFeed") ?: System.getenv("OTA_UPDATE_FEED") ?: DEFAULT,
    private val http: HttpClient = client,
    private val platform: Platform? = Platform.current
) {
    /** The newest published desktop release above [current], or null when [current] is the latest. */
    fun newer(current: AppVersion): Release? = parse(get(url), platform).filter { it.version > current }.maxByOrNull { it.version }

    /** SHA-256 of [name] as listed in the release's SHA256SUMS.txt, if it has one. */
    fun publishedChecksum(release: Release, name: String): String? {
        val sums = release.checksumsUrl ?: return null
        return parseChecksums(get(sums))[name]
    }

    private fun get(url: String): String {
        val response = http.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "OTA-Business-Desktop").GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) throw IOException("Update server answered HTTP ${response.statusCode()}")
        return response.body()
    }

    companion object {
        const val DEFAULT = "https://api.github.com/repos/Shubh-Pandiya/OTA/releases?per_page=30"
        const val TAG_PREFIX = "desktop-v"
        const val CHECKSUMS = "SHA256SUMS.txt"

        val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()

        /** Published (not draft, not pre-release) desktop releases, with [platform]'s installer when they have one. */
        fun parse(json: String, platform: Platform? = Platform.WINDOWS): List<Release> {
            val list = JSONArray(json)
            return (0 until list.length()).mapNotNull { i ->
                val r = list.getJSONObject(i)
                val tag = r.optString("tag_name")
                if (!tag.startsWith(TAG_PREFIX) || r.optBoolean("draft") || r.optBoolean("prerelease")) return@mapNotNull null
                val version = AppVersion.parse(tag.removePrefix(TAG_PREFIX)) ?: return@mapNotNull null
                val array = r.optJSONArray("assets") ?: JSONArray()
                val assets = (0 until array.length()).map { array.getJSONObject(it) }
                val sums = assets.firstOrNull { it.optString("name") == CHECKSUMS }?.optString("browser_download_url")
                val chosen = platform?.pick(assets.map { it.optString("name") })
                val installer = assets.firstOrNull { it.optString("name") == chosen }?.let { a ->
                    Installer(
                        a.optString("name"), a.optString("browser_download_url"), a.optLong("size", -1),
                        a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                    )
                }
                Release(version, tag, r.optString("body").trim(), r.optString("html_url"), installer, sums)
            }
        }

        /** `sha256sum` output: "<hex>  <name>" per line. */
        fun parseChecksums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
            val m = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").find(line.trim()) ?: return@mapNotNull null
            m.groupValues[2].trim() to m.groupValues[1].lowercase()
        }.toMap()

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
    }
}

class VerificationFailed(message: String) : IOException(message)

/** Downloads an installer next to the data folder and checks it before anything runs it. */
object Downloader {
    /**
     * Downloads [installer] into [dir] (resuming nothing: a partial file is thrown away), then checks
     * its size and SHA-256. [expectedSha] wins over the release's own digest; both must agree.
     */
    fun fetch(installer: Installer, dir: File, expectedSha: String?, http: HttpClient = ReleaseFeed.client, progress: (Float) -> Unit = {}): File {
        dir.mkdirs()
        val target = File(dir, installer.name)
        val sha = listOfNotNull(expectedSha, installer.sha256).distinct()
        if (sha.size > 1) throw VerificationFailed("The release's checksums disagree for ${installer.name}")
        if (target.exists() && verify(target, installer.size, sha.firstOrNull()) == null) return target
        target.delete()
        val part = File(dir, installer.name + ".part").apply { delete() }
        val response = http.send(
            HttpRequest.newBuilder(URI(installer.url)).timeout(Duration.ofMinutes(20)).header("User-Agent", "OTA-Business-Desktop").GET().build(),
            HttpResponse.BodyHandlers.ofInputStream()
        )
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("Download answered HTTP ${response.statusCode()}")
        }
        val total = installer.size.takeIf { it > 0 } ?: response.headers().firstValueAsLong("Content-Length").orElse(-1)
        response.body().use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                }
            }
        }
        verify(part, installer.size, sha.firstOrNull())?.let { part.delete(); throw VerificationFailed(it) }
        if (!part.renameTo(target)) throw IOException("Could not save ${target.name}")
        return target
    }

    /** Null when [file] matches, else why not. */
    fun verify(file: File, size: Long, sha256: String?): String? {
        if (size > 0 && file.length() != size) return "Downloaded ${file.length()} bytes, expected $size"
        if (sha256 != null && !ReleaseFeed.sha256(file).equals(sha256, ignoreCase = true)) return "Checksum does not match the published one"
        return null
    }
}
