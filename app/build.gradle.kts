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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${cloudSetting("SUPABASE_URL").trimEnd('/')}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${cloudSetting("SUPABASE_PUBLISHABLE_KEY")}\"")
    }

    buildTypes {
        release {
            // R8 shrinks code and drops unused resources; the app uses no reflection.
            optimization {
                enable = true
            }
            isShrinkResources = true
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}