import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

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

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    implementation("io.zonky.test:embedded-postgres:2.2.2") {
        exclude(group = "io.zonky.test.postgres") // it pulls binaries for every OS; keep only ours below
    }
    implementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.11.0"))
    implementation("io.zonky.test.postgres:embedded-postgres-binaries-$pgPlatform")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("org.postgresql:postgresql:42.7.8")

    implementation("org.json:json:20250517")
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    implementation("org.dhatim:fastexcel:0.19.0")

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
