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
        applicationId = "com.example.otaapplication"
        minSdk = 35
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${cloudSetting("SUPABASE_URL").trimEnd('/')}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${cloudSetting("SUPABASE_PUBLISHABLE_KEY")}\"")
        buildConfigField("String", "UPDATE_FEED", "\"$updateFeed\"")
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