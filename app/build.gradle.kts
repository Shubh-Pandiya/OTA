import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Supabase project settings. The committed supabase.properties has the project URL and the public
// publishable (anon) key, so a fresh clone works as is. local.properties (git-ignored), a Gradle
// property or an environment variable override it. Never a secret key or database password.
fun loadProperties(name: String) = Properties().apply {
    val file = rootProject.file(name)
    if (file.isFile) file.inputStream().use { load(it) }
}
val localProperties = loadProperties("local.properties")
val supabaseProperties = loadProperties("supabase.properties")

fun cloudSetting(name: String): String =
    sequenceOf(
        localProperties.getProperty(name), providers.gradleProperty(name).orNull, System.getenv(name),
        supabaseProperties.getProperty(name),
    ).map { it?.trim()?.replace("\\", "")?.replace("\"", "") }.firstOrNull { !it.isNullOrEmpty() } ?: ""

// Phone app version (gradle.properties otaAndroidVersion, or -PotaAndroidVersion=). The versionCode
// is major*10000 + minor*100 + patch, the same formula the in-app updater reads from release tags.
val appVersion: String = providers.gradleProperty("otaAndroidVersion").get().trim()
val appVersionCode: Int = run {
    val p = appVersion.split('.').map { it.toIntOrNull() ?: error("otaAndroidVersion must look like 1.2.3, not $appVersion") }
    require(p.size == 3 && p[1] in 0..99 && p[2] in 0..99) { "otaAndroidVersion must look like 1.2.3, not $appVersion" }
    p[0] * 10_000 + p[1] * 100 + p[2]
}

// Release signing comes from the environment (CI decodes the GitHub secret into a file); never
// committed. Without it, release builds are signed with this computer's debug key, the same key
// Android Studio's debug builds use, so they install over each other.
val releaseKeystore: String? = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }
fun signingEnv(name: String, default: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() } ?: default

// Two apps come from this code. The normal build is com.ota.business, signed with the OTA release
// key and updated from `phone-v…` releases. -PotaLegacyBridge=true builds the last version of the
// old app (com.example.otaapplication, development key, `android-v…` releases), whose only extra
// job is to move a phone's data into the new app.
val legacyBridge = providers.gradleProperty("otaLegacyBridge").orNull == "true"
val newAppId = "com.ota.business"
val oldAppId = "com.example.otaapplication"
// SHA-256 of the signing certificates (as `apksigner verify --print-certs` prints them).
val releaseKeyCert = "860a577393efcfa6a48dbb305b2f4531688a32a29de337244beae72f8f55c3f0"
val oldKeyCert = "23d8484a257a568371341c78d8b75707dfdb57533e73f69c7f48257e6ef890fc"
// Emulator tests only: also trust this computer's debug key on the old-app side.
val testSourceCert = cloudSetting("OTA_MOVE_TEST_SOURCE_CERT")

// Only the emulator update test points the updater at another feed (a plain-HTTP server on the host).
val updateFeed = cloudSetting("OTA_UPDATE_FEED")

android {
    namespace = "com.example.otaapplication"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = if (legacyBridge) oldAppId else newAppId
        minSdk = 35
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Every supported phone is 64-bit; the emulator is x86_64 or arm64. Keeps SQLCipher's
        // native library from adding four copies to the APK.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        buildConfigField("String", "SUPABASE_URL", "\"${cloudSetting("SUPABASE_URL").trimEnd('/')}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${cloudSetting("SUPABASE_PUBLISHABLE_KEY")}\"")
        buildConfigField("String", "UPDATE_FEED", "\"$updateFeed\"")
        buildConfigField("boolean", "IS_BRIDGE", legacyBridge.toString())
        buildConfigField("String", "UPDATE_TAG_PREFIX", if (legacyBridge) "\"android-v\"" else "\"phone-v\"")
        // Updates of the new app must carry the release key, on top of Android's same-signer rule.
        buildConfigField("String", "UPDATE_CERT_SHA256", if (legacyBridge) "\"\"" else "\"$releaseKeyCert\"")
        buildConfigField("String", "MOVE_TARGET_PACKAGE", "\"$newAppId\"")
        buildConfigField("String", "MOVE_TARGET_CERT", "\"$releaseKeyCert\"")
        buildConfigField("String", "MOVE_SOURCE_PACKAGE", "\"$oldAppId\"")
        buildConfigField("String", "MOVE_SOURCE_CERTS", "\"" + listOf(oldKeyCert, testSourceCert).filter { it.isNotBlank() }.joinToString(",") + "\"")
        manifestPlaceholders["appLabel"] = if (legacyBridge) "OTA Business (old)" else "OTA Business"
        manifestPlaceholders["networkSecurityConfig"] =
            if (updateFeed.startsWith("http://10.0.2.2") || updateFeed.startsWith("http://127.0.0.1")) "@xml/network_security_config_update_test" else "@xml/network_security_config"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingEnv("ANDROID_KEYSTORE_PASSWORD", "android")
                keyAlias = signingEnv("ANDROID_KEY_ALIAS", "androiddebugkey")
                keyPassword = signingEnv("ANDROID_KEY_PASSWORD", "android")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks code and drops unused resources; the app uses no reflection.
            optimization {
                enable = true
            }
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    // Encrypted local databases (SQLCipher; key wrapped by the Android Keystore).
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    testImplementation(libs.junit)
    // Validates the PDF pages appended for advice images.
    testImplementation(libs.pdfbox)
    // Android's org.json is a stub in local unit tests; the real one parses release feeds there.
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}