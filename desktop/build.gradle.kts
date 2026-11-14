import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

buildscript {
    repositories { mavenCentral() }
    dependencies {
        // Re-packing the embedded PostgreSQL archive (trimPostgres below).
        classpath("org.apache.commons:commons-compress:1.28.0")
        classpath("org.tukaani:xz:1.11")
    }
}

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.3"
}

val appVersion = providers.gradleProperty("otaDesktopVersion").get()
group = "com.ota.desktop"
version = appVersion

kotlin { jvmToolchain(21) }

// Embedded PostgreSQL binaries for the OS being packaged (an installer only ever needs its own).
val pgPlatform: String = providers.gradleProperty("pgPlatform").orNull ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it.startsWith("arm") }
    when {
        os.contains("win") -> "windows-amd64"
        os.contains("mac") -> if (arm) "darwin-arm64v8" else "darwin-amd64"
        else -> if (arm) "linux-arm64v8" else "linux-amd64"
    }
}
val pgBinariesVersion = "17.11.0"

// ---------------------------------------------------------------- installer size
// zonky's PostgreSQL archive carries the whole distribution: every contrib extension, message
// translations, client libraries, pgAdmin's wxWidgets DLLs on Windows and, on macOS, two or three
// byte-identical copies of each shared library (ICU's 55 MB data library three times). trimPostgres
// re-packs it with only what the app's server runs: initdb, pg_ctl and postgres, the libraries they
// link, PL/pgSQL, the text-search dictionaries and encoding conversions initdb/the catalog refer to.
// Same PostgreSQL build and version, so existing database folders open unchanged. The unit tests
// boot the server from this trimmed archive too, so a missing piece fails `test` on each OS.
val postgresBinaries by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

/** Server-loadable modules kept: PL/pgSQL, snowball dictionaries, encoding conversions, replication plumbing. */
val pgModulesKept = Regex("""plpgsql|dict_snowball|libpqwalreceiver|pgoutput|euc2004_sjis2004|\w+_and_\w+""")
/** Executables the app runs (EmbeddedDb via zonky: initdb, pg_ctl, postgres). No pg_dump/psql: backups copy the data folder. */
val pgProgramsKept = setOf("initdb", "pg_ctl", "postgres")
/** Shared libraries nothing kept links (client tools, ecpg, pgAdmin/StackBuilder's wxWidgets, curl, ICU io/tools). */
val pgLibsDropped = Regex("""(lib)?(edit|ecpg|ecpg_compat|pgtypes|uuid|curl|xslt|testplug|icuio\d*|icutu\d*|wx\w+)([.-].*)?\.(dll|dylib|so)(\..*)?""")

fun keepPgEntry(path: String, windows: Boolean): Boolean {
    val p = path.removePrefix("./").trimEnd('/')
    val name = p.substringAfterLast('/')
    val dir = p.substringBeforeLast('/', "")
    return when {
        p.startsWith("include") || p.startsWith("doc") || p.startsWith("share/doc") -> false
        // Message translations: clusters are created with lc_messages=C, so they are never read.
        p.startsWith("share/locale") -> false
        "/extension/" in "/$p/" && !p.endsWith("/extension") -> name.startsWith("plpgsql")
        p.startsWith("lib/pkgconfig") || p.startsWith("lib/postgresql/pgxs") -> false
        name.endsWith(".a") || (dir == "lib" && name.endsWith(".lib")) -> false
        // Loadable server modules: lib/*.dll on Windows, lib/postgresql/*.dylib|so elsewhere.
        (windows && dir == "lib" && name.endsWith(".dll")) || dir == "lib/postgresql" ->
            name.substringBefore('.').matches(pgModulesKept)
        name.matches(pgLibsDropped) -> false
        dir == "bin" && !name.endsWith(".dll") -> name.removeSuffix(".exe") in pgProgramsKept
        else -> true
    }
}

/** Re-packs the binaries jar's .txz (same entry name, which zonky looks up) into [outDir]. */
fun trimPgArchive(jar: File, outDir: File, windows: Boolean, log: (String) -> Unit) {
    ZipFile(jar).use { zip ->
        val txz = zip.entries().asSequence().single { it.name.endsWith(".txz") }
        class Item(val entry: TarArchiveEntry, val data: ByteArray?)
        val items = mutableListOf<Item>()
        var before = 0L
        TarArchiveInputStream(XZInputStream(zip.getInputStream(txz).buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val data = if (e.isFile) tar.readAllBytes() else null
                before += data?.size ?: 0
                if (keepPgEntry(e.name, windows)) items += Item(e, data)
            }
        }
        // Byte-identical shared libraries (libfoo.dylib, libfoo.1.dylib, libfoo.1.2.dylib) become one
        // file plus relative symlinks, which zonky extracts as symlinks. Not on Windows (no copies there,
        // and creating symlinks needs extra rights).
        val links = mutableListOf<TarArchiveEntry>()
        if (!windows) {
            fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
            items.filter { it.data != null && it.entry.name.removePrefix("./").let { n -> n.startsWith("lib/") && n.count { c -> c == '/' } == 1 } }
                .groupBy { sha(it.data!!) }.values.filter { it.size > 1 }
                .forEach { same ->
                    val keep = same.maxWith(compareBy<Item> { it.entry.name.length }.thenBy { it.entry.name })
                    (same - keep).forEach { dup ->
                        items.remove(dup)
                        links += TarArchiveEntry(dup.entry.name, TarConstants.LF_SYMLINK).apply {
                            linkName = keep.entry.name.substringAfterLast('/')
                            modTime = dup.entry.modTime
                        }
                    }
                }
        }
        outDir.deleteRecursively()
        val out = File(outDir.apply { mkdirs() }, txz.name.substringAfterLast('/'))
        TarArchiveOutputStream(XZOutputStream(out.outputStream().buffered(), LZMA2Options(9))).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            // Real files first: a symlink's directory must exist when zonky creates it.
            for (it in items) {
                tar.putArchiveEntry(it.entry)
                it.data?.let(tar::write)
                tar.closeArchiveEntry()
            }
            for (link in links.sortedBy { it.name }) {
                tar.putArchiveEntry(link)
                tar.closeArchiveEntry()
            }
        }
        val after = items.sumOf { it.data?.size?.toLong() ?: 0L }
        log("PostgreSQL $pgPlatform: ${before / 1_048_576} MB -> ${after / 1_048_576} MB unpacked, " +
            "archive ${txz.compressedSize / 1_048_576} MB -> ${out.length() / 1_048_576} MB (${links.size} duplicate libraries as symlinks)")
    }
}

val trimPostgres by tasks.registering {
    val source = postgresBinaries
    val windows = pgPlatform.startsWith("windows")
    val outDir = layout.buildDirectory.dir("postgres/$pgPlatform")
    inputs.files(source)
    inputs.property("windows", windows)
    // Bump when the keep/drop rules above change.
    inputs.property("rules", 1)
    outputs.dir(outDir)
    doLast { trimPgArchive(source.singleFile, outDir.get().asFile, windows) { logger.lifecycle(it) } }
}

val postgresJar by tasks.registering(Jar::class) {
    from(trimPostgres)
    archiveFileName.set("embedded-postgres-binaries-$pgPlatform-$pgBinariesVersion-trimmed.jar")
    destinationDirectory.set(layout.buildDirectory.dir("postgres"))
    entryCompression = ZipEntryCompression.STORED // already xz-compressed
    isPreserveFileTimestamps = false
}

// skiko-awt-runtime-macos-arm64 carries the Intel library too. Compose copies the build machine's
// library next to the app and leaves the rest inside the jar, so the Mac app shipped an unusable
// 21 MB x64 library. This transform drops other platforms' skiko libraries from the runtime jar.
val skikoTarget: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it.startsWith("arm") }
    (if (os.contains("win")) "windows" else if (os.contains("mac")) "macos" else "linux") + "-" + (if (arm) "arm64" else "x64")
}
val skikoTrimmed: Attribute<Boolean> = Attribute.of("ota.skikoTrimmed", Boolean::class.javaObjectType)

abstract class TrimSkiko : TransformAction<TrimSkiko.Params> {
    interface Params : TransformParameters {
        @get:Input val target: Property<String>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val jar = input.get().asFile
        if (!jar.name.startsWith("skiko-awt-runtime-")) {
            outputs.file(jar)
            return
        }
        val native = Regex("""(lib)?skiko-(\w+-\w+)\.(dylib|dll|so)(\.sha256)?""")
        val target = parameters.target.get()
        // Same file name: Compose recognises the runtime jar by it.
        val out = outputs.file(jar.name)
        ZipInputStream(jar.inputStream().buffered()).use { zin ->
            ZipOutputStream(out.outputStream().buffered()).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val m = native.matchEntire(e.name.substringAfterLast('/'))
                    if (m != null && m.groupValues[2] != target) continue
                    zout.putNextEntry(ZipEntry(e.name).apply { time = e.time })
                    zin.copyTo(zout)
                    zout.closeEntry()
                }
            }
        }
    }
}

dependencies {
    attributesSchema { attribute(skikoTrimmed) }
    artifactTypes.getByName("jar") { attributes.attribute(skikoTrimmed, false) }
    registerTransform(TrimSkiko::class) {
        from.attribute(skikoTrimmed, false)
        to.attribute(skikoTrimmed, true)
        parameters { target.set(skikoTarget) }
    }
}
configurations.named("runtimeClasspath") { attributes.attribute(skikoTrimmed, true) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    implementation("io.zonky.test:embedded-postgres:2.2.2") {
        exclude(group = "io.zonky.test.postgres") // it pulls binaries for every OS; keep only ours below
    }
    postgresBinaries("io.zonky.test.postgres:embedded-postgres-binaries-$pgPlatform:$pgBinariesVersion")
    implementation(files(postgresJar)) // the same binaries without what the app never runs (trimPostgres)
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("org.postgresql:postgresql:42.7.8")

    implementation("org.json:json:20250517")
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    implementation("org.dhatim:fastexcel:0.19.0")
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.dhatim:fastexcel-reader:0.19.0")
}

// ---------------------------------------------------------------- code shared with the Android app
// Pure Kotlin files (number formats, units, models, cloud JSON mapping) are compiled from the
// Android sources as they are, so both apps follow exactly the same rules.
val androidSrc = rootDir.resolve("../app/src/main/java/com/example/otaapplication")
val sharedOut = layout.buildDirectory.dir("generated/shared/kotlin")
val copyShared by tasks.registering(Sync::class) {
    from(androidSrc) {
        include(
            "OrderNumber.kt", "AdviceNumber.kt", "Rates.kt", "Units.kt", "OrderFromData.kt",
            "data/SearchText.kt", "data/PartyMatch.kt", "data/Models.kt", "data/PaymentModels.kt", "data/SyncModels.kt",
            "cloud/SupabaseHttp.kt", "cloud/OrderCloudApi.kt", "cloud/CatalogCloudApi.kt",
            // Home: Open-Meteo client, the weather card and the roaming cat (Compose, common APIs only).
            "weather/Weather.kt", "ui/WeatherCard.kt", "ui/RoamingCat.kt"
        )
    }
    into(sharedOut.map { it.dir("com/example/otaapplication") })
}

// Letterhead logo and stamp vector paths, read from the Android resources.
val marksOut = layout.buildDirectory.dir("generated/shared/resources")
val copyMarks by tasks.registering {
    val logoXml = rootDir.resolve("../app/src/main/res/values/pdf_marks.xml")
    val stamp = rootDir.resolve("../app/src/main/res/raw/stamp_path.txt")
    inputs.files(logoXml, stamp)
    outputs.dir(marksOut)
    doLast {
        val dir = marksOut.get().dir("marks").asFile.apply { mkdirs() }
        val logo = Regex("""<string name="logo_path"[^>]*>([^<]+)</string>""").find(logoXml.readText())!!.groupValues[1].trim()
        dir.resolve("logo_path.txt").writeText(logo)
        stamp.copyTo(dir.resolve("stamp_path.txt"), overwrite = true)
    }
}

// Supabase settings: the committed supabase.properties, overridden by a local.properties if any.
val buildConfigOut = layout.buildDirectory.dir("generated/buildconfig/kotlin")
val generateBuildConfig by tasks.registering {
    val files = listOf(rootDir.resolve("../supabase.properties"), rootDir.resolve("../local.properties"), rootDir.resolve("local.properties"))
    inputs.files(files.filter { it.exists() })
    inputs.property("version", appVersion)
    outputs.dir(buildConfigOut)
    doLast {
        val props = Properties()
        files.filter { it.exists() }.forEach { f -> f.reader().use { props.load(it) } }
        fun q(key: String) = (props.getProperty(key) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"")
        val file = buildConfigOut.get().file("com/example/otaapplication/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.example.otaapplication
            |
            |object BuildConfig {
            |    const val SUPABASE_URL = "${q("SUPABASE_URL")}"
            |    const val SUPABASE_PUBLISHABLE_KEY = "${q("SUPABASE_PUBLISHABLE_KEY")}"
            |    const val VERSION_NAME = "$appVersion"
            |}
            |""".trimMargin()
        )
    }
}

sourceSets {
    main {
        kotlin.srcDir(copyShared.map { sharedOut.get() })
        kotlin.srcDir(generateBuildConfig.map { buildConfigOut.get() })
        resources.srcDir(copyMarks.map { marksOut.get() })
    }
}

// Regenerates packaging/ota.ico, ota.icns and ota.png from the vector logo (committed; run on change).
val generateIcons by tasks.registering(JavaExec::class) {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.ota.desktop.tools.IconGenKt")
    args(project.file("packaging").absolutePath)
    systemProperty("java.awt.headless", "true")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// Renders every main screen over demo data to build/screenshots (no window needed).
tasks.register<JavaExec>("screenshots") {
    group = "documentation"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ota.desktop.ScreenshotsKt")
    args(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}

// CI's update test: seeds an installed app's database or snapshots it (see E2eData.kt).
tasks.register<JavaExec>("e2eData") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ota.desktop.E2eDataKt")
}

// Manual check against the real cloud as the test account (LiveCloudCheck.kt); its own data folder.
tasks.register<JavaExec>("liveCheck") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ota.desktop.LiveCloudCheckKt")
    val home = providers.environmentVariable("OTA_LIVE_HOME").orElse(layout.buildDirectory.dir("live-check").map { it.asFile.path })
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dota.home=${home.get()}", "-Dota.documents=${home.get()}/documents", "-Djava.net.preferIPv4Stack=true") +
        // Behind a TLS-inspecting proxy, point OTA_LIVE_TRUSTSTORE at a truststore holding its CA.
        listOfNotNull(System.getenv("OTA_LIVE_TRUSTSTORE")?.let { "-Djavax.net.ssl.trustStore=$it" }) })
}

compose.desktop {
    application {
        mainClass = "com.ota.desktop.MainKt"
        // IPv4 stack: on Macs with IPv6 the JDK HTTP client times out connecting to GitHub's IPv4-only download CDN.
        jvmArgs += listOf("-Xms64m", "-Xmx768m", "-Dfile.encoding=UTF-8", "-Dsun.java2d.uiScale.enabled=true", "-Djava.net.preferIPv4Stack=true")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "OTA Business"
            packageVersion = appVersion
            description = "OTA Business desktop - orders, bills and payment advices for Onkar Textile Agency"
            vendor = "Onkar Textile Agency"
            copyright = "Onkar Textile Agency"
            modules("java.sql", "java.naming", "java.management", "jdk.crypto.ec", "jdk.unsupported", "java.net.http", "jdk.accessibility")
            windows {
                // Per-user install: no admin prompt, and PostgreSQL refuses to run as an administrator.
                perUserInstall = true
                dirChooser = false
                menu = true
                menuGroup = "OTA Business"
                shortcut = true
                upgradeUuid = "8d1f2b8e-3c5a-4f0e-9a51-0e6f4f1a7b21"
                iconFile.set(project.file("packaging/ota.ico"))
            }
            macOS {
                bundleID = "com.ota.desktop"
                iconFile.set(project.file("packaging/ota.icns"))
            }
        }
    }
}
